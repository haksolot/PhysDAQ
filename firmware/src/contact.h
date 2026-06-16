#ifndef CONTACT_H
#define CONTACT_H

#include <stdint.h>
#include <stdbool.h>

/* Reset internal filter/peak-detector state. Call once at startup. */
void contact_init(void);

/* Feed one new IR sample — call once per PPG sample (same cadence as
 * max30102_fetch()). Updates DC/AC tracking and pulse validation. */
void contact_update(uint32_t ir_sample);

/* True only once a plausible heartbeat has been confirmed recently — a
 * steady IR DC bump alone (e.g. resting on a table) is not enough, see
 * contact.c for the validation method. */
bool contact_is_skin(void);

#endif /* CONTACT_H */
