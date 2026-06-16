#include <zephyr/kernel.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/adc/voltage_divider.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/sys/printk.h>
#include "battery.h"

#define SAMPLE_INTERVAL_MS  5000

/* VBAT divider on the XIAO nRF52840 Sense: R1=1M / R2=510k into AIN7
 * (P0.31), gated by the P0.14 read-enable pin (active low — leaving it
 * HIGH disconnects the divider and risks over-volting P0.31 above its
 * 3.6V input limit per Seeed's hardware notes). */
static const struct voltage_divider_dt_spec vbatt =
	VOLTAGE_DIVIDER_DT_SPEC_GET(DT_NODELABEL(battery_divider));
static const struct gpio_dt_spec read_en =
	GPIO_DT_SPEC_GET(DT_NODELABEL(battery_divider), power_gpios);

/* Single-cell LiPo discharge curve: voltage (mV) -> state of charge (%). */
struct battery_point {
	int32_t mv;
	uint8_t pct;
};
static const struct battery_point curve[] = {
	{4200, 100}, {4110, 90}, {4020, 80}, {3930, 70}, {3840, 60},
	{3750, 50},  {3660, 40}, {3570, 30}, {3480, 20}, {3390, 10}, {3300, 0},
};

static int64_t last_sample_ms;

static uint8_t percent_from_mv(int32_t mv)
{
	int n = ARRAY_SIZE(curve);

	if (mv >= curve[0].mv) {
		return 100;
	}
	if (mv <= curve[n - 1].mv) {
		return 0;
	}

	for (int i = 0; i < n - 1; i++) {
		if (mv <= curve[i].mv && mv >= curve[i + 1].mv) {
			int32_t span_mv  = curve[i].mv - curve[i + 1].mv;
			int32_t span_pct = curve[i].pct - curve[i + 1].pct;

			return curve[i + 1].pct + (mv - curve[i + 1].mv) * span_pct / span_mv;
		}
	}
	return 0;
}

int battery_init(void)
{
	if (!gpio_is_ready_dt(&read_en) || !adc_is_ready_dt(&vbatt.port)) {
		printk("Battery ADC/GPIO not ready\n");
		return -ENODEV;
	}

	if (gpio_pin_configure_dt(&read_en, GPIO_OUTPUT_INACTIVE) < 0) {
		printk("Battery read-enable GPIO config failed\n");
		return -EIO;
	}

	if (adc_channel_setup_dt(&vbatt.port) < 0) {
		printk("Battery ADC channel setup failed\n");
		return -EIO;
	}

	last_sample_ms = k_uptime_get() - SAMPLE_INTERVAL_MS;  /* sample on first poll */
	return 0;
}

int battery_poll(uint8_t *out_percent, int32_t *out_mv)
{
	int64_t now = k_uptime_get();

	if (now - last_sample_ms < SAMPLE_INTERVAL_MS) {
		return -EAGAIN;
	}
	last_sample_ms = now;

	int16_t buf;
	struct adc_sequence sequence = {
		.buffer = &buf,
		.buffer_size = sizeof(buf),
	};

	gpio_pin_set_dt(&read_en, 1);
	k_sleep(K_USEC(200));  /* let the divider settle before sampling */
	adc_sequence_init_dt(&vbatt.port, &sequence);
	int ret = adc_read_dt(&vbatt.port, &sequence);
	gpio_pin_set_dt(&read_en, 0);

	if (ret < 0) {
		return ret;
	}

	int32_t mv = buf;
	adc_raw_to_millivolts_dt(&vbatt.port, &mv);
	voltage_divider_scale_dt(&vbatt, &mv);

	*out_mv = mv;
	*out_percent = percent_from_mv(mv);
	return 0;
}
