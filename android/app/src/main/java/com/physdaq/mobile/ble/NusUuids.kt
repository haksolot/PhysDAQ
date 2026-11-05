package com.physdaq.mobile.ble

import java.util.UUID

/**
 * Nordic UART Service (NUS) identifiers used by PhysDAQ firmware.
 *
 * Per docs/firmware.md: discovery must always be by this service UUID,
 * never by advertised device name. The manufacturer-data field in the
 * advertisement (device type, PPG count, SD flag) is a scan-list hint
 * only — it is never used to decide whether to connect.
 */
object NusUuids {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Host -> device. Node firmware's RX handler is currently a no-op. */
    val RX_CHARACTERISTIC: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Device -> host, notify-only. Every ASCII line (ID, PPG/IMU, Battery, Power, ...) arrives here. */
    val TX_CHARACTERISTIC: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Standard Client Characteristic Configuration Descriptor — used to enable notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
