#ifndef MAX30102_H
#define MAX30102_H

#include <zephyr/kernel.h>
#include <stdint.h>

struct ppg_sample {
	uint32_t red;
	uint32_t ir;
};

/* Power on, configure SpO2 mode (100 Hz, 18-bit ADC, ~6.2 mA LEDs),
 * and arm the PPG_RDY GPIO interrupt.  Returns 0 or a negative errno. */
int max30102_init(void);

/* Block until the MAX30102 signals a new FIFO sample via the INT pin.
 * Clears the hardware interrupt by reading INT_STATUS1.
 * Returns 0, or -EAGAIN if the timeout elapses. */
int max30102_wait_ready(k_timeout_t timeout);

/* Read one sample pair (Red + IR) from the FIFO.
 * Returns 0 on success, -ENODATA if the FIFO is empty, -EIO on bus error. */
int max30102_fetch(struct ppg_sample *out);

/* Set the SHDN bit in MODE_CONFIG — cuts LED drive and ADC; the chip stays
 * on I2C but draws ~0.7 µA.  Call before entering deep sleep. */
void max30102_shutdown(void);

#endif /* MAX30102_H */
