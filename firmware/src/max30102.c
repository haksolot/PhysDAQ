#include <zephyr/kernel.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/sys/printk.h>
#include "max30102.h"

/* ── Register map ─────────────────────────────────────────────────── */
#define REG_INT_STATUS1  0x00
#define REG_INT_ENABLE1  0x02
#define REG_FIFO_WR_PTR  0x04
#define REG_OVF_COUNTER  0x05
#define REG_FIFO_RD_PTR  0x06
#define REG_FIFO_DATA    0x07
#define REG_FIFO_CONFIG  0x08
#define REG_MODE_CONFIG  0x09
#define REG_SPO2_CONFIG  0x0A
#define REG_LED1_PA      0x0C   /* Red LED pulse amplitude */
#define REG_LED2_PA      0x0D   /* IR LED pulse amplitude  */
#define REG_PART_ID      0xFF

/* ── Configuration constants ──────────────────────────────────────── */
#define PART_ID_EXPECTED  0x15U
#define MODE_RESET        (1U << 6)
#define MODE_SPO2         0x03U  /* SpO2: Red + IR channels */

/* SPO2_CONFIG: ADC_RGE=3 (16384 nA FS), SR=100 Hz, LED_PW=18-bit → 0x67
 *   bits[6:5] = 0b11  ADC full-scale range
 *   bits[4:2] = 0b001 sample rate 100 Hz
 *   bits[1:0] = 0b11  18-bit resolution (411 µs pulse width)       */
#define SPO2_CFG          0x67U

/* FIFO_CONFIG: no sample averaging, A_FULL threshold = 0, ROLLOVER_EN=1.
 * Rollover is essential: with it disabled (0x00), once the 32-deep FIFO fills
 * the sensor STOPS writing and the write pointer freezes, so max30102_fetch()
 * sees wr==rd forever and acquisition stalls permanently (device stays alive,
 * just silent). With rollover the FIFO overwrites the oldest sample instead,
 * so the write pointer always advances and data never stops. */
#define FIFO_CFG          0x10U

/* LED pulse amplitude: 0x1F × 200 µA/LSB ≈ 6.2 mA */
#define LED_PA            0x1FU

/* INT_ENABLE1 bit 6: PPG_RDY — one interrupt per new FIFO sample */
#define INT_PPG_RDY_EN    (1U << 6)

/* Mask for 18-bit ADC result stored in the 24-bit FIFO word */
#define FIFO_DATA_MASK    0x0003FFFFU

/* ── Device tree handles ──────────────────────────────────────────── */
#define MAX30102_NODE  DT_NODELABEL(max30102)

static const struct i2c_dt_spec  dev_i2c = I2C_DT_SPEC_GET(MAX30102_NODE);
static const struct gpio_dt_spec dev_int =
	GPIO_DT_SPEC_GET(MAX30102_NODE, int_gpios);

/* ── ISR → thread signalling ──────────────────────────────────────── */
static K_SEM_DEFINE(ppg_sem, 0, 1);
static struct gpio_callback int_cb_data;

static void ppg_int_isr(const struct device *port, struct gpio_callback *cb,
			uint32_t pins)
{
	ARG_UNUSED(port);
	ARG_UNUSED(cb);
	ARG_UNUSED(pins);
	k_sem_give(&ppg_sem);
}

/* ── Register helpers ─────────────────────────────────────────────── */
static int reg_write(uint8_t reg, uint8_t val)
{
	return i2c_reg_write_byte_dt(&dev_i2c, reg, val);
}

static int reg_read(uint8_t reg, uint8_t *val)
{
	return i2c_reg_read_byte_dt(&dev_i2c, reg, val);
}


/* ── Public API ───────────────────────────────────────────────────── */

