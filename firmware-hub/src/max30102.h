#ifndef MAX30102_H
#define MAX30102_H

#include <zephyr/kernel.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/gpio.h>
#include <stdint.h>

/* Deepest the MAX30102 FIFO can be — 32 entries. A drain never returns more. */
#define MAX30102_FIFO_DEPTH  32

/*
 * Acquisition settings, exposed because the log file header has to record them
 * (see storage.h): a reader cannot interpret raw counts without knowing the
 * LED drive and the ADC range they were taken at.
 *
 * SPO2_CONFIG = ADC_RGE | SR | LED_PW
 *   bits[6:5] = 0b11  ADC full-scale range (16384 nA)
 *   bits[4:2] = SR    sample rate, from the table below
 *   bits[1:0] = 0b11  18-bit resolution (411 us pulse width)
 *
 * Only 50/100/200/400 Hz are offered. Those are the rates the part accepts in
 * SpO2 mode (two active LED channels) at the 411 us pulse width this firmware
 * uses — the faster entries in the datasheet's sample-rate table require a
 * shorter pulse width, which would change the ADC resolution and invalidate
 * the LED-current calibration inherited from the single-PPG node. 100 Hz is
 * the validated default; 200 and 400 are unproven on this hardware.
 */
#if   CONFIG_PHYSDAQ_PPG_ODR_HZ == 50
#define MAX30102_SPO2_SR_BITS  0U
#elif CONFIG_PHYSDAQ_PPG_ODR_HZ == 100
#define MAX30102_SPO2_SR_BITS  1U
#elif CONFIG_PHYSDAQ_PPG_ODR_HZ == 200
#define MAX30102_SPO2_SR_BITS  2U
#elif CONFIG_PHYSDAQ_PPG_ODR_HZ == 400
#define MAX30102_SPO2_SR_BITS  3U
#else
#error "CONFIG_PHYSDAQ_PPG_ODR_HZ must be 50, 100, 200 or 400"
#endif

#define MAX30102_SPO2_CFG  (0x60U | (MAX30102_SPO2_SR_BITS << 2) | 0x03U)

/* LED pulse amplitude: 0x1F × 200 µA/LSB ≈ 6.2 mA */
#define MAX30102_LED_PA    0x1FU

/* At the default 100 Hz this must still come out as the 0x67 the single-PPG
 * firmware ran with — the LED currents and the contact.c DC threshold were
 * tuned against exactly that setting. */
BUILD_ASSERT(CONFIG_PHYSDAQ_PPG_ODR_HZ != 100 || MAX30102_SPO2_CFG == 0x67U,
	     "SPO2_CFG regressed from the validated 0x67 at 100 Hz");

struct ppg_sample {
	uint32_t red;
	uint32_t ir;
};

/*
 * One MAX30102 instance.
 *
 * The single-PPG firmware kept the bus handle, the INT pin, the semaphore and
 * the callback as file-scope statics keyed off DT_NODELABEL(max30102). The hub
 * drives two identical parts at the same address behind an I2C mux, so all of
 * that state moves in here and every entry point takes the instance. The
 * register sequences themselves are unchanged.
 *
 * Declare instances with MAX30102_DT_DEFINE(). Do not touch the fields below
 * the divider from outside this module.
 */
struct max30102_dev {
	/* Configuration — set by MAX30102_DT_DEFINE() */
	struct i2c_dt_spec  i2c;       /* resolves to the mux channel, not the
	                                * root bus: the tca954x channel node is
	                                * itself an i2c controller */
	struct gpio_dt_spec int_gpio;  /* plain GPIO, bypasses the mux */
	uint8_t             source_id; /* goes into every stored record */
	const char         *name;      /* for log lines */

	/* ── internal ──────────────────────────────────────────────────── */
	struct gpio_callback cb;
	struct k_sem         sem;
	uint64_t             isr_ticks;  /* captured in the ISR, see below */
	bool                 ready;
};

#define MAX30102_DT_DEFINE(_node, _id, _name)                      \
	{                                                          \
		.i2c       = I2C_DT_SPEC_GET(_node),               \
		.int_gpio  = GPIO_DT_SPEC_GET(_node, int_gpios),   \
		.source_id = (_id),                                \
		.name      = (_name),                              \
	}

/* Power on, configure SpO2 mode (CONFIG_PHYSDAQ_PPG_ODR_HZ, 18-bit ADC,
 * ~6.2 mA LEDs), and arm the PPG_RDY GPIO interrupt.
 * Returns 0 or a negative errno. */
int max30102_init(struct max30102_dev *dev);

/* Block until this sensor signals a new FIFO sample via its INT pin, or the
 * timeout elapses. Returns 0 if an interrupt arrived, -EAGAIN on timeout —
 * which is not an error, see the comment in the .c. The caller drains the FIFO
 * either way. */
int max30102_wait_ready(struct max30102_dev *dev, k_timeout_t timeout);

/* Drain everything the FIFO currently holds into buf (at most max_samples).
 *
 * Returns the number of samples read (0 if the FIFO was empty), or a negative
 * errno on bus error. buf[count - 1] is the NEWEST sample and is the one
 * *out_ticks belongs to; older entries must be back-dated from the ODR by the
 * caller (max30102_backdate_ticks()).
 *
 * *out_ticks receives the timestamp captured *inside the ISR*, on the
 * k_uptime_ticks() monotonic base shared by both sensors. It is deliberately
 * not sampled by the caller after the fact: selecting the mux channel and
 * reading the FIFO adds hundreds of microseconds of variable delay, and
 * timestamping after that destroys the inter-site timing relationship this
 * instrument exists to measure.
 *
 * The timestamp is paired with the FIFO write pointer under a seqlock-style
 * retry, so it always describes the same sample as the batch it is returned
 * with — see the implementation for why a naive read is off by a full sample
 * period whenever an interrupt lands mid-read.
 *
 * *out_overflow is set true if the part reported dropped samples since the
 * last drain (OVF_COUNTER non-zero). Both output pointers may be NULL. */
int max30102_drain(struct max30102_dev *dev, struct ppg_sample *buf,
		   size_t max_samples, bool *out_overflow,
		   uint64_t *out_ticks);

/* Ticks to subtract from the interrupt timestamp for entry `index` of a batch
 * of `count` samples. Exact for index == count - 1 (returns 0).
 *
 * Computed in 64-bit from the configured ODR rather than by accumulating a
 * rounded per-sample period, so the error stays below one tick (~30.5 us)
 * instead of growing across the batch. Each batch re-anchors on a fresh
 * interrupt timestamp, so the error never accumulates across batches either. */
uint64_t max30102_backdate_ticks(size_t index, size_t count);

/* Set the SHDN bit in MODE_CONFIG — cuts LED drive and ADC; the chip stays
 * on I2C but draws ~0.7 uA. Call before entering deep sleep. */
void max30102_shutdown(struct max30102_dev *dev);

#endif /* MAX30102_H */
