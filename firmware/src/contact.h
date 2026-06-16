#ifndef CONTACT_H
#define CONTACT_H

#include <stdint.h>
#include <stdbool.h>

/* Reset internal filter state. Call once at startup. */
void contact_init(void);

/* Feed one new IR sample — call once per PPG sample (same cadence as
 * max30102_fetch()). Updates the DC-level estimate. */
void contact_update(uint32_t ir_sample);

/* True when the IR DC level indicates something is on the sensor — see
 * CONTACT_DC_MIN in contact.c. DC-only: no heartbeat validation, so an
 * inert reflective surface at the right distance could also read true. */
bool contact_is_skin(void);

/* Diagnostic snapshot for the periodic status line. */
struct contact_debug {
	float dc;  /* raw IR DC estimate (counts) */
};
void contact_get_debug(struct contact_debug *out);

#endif /* CONTACT_H */
