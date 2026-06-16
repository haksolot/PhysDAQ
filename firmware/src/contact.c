#include <zephyr/kernel.h>
#include "contact.h"

/* Same empirical threshold/approach as analysis/pipeline.py's
 * CONTACT_IR_MIN — open air reads ~100-300 raw IR counts on this
 * hardware (6.2 mA LED), skin contact ~29000+. Recalibrate both if LED
 * current/gain changes.
 *
 * This used to also require detecting an actual heartbeat (to reject an
 * inert reflective surface sitting at the right distance), but the
 * embedded peak detector never reliably found one on real hardware after
 * several tuning rounds — without a way to inspect the live raw
 * waveform, further blind tuning wasn't productive. Falling back to the
 * DC-only check already proven on the PC side.
 * Known limitation: a non-skin reflective surface could register as
 * "contact" too — there's no heartbeat check anymore. */
#define CONTACT_DC_MIN  5000.0f
#define DC_ALPHA        0.05f   /* ~0.8 Hz lowpass — reacts to finger
                                  * on/off within ~200 ms */

static float dc;

void contact_init(void)
{
	dc = 0.0f;
}

void contact_update(uint32_t ir_sample)
{
	dc += DC_ALPHA * ((float)ir_sample - dc);
}

bool contact_is_skin(void)
{
	return dc >= CONTACT_DC_MIN;
}

void contact_get_debug(struct contact_debug *out)
{
	out->dc = dc;
}
