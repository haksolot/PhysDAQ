#ifndef POWER_H
#define POWER_H

#include <stdbool.h>

#include <zephyr/drivers/sensor.h>

/* Call once after all sensors are initialised. */
void power_init(void);

/* Call every sample, after contact_update() for the same sample. Resets
 * the idle timer on gyro motion, IR contact (contact.h), or an active BLE
 * link (ble.h) — sleep only triggers on a node that is unworn, still, and
 * not connected to a host. When the configured idle timeout expires,
 * shuts down sensors and enters deep sleep (system off, ~0.4 µA).  On
 * motion the IMU INT1 fires and the nRF reboots. */
void power_update(const struct sensor_value gyro[3]);

/* True while a host holds the USB CDC console open (DTR asserted). One of the
 * "do not sleep" signals, and the USB-side rising edge main.c uses to re-send
 * the identity line. */
bool power_usb_host_open(void);

#endif /* POWER_H */
