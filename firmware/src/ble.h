#ifndef BLE_H
#define BLE_H

#include <stddef.h>
#include <stdint.h>

/* Start BLE stack, register NUS service, begin advertising.
 * Advertises by NUS service UUID so the PC script can discover by UUID.
 * Returns 0 on success, negative errno on failure. */
int ble_init(void);

/* Send data to the connected BLE central (PC).
 * No-op when no central is connected or TX queue is full. */
void ble_send(const uint8_t *data, size_t len);

/* Disconnect any active connection and stop advertising.
 * Must be called before entering deep sleep so the BLE controller
 * is idle when nrf_power_system_off() is invoked. */
void ble_stop(void);

#endif /* BLE_H */
