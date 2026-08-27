#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/sensor.h>
#include "imu.h"
#include "max30102.h"
#include "power.h"
#include "ble.h"
#include "battery.h"
#include "contact.h"
#include "watchdog.h"
#include "version.h"

/* Format a sensor_value (val1 + val2/1e6) as "±integer.milli" using printk.
 * Mirrors the print_val helper in imu.c, kept local to avoid coupling. */
#define SV_SIGN(v)  (((v)->val1 < 0 || (v)->val2 < 0) ? "-" : "")
#define SV_INT(v)   ((v)->val1 < 0 ? -(v)->val1 : (v)->val1)
#define SV_MILLI(v) (((v)->val2 < 0 ? -(v)->val2 : (v)->val2) / 1000)

/*
 * Identity line. This is what tells the host which firmware it is talking to
 * and how many PPG channels to expect.
 *
 * Sent over both transports, unlike most diagnostics. The manufacturer data in
 * the advertisement carries the same device type, but USB CDC has no
 * advertising at all, so without this line a cabled node is unidentifiable.
 * Re-sent on every new BLE connection: a central that connects late has missed
 * the boot copy, and on USB the console does not exist yet while the board
 * boots, so the boot copy is routinely lost there too.
 */
static void send_identity(void)
{
    char line[128];
    int n = snprintk(line, sizeof(line),
        "ID model=node proto=%d fw=%s ppg=1 sd=0 name=%s\n",
        PHYSDAQ_AD_PROTO_VERSION, PHYSDAQ_FW_STRING, ble_device_name());

    printk("%s", line);
    ble_send((const uint8_t *)line, n);
}

int main(void)
{
    if (imu_init() < 0) {
        return 0;
    }

    if (max30102_init() < 0) {
        printk("MAX30102 init failed — halting\n");
        return 0;
    }

    if (battery_init() < 0) {
        printk("Battery ADC init failed — continuing without battery status\n");
    }

    contact_init();
    power_init();

    if (ble_init() < 0) {
        printk("BLE init failed — running USB-only\n");
    }

    /* Arm the watchdog only after all (potentially slow) init has finished,
     * so init delays can never trip it. From here on, main() must feed it. */
    watchdog_init();

    printk("\n=== PhysDAQ: PPG + IMU acquisition ===\n");
    printk("PPG: SpO2 mode, 100 Hz, 18-bit ADC\n");
    printk("IMU: accel [m/s^2], gyro [rad/s]\n");
    printk("BLE: NUS advertising as PhysDAQ\n");
    printk("Battery: VBAT via P0.31/AIN7, sampled every 5 s\n");
    printk("Power: sleep after %d s idle\n\n",
           CONFIG_PHYSDAQ_IDLE_TIMEOUT_SEC);

    send_identity();

    int64_t last_data_ms = k_uptime_get();

    while (1) {
        /* Feed the watchdog once per outer iteration. The loop turns over at
         * least every 200 ms (the max30102_wait_ready timeout), so if anything
         * below hangs — a wedged I2C transfer in particular — the feeds stop
         * and the SoC resets itself within the WDT window instead of freezing
         * indefinitely. */
        watchdog_feed();

        /* Rising edge of the BLE link: a central that just connected has not
         * seen the boot copy of the identity line. */
        static bool was_linked;
        bool linked = ble_is_connected();
        if (linked && !was_linked) {
            send_identity();
        }
        was_linked = linked;

        /* Wait for a PPG_RDY interrupt, or fall through on the timeout. The
         * timeout path is harmless and self-healing: max30102_wait_ready()
         * clears the interrupt flag on every wake, and max30102_fetch() polls
         * the FIFO (returning -ENODATA when empty), so a missed edge costs one
         * timeout of latency rather than a permanent stall. */
        max30102_wait_ready(K_MSEC(200));

        struct ppg_sample ppg;
        struct imu_sample imu;
        bool got_data = false;

        while (max30102_fetch(&ppg) == 0) {
            got_data = true;
            if (imu_fetch_sample(&imu) < 0) {
                continue;
            }

            /* Format once, send to both USB console and BLE NUS */
            char line[160];
            int n = snprintk(line, sizeof(line),
                "PPG red=%u ir=%u | IMU "
                "ax=%s%d.%03d ay=%s%d.%03d az=%s%d.%03d "
                "gx=%s%d.%03d gy=%s%d.%03d gz=%s%d.%03d\n",
                ppg.red, ppg.ir,
                SV_SIGN(&imu.accel[0]), SV_INT(&imu.accel[0]), SV_MILLI(&imu.accel[0]),
                SV_SIGN(&imu.accel[1]), SV_INT(&imu.accel[1]), SV_MILLI(&imu.accel[1]),
                SV_SIGN(&imu.accel[2]), SV_INT(&imu.accel[2]), SV_MILLI(&imu.accel[2]),
                SV_SIGN(&imu.gyro[0]),  SV_INT(&imu.gyro[0]),  SV_MILLI(&imu.gyro[0]),
                SV_SIGN(&imu.gyro[1]),  SV_INT(&imu.gyro[1]),  SV_MILLI(&imu.gyro[1]),
                SV_SIGN(&imu.gyro[2]),  SV_INT(&imu.gyro[2]),  SV_MILLI(&imu.gyro[2]));

            printk("%s", line);

            /* BLE link can carry ~4 kB/s at default Windows connection params.
             * 100 Hz × 120 B = 12 kB/s would overflow the TX queue instantly.
             * Cap at one notification per 40 ms (~25 Hz) — plenty for display. */
            static int64_t ble_last_ms;
            int64_t now_ms = k_uptime_get();
            if (now_ms - ble_last_ms >= 40) {
                ble_last_ms = now_ms;
                ble_send((const uint8_t *)line, n);
            }

            contact_update(ppg.ir);
            power_update(imu.gyro);

            /* Internally rate-limited to once every 5 s — cheap to call
             * every sample. */
            uint8_t batt_pct;
            int32_t batt_mv;
            if (battery_poll(&batt_pct, &batt_mv) == 0) {
                char batt_line[48];
                int batt_n = snprintk(batt_line, sizeof(batt_line),
                    "Battery: %u%% (%d mV)\n", batt_pct, batt_mv);
                printk("%s", batt_line);
                ble_send((const uint8_t *)batt_line, batt_n);
            }
        }

        /* Sensor-stall recovery. If the FIFO produced nothing for over a
         * second the MAX30102 (or its I2C bus) has stalled — the CPU is fine,
         * so the watchdog won't help. Re-running init recovers the bus and
         * fully reconfigures the sensor, restarting acquisition instead of
         * leaving the whole pipeline (serial + BLE + power mgmt) frozen. */
        int64_t now = k_uptime_get();
        if (got_data) {
            last_data_ms = now;
        } else if (now - last_data_ms > 1000) {
            /* Dual-send like the battery line: over BLE, printk-only
             * diagnostics never reach the app, and a recurring reinit is
             * exactly the "slowdown before disconnect" signature worth
             * seeing in the System Logs pane. */
            static const char reinit_msg[] =
                "MAX30102: no data for >1s — reinitialising sensor\n";
            printk("%s", reinit_msg);
            ble_send((const uint8_t *)reinit_msg, sizeof(reinit_msg) - 1);
            max30102_init();
            last_data_ms = k_uptime_get();
        }
    }

    return 0;
}
