package com.physdaq.mobile.ble

enum class DeviceKind { NODE, HUB, UNKNOWN }

/**
 * The hint PhysDAQ firmware puts in its advertisement: a manufacturer-specific
 * field under company ID 0xFFFF carrying four bytes (docs/firmware.md):
 *
 *     [0] AD protocol version
 *     [1] device type: 0x01 node, 0x02 hub
 *     [2] PPG sensor count
 *     [3] flags, bit 0 = has an SD card
 *
 * This is only for labelling rows in the scan list. Discovery is by service
 * UUID, and once connected the `ID` line is what decides what the device is.
 */
data class AdvertisementInfo(
    val protocol: Int,
    val kind: DeviceKind,
    val ppgCount: Int,
    val hasSd: Boolean
) {
    companion object {
        const val COMPANY_ID = 0xFFFF

        /** [payload] is the field's data without the two company-ID bytes. */
        fun parse(payload: ByteArray?): AdvertisementInfo? {
            if (payload == null || payload.size < 4) return null
            val kind = when (payload[1].toInt() and 0xFF) {
                0x01 -> DeviceKind.NODE
                0x02 -> DeviceKind.HUB
                else -> DeviceKind.UNKNOWN
            }
            return AdvertisementInfo(
                protocol = payload[0].toInt() and 0xFF,
                kind = kind,
                ppgCount = payload[2].toInt() and 0xFF,
                hasSd = (payload[3].toInt() and 0x01) != 0
            )
        }
    }
}

/** One row of the scan list. */
data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val info: AdvertisementInfo?
)
