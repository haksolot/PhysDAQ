#ifndef PHYSDAQ_WATCHDOG_H
#define PHYSDAQ_WATCHDOG_H

/* Hardware watchdog: last-resort recovery if main() ever stops making
 * progress (e.g. an I2C transfer that blocks forever on a wedged bus).
 * watchdog_init() installs and starts the timer; watchdog_feed() must be
 * called from the main loop more often than the timeout, or the chip resets. */
int watchdog_init(void);
void watchdog_feed(void);

/* Name the step main() is about to perform. Costs one pointer store; read by
 * the starvation monitor to say *where* main hung before the WDT resets. The
 * string must be a literal (it is kept by pointer, not copied). */
void watchdog_set_stage(const char *s);

#endif /* PHYSDAQ_WATCHDOG_H */
