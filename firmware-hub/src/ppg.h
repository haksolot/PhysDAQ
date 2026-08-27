#ifndef PPG_H
#define PPG_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stdbool.h>

/* Number of local PPG sensors on this board. Both are MAX30102 at 0x57,
 * separated only by the mux channel they sit behind. */
#define PPG_SENSOR_COUNT  2

/* Bring up both sensors and start one acquisition thread per sensor.
 *
 * Returns 0 if at least one sensor came up. A single dead sensor is not fatal:
 * a half-populated recording is still worth having, and the bring-up checklist
 * explicitly walks the single-sensor case before the dual-sensor one. Returns
 * a negative errno only if neither sensor answered. */
int ppg_init(void);

/* Most recent state of one sensor, for the modules that need a live value
 * rather than the logged stream: contact.c wants the IR DC level, and the
 * status line wants something to display. */
struct ppg_live {
	uint32_t red;
	uint32_t ir;
	uint64_t t_ticks;      /* absolute, same base as everything else */
	uint32_t samples;      /* total drained since boot */
	uint32_t overflows;    /* FIFO overflow events reported by the part */
	uint32_t reinits;      /* stall recoveries */
	bool     alive;        /* produced data within the stall timeout */
};

/* index must be < PPG_SENSOR_COUNT. */
void ppg_get_live(uint8_t index, struct ppg_live *out);

/* Put both sensors into shutdown (LEDs off). Called before deep sleep. */
void ppg_shutdown_all(void);

#endif /* PPG_H */
