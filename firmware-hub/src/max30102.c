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

/* SPO2_CONFIG and the LED pulse amplitude live in max30102.h — the log file
 * header has to record both (storage.h), so they cannot be private here. */
#define SPO2_CFG  MAX30102_SPO2_CFG
#define LED_PA    MAX30102_LED_PA

/* FIFO_CONFIG: no sample averaging, A_FULL threshold = 0, ROLLOVER_EN=1.
 * Rollover is essential: with it disabled (0x00), once the 32-deep FIFO fills
 * the sensor STOPS writing and the write pointer freezes, so a drain sees
 * wr==rd forever and acquisition stalls permanently (device stays alive,
 * just silent). With rollover the FIFO overwrites the oldest sample instead,
 * so the write pointer always advances and data never stops. */
#define FIFO_CFG          0x10U

/* INT_ENABLE1 bit 6: PPG_RDY — one interrupt per new FIFO sample */
#define INT_PPG_RDY_EN    (1U << 6)

/* Mask for 18-bit ADC result stored in the 24-bit FIFO word */
#define FIFO_DATA_MASK    0x0003FFFFU

/* Bytes per SpO2 FIFO entry: 3 Red + 3 IR */
#define FIFO_BYTES_PER_SAMPLE  6

/* ── ISR → thread signalling ──────────────────────────────────────── */

static void ppg_int_isr(const struct device *port, struct gpio_callback *cb,
			uint32_t pins)
{
	ARG_UNUSED(port);
	ARG_UNUSED(pins);

	struct max30102_dev *dev = CONTAINER_OF(cb, struct max30102_dev, cb);

	/* Timestamp FIRST, before anything else and before the thread runs.
	 * Everything downstream — taking the semaphore, locking the bus,
	 * switching the mux channel, draining the FIFO — costs a variable few
	 * hundred microseconds, and the whole point of this instrument is the
	 * timing relationship between the two sites. k_uptime_ticks() is the
	 * 32768 Hz RTC (~30.5 us, 64-bit so it never wraps) and is the same
	 * source for both sensors. */
	dev->isr_ticks = k_uptime_ticks();

	k_sem_give(&dev->sem);
}

/* ── Register helpers ─────────────────────────────────────────────── */
static int reg_write(struct max30102_dev *dev, uint8_t reg, uint8_t val)
{
	return i2c_reg_write_byte_dt(&dev->i2c, reg, val);
}

static int reg_read(struct max30102_dev *dev, uint8_t reg, uint8_t *val)
{
	return i2c_reg_read_byte_dt(&dev->i2c, reg, val);
}


/* ── Public API ───────────────────────────────────────────────────── */

