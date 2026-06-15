#include <zephyr/kernel.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/sys/printk.h>
#include <hal/nrf_gpio.h>
#include <hal/nrf_power.h>
#include "power.h"
#include "max30102.h"

/* Motion threshold: 0.1 rad/s (≈ 5.7 °/s).
 * Compared as squared magnitude to avoid sqrtf.
 * Large enough to ignore gyro noise and micro-vibrations from surfaces,
 * small enough to detect any real intentional movement. */
#define MOTION_THRESH_SQ    (0.1f * 0.1f)
#define MOTION_THRESH_MRAD  100   /* same threshold in mrad/s, for the log */

#define IDLE_TIMEOUT_MS    ((int64_t)CONFIG_MAID_IDLE_TIMEOUT_SEC * 1000)
#define STATUS_INTERVAL_MS  5000  /* print idle status every 5 s */

static const struct device *i2c0 = DEVICE_DT_GET(DT_NODELABEL(i2c0));
#define IMU_ADDR  0x6A

/* IMU INT1: P0.11 (source: xiao_ble_common.dtsi lsm6ds3tr-c irq-gpios) */
#define IMU_INT1_ABS_PIN  NRF_GPIO_PIN_MAP(0, 11)

static int64_t last_motion_ms;
static int64_t last_status_ms;
static int32_t peak_mrad;   /* max ||gyro||₁ in mrad/s since last print */

static void imu_write_reg(uint8_t reg, uint8_t val)
{
	uint8_t buf[2] = {reg, val};
	i2c_write(i2c0, buf, sizeof(buf), IMU_ADDR);
}

static void configure_imu_wakeup(void)
{
	imu_write_reg(0x10, 0x20);    /* CTRL1_XL: 26 Hz / ±2 g */
	imu_write_reg(0x11, 0x00);    /* CTRL2_G: gyro power-down */
	imu_write_reg(0x56, BIT(3));  /* TAP_CFG0: LIR=1 (latched INT) */
	imu_write_reg(0x58, BIT(7));  /* TAP_CFG2: INTERRUPTS_ENABLE=1
	                                * Without this bit no interrupt fires,
	                                * regardless of MD1_CFG routing. */
	imu_write_reg(0x5B, 0x04);    /* WAKE_UP_THS: ~125 mg */
	imu_write_reg(0x5C, 0x00);    /* WAKE_UP_DUR: 1 ODR cycle */
	imu_write_reg(0x5E, BIT(5));  /* MD1_CFG: INT1_WU → INT1 pin */
}

static void enter_sleep(void)
{
	printk("Power: %d s idle — entering deep sleep (wake on motion)\n",
	       CONFIG_MAID_IDLE_TIMEOUT_SEC);
	k_sleep(K_MSEC(20));

	max30102_shutdown();
	configure_imu_wakeup();

	nrf_gpio_cfg_sense_input(IMU_INT1_ABS_PIN,
				 NRF_GPIO_PIN_PULLDOWN,
				 NRF_GPIO_PIN_SENSE_HIGH);

	nrf_power_system_off(NRF_POWER);
}

void power_init(void)
{
	last_motion_ms = k_uptime_get();
	last_status_ms = k_uptime_get();
	peak_mrad      = 0;
}

void power_update(const struct sensor_value gyro[3])
{
	/* Squared Euclidean magnitude (float, rad/s) for threshold comparison */
	float gx = (float)gyro[0].val1 + (float)gyro[0].val2 * 1e-6f;
	float gy = (float)gyro[1].val1 + (float)gyro[1].val2 * 1e-6f;
	float gz = (float)gyro[2].val1 + (float)gyro[2].val2 * 1e-6f;

	int64_t now = k_uptime_get();

	if (gx*gx + gy*gy + gz*gz > MOTION_THRESH_SQ) {
		last_motion_ms = now;
	}

	/* L1-norm in mrad/s (integer, no sqrtf) — used only for the status log.
	 * val1 is the integer rad/s part, val2 the µrad/s fractional part. */
	int32_t l1 = (abs(gyro[0].val1) + abs(gyro[1].val1) + abs(gyro[2].val1)) * 1000
		   + (abs(gyro[0].val2) + abs(gyro[1].val2) + abs(gyro[2].val2)) / 1000;
	if (l1 > peak_mrad) {
		peak_mrad = l1;
	}

	/* Periodic status line so you can watch the countdown in 'make term'.
	 * Format:  Power: idle Xs/30s | peak ~Xmrad/s (thresh 100mrad/s) */
	if (now - last_status_ms >= STATUS_INTERVAL_MS) {
		int32_t idle_s = (int32_t)((now - last_motion_ms) / 1000);
		printk("Power: idle %ds/%ds | peak ~%dmrad/s (thresh %dmrad/s)\n",
		       idle_s, CONFIG_MAID_IDLE_TIMEOUT_SEC,
		       peak_mrad, MOTION_THRESH_MRAD);
		last_status_ms = now;
		peak_mrad      = 0;
	}

	if ((now - last_motion_ms) >= IDLE_TIMEOUT_MS) {
		enter_sleep();
	}
}
