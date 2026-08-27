#ifndef BATTERY_H
#define BATTERY_H

#include <stdint.h>

/* Configure the VBAT ADC channel (AIN7) and the read-enable GPIO (P0.14).
 * Returns 0 on success, negative errno on failure. */
int battery_init(void);

/* Rate-limited battery sample: internally samples VBAT at most once every
 * 5 s, toggling the P0.14 read-enable gate only for the duration of the
 * read (leaving it enabled drains the battery — see Seeed forum report of
 * increased sleep current).
 * On a fresh sample, fills *out_percent and *out_mv and returns 0.
 * Returns -EAGAIN if the interval hasn't elapsed yet, or a negative errno
 * on ADC failure. */
int battery_poll(uint8_t *out_percent, int32_t *out_mv);

#endif /* BATTERY_H */
