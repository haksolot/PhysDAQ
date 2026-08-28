#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/sensor.h>
#include "imu.h"
#include "ppg.h"
#include "storage.h"
#include "power.h"
#include "ble.h"
#include "battery.h"
#include "contact.h"
#include "watchdog.h"
#include "selftest.h"
#include "version.h"
#include "command.h"
#include "satellites.h"
#include "crashlog.h"

/* Format a sensor_value (val1 + val2/1e6) as "±integer.milli" using printk.
 * Mirrors the print_val helper in imu.c, kept local to avoid coupling. */
#define SV_SIGN(v)  (((v)->val1 < 0 || (v)->val2 < 0) ? "-" : "")
#define SV_INT(v)   ((v)->val1 < 0 ? -(v)->val1 : (v)->val1)
#define SV_MILLI(v) (((v)->val2 < 0 ? -(v)->val2 : (v)->val2) / 1000)

/* Supervisor cadence. Acquisition and storage run on their own threads now, so
 * this loop only has to service the things that are inherently periodic: the
 * watchdog, the IMU, the BLE stream and the status lines. 40 ms lands it on
 * the same ~25 Hz the BLE link is rate-limited to. */
#define SUPERVISOR_PERIOD_MS  40
#define HUB_STATUS_INTERVAL_MS  5000

/* How often to re-run the bus diagnostics while nothing is acquiring.
 *
 * The console is a USB CDC ACM port, so it does not exist yet while the board
 * is booting — by the time a terminal can attach, the boot output is long
 * gone. That makes a once-at-boot diagnostic useless in exactly the situation
 * it was written for. Repeating it while the board is broken means you can
 * attach whenever and still get the verdict. */
#define DIAG_INTERVAL_MS  10000

/* Set once acquisition threads are running — used only for the uptime display. */
static int64_t acq_start_ms;

/*
 * Per-sensor rate in tenths of a hertz, measured over the last status interval
 * rather than since acquisition began.
 *
 * A since-start average is contaminated by a fixed head start: each sensor's
 * 32-deep FIFO is already filling while the *other* sensor's init runs, and a
 * failing init burns hundreds of milliseconds of retries. That showed up as a
 * rate reading a few percent high and slowly decaying, which looks exactly
 * like a real drift and is not one. A windowed rate has no such offset, and it
 * also surfaces a sensor that degrades mid-session — which a since-start
 * average would bury under all the healthy minutes before it.
 *
 * This is the number that says whether a sensor runs at the configured ODR.
 * "It produces samples" is not "it produces them at 100 Hz", and a low rate is
 * sample loss even when the overflow counter is clean.
 */
static uint32_t windowed_dhz(uint8_t idx, uint32_t samples, int64_t now_ms)
{
	static uint32_t prev_samples[PPG_SENSOR_COUNT];
	static int64_t  prev_ms[PPG_SENSOR_COUNT];

	if (idx >= PPG_SENSOR_COUNT) {
		return 0;
	}

	int64_t  dt = now_ms - prev_ms[idx];
	uint32_t dn = samples - prev_samples[idx];   /* wraps correctly */

	prev_samples[idx] = samples;
	prev_ms[idx]      = now_ms;

	if (dt < 1000) {
		return 0;   /* window too short to mean anything */
	}
	return (uint32_t)(((uint64_t)dn * 10000) / (uint64_t)dt);
}

static void send_hub_status(void)
{
	struct ppg_live      s0, s1;
	struct storage_stats st;

	ppg_get_live(0, &s0);
	ppg_get_live(1, &s1);
	storage_get_stats(&st);

	int64_t  now_ms = k_uptime_get();
	uint32_t d0 = windowed_dhz(0, s0.samples, now_ms);
	uint32_t d1 = windowed_dhz(1, s1.samples, now_ms);
	uint32_t up_s = (uint32_t)((now_ms - acq_start_ms) / 1000);

	/* Deliberately does NOT start with "PPG". bridge.py matches the sample
	 * line with re.search(r'PPG\s+red=...') and silently discards any other
	 * line beginning with those three characters, so a status line prefixed
	 * "PPG..." would vanish without trace. The same rule governs every
	 * reply in command.c. */
	/* 256, not 224: `open=` and `sat=n/N` were added after the original
	 * sizing and the line was already close to full. */
	char line[256];
	int n = snprintk(line, sizeof(line),
		"Hub: up=%us | s0 %s n=%u %u.%uHz ovf=%u ri=%u ir=%u | "
		"s1 %s n=%u %u.%uHz ovf=%u ri=%u ir=%u | "
		"sd sess=%u open=%u w=%u drop=%u sync=%u err=%u qmax=%u "
		"sat=%u/%u\n",
		up_s,
		s0.alive ? "ok" : "DEAD", s0.samples, d0 / 10, d0 % 10,
		s0.overflows, s0.reinits, s0.ir,
		s1.alive ? "ok" : "DEAD", s1.samples, d1 / 10, d1 % 10,
		s1.overflows, s1.reinits, s1.ir,
		st.session_id, storage_session_is_open() ? 1U : 0U,
		st.records_written, st.records_dropped,
		st.syncs, st.write_errors, st.max_queue_used,
		satellites_count(), SATELLITES_MAX);

	printk("%s", line);
	ble_send((const uint8_t *)line, n);
}

