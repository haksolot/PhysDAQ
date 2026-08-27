#include <zephyr/kernel.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/sys/printk.h>
#include "watchdog.h"

/* Reset the SoC if main() stops feeding the watchdog for this long. The loop
 * turns over every ~200 ms in normal operation, and nothing legitimate blocks
 * for seconds, so 8 s leaves a wide margin while still recovering quickly. */
#define WDT_TIMEOUT_MS  8000

static const struct device *const wdt = DEVICE_DT_GET(DT_NODELABEL(wdt0));
static int wdt_channel = -1;

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

	printk("Watchdog: armed (%d ms, reset-on-hang)\n", WDT_TIMEOUT_MS);
	return 0;
}

void watchdog_feed(void)
{
	if (wdt_channel >= 0) {
		wdt_feed(wdt, wdt_channel);
	}
}