int max30102_init(struct max30102_dev *dev)
{
	int ret;

	/* MAXREFDES117# has no accessible EN/SHDN pin — the MAX30102 is always
	 * powered when VIN is connected. Only check the bus and INT gpio.
	 *
	 * dev->i2c.bus is the tca954x *channel* device, not the root I2C
	 * controller. device_is_ready() on it covers the channel, and the
	 * driver's own init ordering guarantees the root mux came up first. */
	if (!device_is_ready(dev->i2c.bus) ||
	    !device_is_ready(dev->int_gpio.port)) {
		printk("%s: DT devices not ready\n", dev->name);
		return -ENODEV;
	}

	k_sem_init(&dev->sem, 0, 1);

	/* Allow 3V3 rail and device to stabilise.  After an nRF System Off wake
	 * or a programming reset the bus pins float (HiZ) for a brief window,
	 * which can leave the MAX30102 mid-transaction. */
	k_sleep(K_MSEC(200));

	/* Send 9 SCL pulses + STOP to unstick SDA if the bus is hung.
	 * Returns -ENOSYS if the driver doesn't implement it — safe to ignore.
	 * Recovery targets the mux channel handle here, which forwards to the
	 * root controller: a stuck SDA is an electrical condition on the
	 * physical wires, not something the channel node owns. */
	i2c_recover_bus(dev->i2c.bus);
	k_sleep(K_MSEC(10));

	/* ① Software reset — retry up to 5× for post-wake transient NAKs */
	ret = -EIO;
	for (int i = 0; i < 5; i++) {
		ret = reg_write(dev, REG_MODE_CONFIG, MODE_RESET);
		if (ret == 0) {
			break;
		}
		k_sleep(K_MSEC(20));
	}
	if (ret < 0) {
		printk("%s: I2C error during reset (%d)\n", dev->name, ret);
		return -EIO;
	}

	/* Poll until reset bit clears.  The chip may NACK briefly after reset
	 * is written, so treat any read error as "still resetting" rather than
	 * exiting the loop early with an uninitialised mode value. */
	uint8_t mode = MODE_RESET;
	int retries = 25;

	while (retries-- > 0) {
		k_sleep(K_MSEC(2));
		if (reg_read(dev, REG_MODE_CONFIG, &mode) == 0 &&
		    !(mode & MODE_RESET)) {
			break;
		}
	}
	if (retries < 0 && (mode & MODE_RESET)) {
		printk("%s: reset timed out\n", dev->name);
		return -EIO;
	}
	k_sleep(K_MSEC(10));

	/* ② Verify part ID */
	uint8_t part_id;

	reg_read(dev, REG_PART_ID, &part_id);
	if (part_id != PART_ID_EXPECTED) {
		printk("%s: unexpected part ID 0x%02X (want 0x%02X)\n",
		       dev->name, part_id, PART_ID_EXPECTED);
		return -EIO;
	}

	/* ③ Configure sensor registers */
	reg_write(dev, REG_FIFO_WR_PTR,  0);
	reg_write(dev, REG_OVF_COUNTER,  0);
	reg_write(dev, REG_FIFO_RD_PTR,  0);
	reg_write(dev, REG_FIFO_CONFIG,  FIFO_CFG);
	reg_write(dev, REG_MODE_CONFIG,  MODE_SPO2);
	reg_write(dev, REG_SPO2_CONFIG,  SPO2_CFG);
	reg_write(dev, REG_LED1_PA,      LED_PA);
	reg_write(dev, REG_LED2_PA,      LED_PA);
	reg_write(dev, REG_INT_ENABLE1,  INT_PPG_RDY_EN);

	/* ④ Configure INT GPIO: input with pull-up, falling-edge interrupt.
	 * This path runs again on stall-recovery reinit, so remove any
	 * previously installed callback before re-adding it — gpio_add_callback
	 * pushes onto a list and would otherwise corrupt it on the second pass. */
	ret = gpio_pin_configure_dt(&dev->int_gpio, GPIO_INPUT);
	if (ret < 0) {
		printk("%s: INT GPIO config failed (%d)\n", dev->name, ret);
		return ret;
	}
	ret = gpio_pin_interrupt_configure_dt(&dev->int_gpio,
					      GPIO_INT_EDGE_FALLING);
	if (ret < 0) {
		printk("%s: INT interrupt configure failed (%d)\n",
		       dev->name, ret);
		return ret;
	}
	gpio_remove_callback(dev->int_gpio.port, &dev->cb);
	gpio_init_callback(&dev->cb, ppg_int_isr, BIT(dev->int_gpio.pin));
	gpio_add_callback(dev->int_gpio.port, &dev->cb);

	/* Clear the interrupt that latched during the >200 ms init sequence.
	 * The sensor is already streaming, so PPG_RDY has almost certainly
	 * pulled INT LOW by now. If we leave it low after arming the
	 * falling-edge trigger, no further edge ever arrives and the acquisition
	 * thread blocks forever in max30102_wait_ready(). Reading INT_STATUS1
	 * deasserts INT HIGH so the next sample produces a real falling edge. */
	uint8_t int_status;
	reg_read(dev, REG_INT_STATUS1, &int_status);

	/* Kick the consumer once in case the very first edge was missed while
	 * the interrupt was being armed — the FIFO already holds data. */
	dev->isr_ticks = k_uptime_ticks();
	k_sem_give(&dev->sem);

	dev->ready = true;
	printk("%s: ready (SpO2, %d Hz, 18-bit ADC, SPO2_CFG=0x%02X)\n",
	       dev->name, CONFIG_PHYSDAQ_PPG_ODR_HZ, SPO2_CFG);
	return 0;
}

/* Read the ISR timestamp without tearing.
 *
 * isr_ticks is 64-bit and written from interrupt context; a Cortex-M4 loads it
 * as two separate 32-bit accesses, so an interrupt landing between them yields
 * a value that never existed — and near a low-word rollover that value is
 * wrong by 2^32 ticks, i.e. about 36 hours. Cheap to prevent, catastrophic to
 * miss, and it would show up as a handful of impossible timestamps scattered
 * through an otherwise clean recording. */
static uint64_t read_isr_ticks(struct max30102_dev *dev)
{
	unsigned int key = irq_lock();
	uint64_t t = dev->isr_ticks;

	irq_unlock(key);
	return t;
}

int max30102_wait_ready(struct max30102_dev *dev, k_timeout_t timeout)
{
	/* Block until the PPG_RDY interrupt fires, or the timeout elapses.
	 * The timeout is not treated as an error: it self-heals a wedged
	 * interrupt handshake. The interrupt is edge-triggered (falling), so if
	 * a single I2C hiccup ever leaves INT asserted LOW with its status flag
	 * unread, no further falling edge can occur and the ISR would never
	 * fire again — the acquisition thread would block here forever (the
	 * exact freeze we hit). Clearing INT_STATUS1 below on every wake,
	 * including timeouts, deasserts INT HIGH so the next PPG_RDY produces a
	 * fresh edge, and the caller drains the FIFO regardless — so a missed
	 * edge costs at most one timeout of latency instead of a permanent hang. */
	int taken = k_sem_take(&dev->sem, timeout);

	/* Reading INT_STATUS1 clears the hardware interrupt line */
	uint8_t status;

	reg_read(dev, REG_INT_STATUS1, &status);
	return (taken == 0) ? 0 : -EAGAIN;
}

