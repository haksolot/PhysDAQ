#ifndef BLE_H
#define BLE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Start BLE stack, register NUS service, begin advertising.
 * Advertises by NUS service UUID so the PC script can discover by UUID.
 * Returns 0 on success, negative errno on failure. */
int ble_init(void);

/* Queue data for the connected BLE central (PC). Never blocks: copies the
 * line into a bounded queue drained by a dedicated TX thread, and drops it
 * when no central is subscribed or the queue is full. See the comment above
 * tx_thread() in ble.c for why the caller must never touch the radio. */
void ble_send(const uint8_t *data, size_t len);

/* Lines dropped by ble_send() because the TX queue was full — a growing
 * number is the "link is degrading" signal. */
uint32_t ble_tx_dropped(void);

/* True while a central (PC) holds a connection. Used by the power
 * manager: an active link means a live acquisition session, which must
 * hold off the idle deep-sleep timer. */
bool ble_is_connected(void);

/* Version of the host-facing identification protocol: the manufacturer-specific
 * AD payload and the ID line. Bump when either layout changes; a host that
 * reads a version it does not know falls back to "single-PPG node", which is
 * what everything was before this existed. */
#define PHYSDAQ_AD_PROTO_VERSION  0x02

/* Device class, advertised in the manufacturer-specific AD field and repeated
 * in the ID line the supervisor emits. A host that has only seen an
 * advertisement needs to know how many PPG channels to expect before it
 * connects; after connecting, the ID line is what actually decides. */
#define PHYSDAQ_DEV_TYPE_NODE  0x01
#define PHYSDAQ_DEV_TYPE_HUB   0x02

/* Bit flags in the AD payload and the ID line. */
#define PHYSDAQ_DEV_FLAG_SD    (1U << 0)   /* has removable storage */

/* Advertised name, e.g. "PhysDAQ-Hub-FDF9". Empty string before ble_init(). */
const char *ble_device_name(void);

/* Disconnect any active connection and stop advertising.
 * Must be called before entering deep sleep so the BLE controller
 * is idle when nrf_power_system_off() is invoked. */
void ble_stop(void);

#endif /* BLE_H */