int max30102_init(void)
{
	int ret;

	/* MAXREFDES117# has no accessible EN pin — MAX30102 is always powered
	 * when VIN is connected.  Only check I2C bus and INT gpio. */
	if (!device_is_ready(dev_i2c.bus) ||
	    !device_is_ready(dev_int.port)) {
		printk("MAX30102: DT devices not ready\n");
		return -ENODEV;
	}

	/* Allow 3V3 rail and device to stabilise.  After an nRF System Off wake
	 * or a programming reset the bus pins float (HiZ) for a brief window,
	 * which can leave the MAX30102 mid-transaction. */
	k_sleep(K_MSEC(200));

	/* Send 9 SCL pulses + STOP to unstick SDA if the bus is hung.
	 * Returns -ENOSYS if the driver doesn't implement it — safe to ignore. */
	i2c_recover_bus(dev_i2c.bus);
	k_sleep(K_MSEC(10));

	/* ① Software reset — retry up to 5× for post-wake transient NAKs */
	ret = -EIO;
	for (int i = 0; i < 5; i++) {
		ret = reg_write(REG_MODE_CONFIG, MODE_RESET);
		if (ret == 0) {
			break;
		}
		k_sleep(K_MSEC(20));
	}
	if (ret < 0) {
		printk("MAX30102: I2C error during reset (%d)\n", ret);
		return -EIO;
	}

	/* Poll until reset bit clears.  The chip may NACK briefly after reset
	 * is written, so treat any read error as "still resetting" rather than
	 * exiting the loop early with an uninitialised mode value. */
	uint8_t mode = MODE_RESET;
	int retries = 25;

	while (retries-- > 0) {
		k_sleep(K_MSEC(2));
		if (reg_read(REG_MODE_CONFIG, &mode) == 0 &&
		    !(mode & MODE_RESET)) {
			break;
		}
	}
	if (retries < 0 && (mode & MODE_RESET)) {
		printk("MAX30102: reset timed out\n");
		return -EIO;
	}
	k_sleep(K_MSEC(10));

	/* ② Verify part ID */
	uint8_t part_id;

	reg_read(REG_PART_ID, &part_id);
	if (part_id != PART_ID_EXPECTED) {
		printk("MAX30102: unexpected part ID 0x%02X (want 0x%02X)\n",
		       part_id, PART_ID_EXPECTED);
		return -EIO;
	}

	/* ③ Configure sensor registers */
	reg_write(REG_FIFO_WR_PTR,  0);
	reg_write(REG_OVF_COUNTER,  0);
	reg_write(REG_FIFO_RD_PTR,  0);
	reg_write(REG_FIFO_CONFIG,  FIFO_CFG);
	reg_write(REG_MODE_CONFIG,  MODE_SPO2);
	reg_write(REG_SPO2_CONFIG,  SPO2_CFG);
	reg_write(REG_LED1_PA,      LED_PA);
	reg_write(REG_LED2_PA,      LED_PA);
	reg_write(REG_INT_ENABLE1,  INT_PPG_RDY_EN);

	/* ④ Configure INT GPIO: input with pull-up, falling-edge interrupt */
	ret = gpio_pin_configure_dt(&dev_int, GPIO_INPUT);
	if (ret < 0) {
		printk("MAX30102: INT GPIO config failed (%d)\n", ret);
		return ret;
	}
	ret = gpio_pin_interrupt_configure_dt(&dev_int, GPIO_INT_EDGE_FALLING);
	if (ret < 0) {
		printk("MAX30102: INT interrupt configure failed (%d)\n", ret);
		return ret;
	}
	gpio_init_callback(&int_cb_data, ppg_int_isr, BIT(dev_int.pin));
	gpio_add_callback(dev_int.port, &int_cb_data);

	/* Clear the interrupt that latched during the >200 ms init sequence.
	 * The sensor is already streaming at 100 Hz, so PPG_RDY has almost
	 * certainly pulled INT LOW by now. If we leave it low after arming the
	 * falling-edge trigger, no further edge ever arrives and the acquisition
	 * thread blocks forever in max30102_wait_ready(). Reading INT_STATUS1
	 * deasserts INT HIGH so the next sample produces a real falling edge. */
	uint8_t int_status;
	reg_read(REG_INT_STATUS1, &int_status);

	/* Kick the consumer once in case the very first edge was missed while
	 * the interrupt was being armed — the FIFO already holds data. */
	k_sem_give(&ppg_sem);

	printk("MAX30102: ready (SpO2, 100 Hz, 18-bit ADC)\n");
	return 0;
}

int max30102_wait_ready(k_timeout_t timeout)
{
	/* Block until the PPG_RDY interrupt fires, or the timeout elapses.
	 * We intentionally ignore the take result and fall through on timeout:
	 * this self-heals a wedged interrupt handshake. The interrupt is
	 * edge-triggered (falling), so if a single I2C hiccup ever leaves INT
	 * asserted LOW with its status flag unread, no further falling edge can
	 * occur and the ISR would never fire again — the acquisition thread
	 * would block here forever (the exact freeze we hit). Clearing
	 * INT_STATUS1 below on every wake, including timeouts, deasserts INT
	 * HIGH so the next PPG_RDY produces a fresh edge, and the caller polls
	 * the FIFO regardless — so a missed edge costs at most one timeout of
	 * latency instead of a permanent hang. */
	(void)k_sem_take(&ppg_sem, timeout);

	/* Reading INT_STATUS1 clears the hardware interrupt line */
	uint8_t status;

	reg_read(REG_INT_STATUS1, &status);
	return 0;
}

int max30102_fetch(struct ppg_sample *out)
{
	uint8_t wr, rd;

	reg_read(REG_FIFO_WR_PTR, &wr);
	reg_read(REG_FIFO_RD_PTR, &rd);
	wr &= 0x1F;
	rd &= 0x1F;

	if (wr == rd) {
		return -ENODATA;  /* FIFO empty */
	}

	/* Burst-read one SpO2 sample: 3 bytes Red + 3 bytes IR. */
	uint8_t buf[6];

	if (i2c_burst_read_dt(&dev_i2c, REG_FIFO_DATA, buf, sizeof(buf)) < 0) {
		return -EIO;
	}

	/* ADC result occupies bits [17:0] of the 24-bit FIFO word */
	out->red = ((uint32_t)buf[0] << 16 |
		    (uint32_t)buf[1] <<  8 |
		    (uint32_t)buf[2]) & FIFO_DATA_MASK;
	out->ir  = ((uint32_t)buf[3] << 16 |
		    (uint32_t)buf[4] <<  8 |
		    (uint32_t)buf[5]) & FIFO_DATA_MASK;
	return 0;
}

void max30102_shutdown(void)
{
	reg_write(REG_MODE_CONFIG, 0x80); /* SHDN bit — LEDs off, ~0.7 µA */
	printk("MAX30102: shutdown\n");
}
