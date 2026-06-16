#include <math.h>
#include <zephyr/kernel.h>
#include "contact.h"

/* Same empirical threshold as analysis/pipeline.py CONTACT_IR_MIN — open
 * air reads ~100-300 raw IR counts on this hardware (6.2 mA LED), skin
 * contact ~29000+. Recalibrate both if LED current/gain changes. */
#define CONTACT_DC_MIN  5000.0f

/* One-pole IIR coefficients (alpha ~= dt / tau, dt = 10 ms @ 100 Hz). */
#define DC_ALPHA       0.01f  /* ~1 s time constant: slow baseline (DC) */
#define AC_ALPHA       0.3f   /* ~33 ms: damps ADC sample noise without
                                * smearing the cardiac waveform shape */
#define RMS_ALPHA      0.01f  /* ~1 s: adaptive amplitude reference */

/* A reflective surface (table, sleeve) gives a steady DC bump but no real
 * pulsation. We only call it "skin" once PULSE_BEATS_REQUIRED consecutive
 * peak-to-peak intervals land in a plausible cardiac range — vibration
 * noise or a static surface essentially never produces that pattern.
 * Known limitation: a surface vibrating periodically within 40-180 BPM
 * (e.g. on top of a washing machine) could still pass this check. */
#define PULSE_MIN_IBI_MS      333   /* 180 BPM ceiling */
#define PULSE_MAX_IBI_MS     1500   /* 40 BPM floor */
#define PULSE_BEATS_REQUIRED    2   /* consecutive valid IBIs to confirm */
#define PULSE_VALIDITY_MS    6000   /* re-validate if no beat for this long */

static float   dc, ac_smooth, ac_prev, ac_prev2, ac_rms_ema;
static int64_t last_peak_ms;
static int     valid_run;
static bool    skin_validated;
static int64_t last_valid_beat_ms;

void contact_init(void)
{
	dc = ac_smooth = ac_prev = ac_prev2 = ac_rms_ema = 0.0f;
	last_peak_ms       = 0;
	valid_run          = 0;
	skin_validated     = false;
	last_valid_beat_ms = 0;
}

void contact_update(uint32_t ir_sample)
{
	int64_t now = k_uptime_get();

	dc += DC_ALPHA * ((float)ir_sample - dc);

	if (dc < CONTACT_DC_MIN) {
		/* Nothing on the sensor at all — reset pulse state. */
		ac_smooth = ac_prev = ac_prev2 = ac_rms_ema = 0.0f;
		valid_run      = 0;
		skin_validated = false;
		return;
	}

	if (skin_validated && (now - last_valid_beat_ms) > PULSE_VALIDITY_MS) {
		skin_validated = false;  /* beats stopped — re-validate */
	}

	float ac = (float)ir_sample - dc;

	ac_smooth  += AC_ALPHA  * (ac - ac_smooth);
	ac_rms_ema += RMS_ALPHA * (ac_smooth * ac_smooth - ac_rms_ema);

	/* Causal local-maximum detector (1-sample lag) on the smoothed AC
	 * channel, gated by an adaptive amplitude floor so flat noise never
	 * counts as a peak. */
	bool is_peak = (ac_prev > ac_prev2) && (ac_prev > ac_smooth) &&
		       (ac_prev > sqrtf(ac_rms_ema));

	ac_prev2 = ac_prev;
	ac_prev  = ac_smooth;

	if (!is_peak) {
		return;
	}

	int64_t ibi_ms = now - last_peak_ms;

	last_peak_ms = now;

	if (ibi_ms >= PULSE_MIN_IBI_MS && ibi_ms <= PULSE_MAX_IBI_MS) {
		valid_run++;
		last_valid_beat_ms = now;
		if (valid_run >= PULSE_BEATS_REQUIRED) {
			skin_validated = true;
		}
	} else {
		valid_run = 0;
	}
}

bool contact_is_skin(void)
{
	return skin_validated;
}
