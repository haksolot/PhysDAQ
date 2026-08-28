#include <zephyr/kernel.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/sys/printk.h>
#include <cmsis_core.h>
#include "watchdog.h"
#include "crashlog.h"

/* Reset the SoC if main() stops feeding the watchdog for this long. The loop
 * turns over every ~200 ms in normal operation, and nothing legitimate blocks
 * for seconds, so 8 s leaves a wide margin while still recovering quickly. */
#define WDT_TIMEOUT_MS  8000

static const struct device *const wdt = DEVICE_DT_GET(DT_NODELABEL(wdt0));
static int wdt_channel = -1;

/* Starvation monitor. The hardware watchdog recovers from a hang but erases
 * every trace of it — the console just stops mid-line and the next boot says
 * "cause=watchdog". A kernel timer, not a thread: its handler runs from the
 * system-clock interrupt, so it still fires when a cooperative thread (the
 * BT host's, for instance) spins and starves every other thread including
 * main. If even this stays silent before a watchdog reset, interrupts were
 * locked or a fault handler looped — a different class of bug.
 *
 * What it prints once main has gone STARVE_WARN_MS without feeding: the
 * stage marker main sets before each step, the kernel's view of main, the
 * thread that owns the CPU right now, and main's PC as saved on its stack
 * when it was switched out (Cortex-M exception frame: r0-r3, r12, lr, pc,
 * xpsr — pc is word 6; only meaningful while main is not the running
 * thread). Resolve with
 * `arm-zephyr-eabi-addr2line -e build/zephyr/zephyr.elf 0x...`. */
#define STARVE_WARN_MS   3000
#define STARVE_POLL_MS    250

static volatile const char *stage = "init";
static volatile int64_t     last_feed_ms;
static k_tid_t              main_tid;
static bool                 warned;

void watchdog_set_stage(const char *s)
{
	stage = s;
}

static void starve_check(struct k_timer *t)
{
	ARG_UNUSED(t);
	if (!main_tid) {
		return;
	}
	int64_t starved = k_uptime_get() - last_feed_ms;
	if (starved < STARVE_WARN_MS) {
		warned = false;
		return;
	}
	if (warned) {
		return;
	}
	warned = true;

	char state[32];
	k_tid_t cur = k_current_get();   /* the thread this IRQ interrupted */
	/* PC of the interrupted thread: this handler runs from the system
	 * clock interrupt, and the hardware stacked that thread's exception
	 * frame on its own (process) stack — word 6 is the PC. */
	uint32_t psp = __get_PSP();
	uint32_t pc  = 0;
	if (psp >= 0x20000000U && psp < 0x20040000U) {
		pc = ((uint32_t *)psp)[6];
	}
	const char *mstate = k_thread_state_str(main_tid, state, sizeof(state));
	const char *cname  = k_thread_name_get(cur) ? k_thread_name_get(cur) : "?";
	int cprio = k_thread_priority_get(cur);

	/* Into retained RAM first: if a cooperative thread is hogging the CPU
	 * the printk below is never transmitted, but the record survives the
	 * watchdog reset and main.c reports it on the next boot. */
	crashlog_note_hang((uint32_t)starved, (const char *)stage, mstate,
			   cname, cprio, pc);
	printk("WDT: main starved %lld ms at stage \"%s\" — main is %s; "
	       "CPU held by \"%s\" prio %d pc=0x%08x\n",
	       (long long)starved, (const char *)stage, mstate,
	       cname, cprio, (unsigned int)pc);
}

K_TIMER_DEFINE(starve_timer, starve_check, NULL);

int watchdog_init(void)
{
	if (!device_is_ready(wdt)) {
		printk("Watchdog: device not ready — running without WDT\n");
		return -ENODEV;
	}

	struct wdt_timeout_cfg cfg = {
		.flags = WDT_FLAG_RESET_SOC,
		.window = { .min = 0U, .max = WDT_TIMEOUT_MS },
		.callback = NULL,
	};

	wdt_channel = wdt_install_timeout(wdt, &cfg);
	if (wdt_channel < 0) {
		printk("Watchdog: install failed (%d)\n", wdt_channel);
		return wdt_channel;
	}

	/* Deliberately NOT passing WDT_OPT_PAUSE_IN_SLEEP: the freeze we guard
	 * against blocks main() on a K_FOREVER I2C wait, which idles the CPU. If
	 * the WDT paused during CPU sleep it would never fire in exactly that
	 * case. It keeps counting through WFI and is powered down only in nRF
	 * System OFF (deep sleep), where wake is a full reset anyway — so it can
	 * never spuriously reset the device while it is intentionally asleep. */
	int err = wdt_setup(wdt, WDT_OPT_PAUSE_HALTED_BY_DBG);
	if (err) {
		printk("Watchdog: setup failed (%d)\n", err);
		return err;
	}

	main_tid     = k_current_get();
	last_feed_ms = k_uptime_get();
	k_timer_start(&starve_timer, K_MSEC(STARVE_POLL_MS), K_MSEC(STARVE_POLL_MS));
	printk("Watchdog: armed (%d ms, reset-on-hang; starvation report after %d ms)\n",
	       WDT_TIMEOUT_MS, STARVE_WARN_MS);
	return 0;
}

void watchdog_feed(void)
{
	last_feed_ms = k_uptime_get();
	if (wdt_channel >= 0) {
		wdt_feed(wdt, wdt_channel);
	}
}
