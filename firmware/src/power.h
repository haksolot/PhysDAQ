#ifndef POWER_H
#define POWER_H

#include <zephyr/drivers/sensor.h>

/* Call once after all sensors are initialised. */
void power_init(void);

/* Call every sample, after contact_update() for the same sample. Resets
 * the idle timer on gyro motion OR IR contact (contact.h) — worn-but-still
 * does not trigger sleep, only set-down-and-unworn does. When the
 * configured idle timeout expires, shuts down sensors and enters deep
 * sleep (system off, ~0.4 µA).  On motion the IMU INT1 fires and the nRF
 * reboots. */
void power_update(const struct sensor_value gyro[3]);

#endif /* POWER_H */
