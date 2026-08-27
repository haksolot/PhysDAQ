#ifndef SELFTEST_H
#define SELFTEST_H

/*
 * Bus bring-up self-test — steps 1 to 3 of the hand-off report's checklist.
 *
 * Exists because a bare "-EIO during reset" from max30102_init() cannot tell
 * you whether the mux at 0x70 is missing, whether the sensor behind a channel
 * is missing, or whether the whole bus is dead. Those have completely
 * different fixes, and on a hand-wired prototype you need to know which one
 * you have before touching anything.
 *
 * Prints a verdict and never fails hard — a board that cannot self-test still
 * boots, so the rest of the log stays available.
 *
 * Safe to leave enabled in a recording build: it runs once at boot, before
 * acquisition starts, and leaves the mux with all channels deselected (0x00),
 * which is both the part's reset state and what the tca954x driver believes
 * after its own init. It probes with a one-byte read rather than a write, so
 * it cannot alter any device it finds.
 */
void selftest_run(void);

#endif /* SELFTEST_H */