int max30102_drain(struct max30102_dev *dev, struct ppg_sample *buf,
		   size_t max_samples, bool *out_overflow, uint64_t *out_ticks)
{
	uint8_t wr, rd, ovf;

	if (out_overflow != NULL) {
		*out_overflow = false;
	}
	if (max_samples == 0) {
		return 0;
	}

	/*
	 * Latch the FIFO write pointer and the interrupt timestamp as a
	 * consistent pair, seqlock style: sample the timestamp, read the
	 * pointers, sample it again, and retry if it moved.
	 *
	 * Without this the two disagree. Reading the pointers takes a few I2C
	 * transactions — hundreds of microseconds at 400 kHz — and a PPG_RDY
	 * interrupt landing inside that window advances the write pointer after
	 * the timestamp was taken. The batch then contains one sample newer
	 * than the timestamp applied to it, so the newest sample is dated a
	 * full ODR period early: 10 ms at 100 Hz, on an instrument whose entire
	 * purpose is inter-site timing. Retrying costs one extra pointer read
	 * in the rare case it happens, which is a trade worth making.
	 *
	 * Three attempts is plenty — a retry only loses if another interrupt
	 * lands in the same window again, and the window is a few hundred
	 * microseconds out of a 10 ms period. If all three somehow race we fall
	 * through with the last reading, leaving the original one-period error
	 * on the newest sample of that single batch; the odds of reaching that
	 * are negligible and detecting it would cost another output parameter
	 * on every call.
	 */
	uint64_t t_before, t_after;
	int attempts = 3;

	do {
		t_before = read_isr_ticks(dev);

		if (reg_read(dev, REG_FIFO_WR_PTR, &wr) < 0 ||
		    reg_read(dev, REG_FIFO_RD_PTR, &rd) < 0) {
			return -EIO;
		}

		t_after = read_isr_ticks(dev);
	} while (t_before != t_after && --attempts > 0);

	if (out_ticks != NULL) {
		*out_ticks = t_after;
	}

	/* OVF_COUNTER is non-zero when rollover discarded samples because we
	 * fell behind. Cleared explicitly by writing 0 rather than relying on
	 * read-to-clear, which is not what this register does. */
	if (reg_read(dev, REG_OVF_COUNTER, &ovf) == 0 && ovf != 0) {
		if (out_overflow != NULL) {
			*out_overflow = true;
		}
		reg_write(dev, REG_OVF_COUNTER, 0);
	}

	wr &= 0x1F;
	rd &= 0x1F;

	/* wr == rd means empty, not full. A genuinely full FIFO cannot be
	 * distinguished from an empty one by the pointers alone, which is why
	 * the overflow counter above is the thing that reports falling behind. */
	size_t count = (size_t)((wr - rd) & 0x1F);

	if (count == 0) {
		return 0;
	}
	if (count > max_samples) {
		count = max_samples;   /* rest stays queued for the next call */
	}

	/* One burst for the whole batch. The single-PPG firmware re-read both
	 * FIFO pointers before every single sample; here each of those round
	 * trips would drag a mux channel-select along with it, so the batch is
	 * pulled in one transaction instead. Reading FIFO_DATA auto-advances
	 * the read pointer, so no pointer write-back is needed. */
	uint8_t raw[MAX30102_FIFO_DEPTH * FIFO_BYTES_PER_SAMPLE];

	if (i2c_burst_read_dt(&dev->i2c, REG_FIFO_DATA, raw,
			      count * FIFO_BYTES_PER_SAMPLE) < 0) {
		return -EIO;
	}

	for (size_t i = 0; i < count; i++) {
		const uint8_t *p = &raw[i * FIFO_BYTES_PER_SAMPLE];

		/* ADC result occupies bits [17:0] of the 24-bit FIFO word */
		buf[i].red = ((uint32_t)p[0] << 16 |
			      (uint32_t)p[1] <<  8 |
			      (uint32_t)p[2]) & FIFO_DATA_MASK;
		buf[i].ir  = ((uint32_t)p[3] << 16 |
			      (uint32_t)p[4] <<  8 |
			      (uint32_t)p[5]) & FIFO_DATA_MASK;
	}

	return (int)count;
}

uint64_t max30102_backdate_ticks(size_t index, size_t count)
{
	if (index + 1 >= count) {
		return 0;
	}

	/* The interrupt belongs to the NEWEST entry, buf[count - 1]; everything
	 * older is one ODR period further back. Done as a single 64-bit
	 * multiply-then-divide so the rounding error stays under one tick for
	 * the whole batch instead of compounding a rounded per-sample period. */
	uint64_t back = (uint64_t)(count - 1 - index);

	return (back * CONFIG_SYS_CLOCK_TICKS_PER_SEC) /
	       (uint64_t)CONFIG_PHYSDAQ_PPG_ODR_HZ;
}

void max30102_shutdown(struct max30102_dev *dev)
{
	reg_write(dev, REG_MODE_CONFIG, 0x80); /* SHDN bit — LEDs off, ~0.7 µA */
	dev->ready = false;
	printk("%s: shutdown\n", dev->name);
}
