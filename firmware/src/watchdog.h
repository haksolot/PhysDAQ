#ifndef MAID_WATCHDOG_H
#define MAID_WATCHDOG_H

/* Hardware watchdog: last-resort recovery if main() ever stops making
 * progress (e.g. an I2C transfer that blocks forever on a wedged bus).
 * watchdog_init() installs and starts the timer; watchdog_feed() must be
 * called from the main loop more often than the timeout, or the chip resets. */
int watchdog_init(void);
void watchdog_feed(void);

#endif /* MAID_WATCHDOG_H */