int main(void)
{
	/* Before anything else: captures the previous boot's crash/hang record
	 * and the reset cause while nothing has had a chance to clobber them. */
	crashlog_init();

	/* Bus self-test first, before anything that depends on the bus. On a
	 * hand-wired prototype the difference between "the mux is missing" and
	 * "the sensor behind the mux is missing" is the difference between two
	 * completely different repairs, and an -EIO out of max30102_init()
	 * cannot tell them apart. */
	selftest_run();

	if (imu_init() < 0) {
		printk("IMU init failed — continuing without motion data\n");
	}

	/* Storage first: it anchors the session clock, and the acquisition
	 * threads read that epoch once when they start. Starting them earlier
	 * would date every record against an epoch of zero. */
	bool logging = (storage_init() == 0);

	if (!logging) {
		printk("Storage init failed — acquisition will run but NOTHING "
		       "WILL BE RECORDED. Check the card and reboot.\n");
	}

	bool acquiring = (ppg_init() == 0);

	acq_start_ms = k_uptime_get();

	if (!acquiring) {
		/* Deliberately not a halt. The single-PPG firmware returned
		 * here, which kills the console output too — on a prototype
		 * being wired up, staying alive so the self-test result and the
		 * periodic status line stay readable is worth far more than
		 * exiting cleanly. */
		printk("No PPG sensor available — staying up so the self-test "
		       "output above remains readable. Fix the wiring and "
		       "reset.\n");
	}

	if (battery_init() < 0) {
		printk("Battery ADC init failed — continuing without battery "
		       "status\n");
	}

	contact_init();
	power_init();

	if (ble_init() < 0) {
		printk("BLE init failed — running USB-only\n");
	}

	/* Roster before the command thread, so a "sat.list" arriving in the
	 * first milliseconds answers with what is actually stored rather than
	 * with an empty list. A failure here is not fatal: the roster still
	 * works for this boot, it just will not survive a reset. */
	if (satellites_init() < 0) {
		printk("Satellites: roster will not persist across reboots\n");
	}

	command_init();

	/* Arm the watchdog only after all (potentially slow) init has finished,
	 * so init delays can never trip it. From here on, this loop must feed
	 * it. Note the scope change from the single-PPG firmware: the watchdog
	 * now guards the supervisor, not acquisition. A wedged sensor is
	 * handled by the per-sensor stall recovery in ppg.c, which is the right
	 * place for it — resetting the whole SoC would close the session file. */
	watchdog_init();

	printk("\n=== PhysDAQ Hub: dual PPG + microSD ===\n");
	/* Mux channels 1 and 2, which the breakout silk-screens "2" and "3".
	 * The banner used the silk-screen numbers; every other message and all
	 * of the docs use the channel indices, so it read as a third pair. */
	printk("PPG: 2× MAX30102 behind PCA9546A ch1/ch2, SpO2 %d Hz, 18-bit\n",
	       CONFIG_PHYSDAQ_PPG_ODR_HZ);
	printk("IMU: accel [m/s^2], gyro [rad/s]\n");
	printk("Time base: k_uptime_ticks() @ %d Hz (%d us), captured in ISR\n",
	       CONFIG_SYS_CLOCK_TICKS_PER_SEC,
	       1000000 / CONFIG_SYS_CLOCK_TICKS_PER_SEC);
	printk("Storage: %s\n", logging ? "active" : "UNAVAILABLE");
	printk("BLE: NUS advertising\n");
	printk("Deep sleep: %s\n",
	       IS_ENABLED(CONFIG_PHYSDAQ_DEEP_SLEEP) ? "enabled" : "disabled");
	printk("\n");

	command_send_identity();

	int64_t last_status_ms = k_uptime_get();
	int64_t last_diag_ms   = k_uptime_get();

	while (1) {
		/* Feed the watchdog once per iteration. The loop turns over
		 * every SUPERVISOR_PERIOD_MS, so if anything below hangs the
		 * feeds stop and the SoC resets itself within the WDT window
		 * instead of freezing indefinitely. */
		watchdog_feed();

		/* Rising edge of the BLE link: a central that just connected
		 * has not seen the boot copy of the identity line. */
		static bool was_linked;
		bool linked = ble_is_connected();
		if (linked && !was_linked) {
			command_send_identity();
		}
		was_linked = linked;

		struct imu_sample imu;
		bool have_imu = (imu_fetch_sample(&imu) == 0);

		struct ppg_live s0, s1;
		ppg_get_live(0, &s0);
		ppg_get_live(1, &s1);

		if (have_imu) {
			/* The single-PPG line format, extended with a
			 * "| PPG1 red=… ir=…" section for sensor 1.
			 *
			 * Deliberately one line, not two. bridge.py matches the
			 * sample with re.search(r'PPG\s+red=…'), so this
			 * still matches on sensor 0 and an un-updated bridge
			 * keeps working — while a *separate* line beginning
			 * "PPG1" would be silently discarded by the same code
			 * path, which drops any unmatched line starting with
			 * "PPG". Keeping both sensors in one message also gives
			 * the pair a single arrival timestamp, so the host
			 * cannot desynchronise the two channels.
			 *
			 * 192 bytes, not 160: the PPG1 section adds up to ~28 B
			 * and the old buffer left no room for it. */
			char line[192];
			int n = snprintk(line, sizeof(line),
				"PPG red=%u ir=%u | PPG1 red=%u ir=%u | IMU "
				"ax=%s%d.%03d ay=%s%d.%03d az=%s%d.%03d "
				"gx=%s%d.%03d gy=%s%d.%03d gz=%s%d.%03d\n",
				s0.red, s0.ir, s1.red, s1.ir,
				SV_SIGN(&imu.accel[0]), SV_INT(&imu.accel[0]), SV_MILLI(&imu.accel[0]),
				SV_SIGN(&imu.accel[1]), SV_INT(&imu.accel[1]), SV_MILLI(&imu.accel[1]),
				SV_SIGN(&imu.accel[2]), SV_INT(&imu.accel[2]), SV_MILLI(&imu.accel[2]),
				SV_SIGN(&imu.gyro[0]),  SV_INT(&imu.gyro[0]),  SV_MILLI(&imu.gyro[0]),
				SV_SIGN(&imu.gyro[1]),  SV_INT(&imu.gyro[1]),  SV_MILLI(&imu.gyro[1]),
				SV_SIGN(&imu.gyro[2]),  SV_INT(&imu.gyro[2]),  SV_MILLI(&imu.gyro[2]));

			/* One notification per loop, i.e. ~25 Hz. The link
			 * carries ~4 kB/s; the raw 100 Hz × 2 sensor stream
			 * would be several times that and would overflow the TX
			 * queue. The SD file is the record of truth — BLE is
			 * only a live view. */
			ble_send((const uint8_t *)line, n);

			contact_update(s0.ir);
			power_update(imu.gyro);
		}

		/* Internally rate-limited to once every 5 s — cheap to call
		 * every iteration. */
		uint8_t batt_pct;
		int32_t batt_mv;
		if (battery_poll(&batt_pct, &batt_mv) == 0) {
			char batt_line[48];
			int batt_n = snprintk(batt_line, sizeof(batt_line),
				"Battery: %u%% (%d mV)\n", batt_pct, batt_mv);
			printk("%s", batt_line);
			ble_send((const uint8_t *)batt_line, batt_n);
		}

		int64_t now = k_uptime_get();
		if (now - last_status_ms >= HUB_STATUS_INTERVAL_MS) {
			last_status_ms = now;
			send_hub_status();
		}

		/* While nothing is acquiring, keep re-running the diagnostics
		 * so they are readable whenever a terminal attaches.
		 *
		 * Gated strictly on `acquiring`, which is false only when
		 * ppg_init() started no threads at all. That matters: the
		 * self-test writes the mux control register directly, behind
		 * the tca954x driver's back, and doing that while an
		 * acquisition thread is mid-transfer would corrupt it. With no
		 * threads running, the bus is ours alone. */
		if (!acquiring && now - last_diag_ms >= DIAG_INTERVAL_MS) {
			last_diag_ms = now;

			if (!logging) {
				printk("\nStorage: still unavailable "
				       "(last error %d)\n",
				       storage_last_error());
			}
			selftest_run();
		}

		k_sleep(K_MSEC(SUPERVISOR_PERIOD_MS));
	}

	return 0;
}
