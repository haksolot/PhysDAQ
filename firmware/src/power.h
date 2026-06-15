#ifndef POWER_H
#define POWER_H

#include <zephyr/drivers/sensor.h>

/* Call once after all sensors are initialised. */
void power_init(void);

/* Call every sample. Resets idle timer on motion; when the configured
 * idle timeout expires, shuts down sensors and enters deep sleep (system
 * off, ~0.4 µA).  On motion the IMU INT1 fires and the nRF reboots. */
void power_update(const struct sensor_value gyro[3]);

#endif /* POWER_H */
