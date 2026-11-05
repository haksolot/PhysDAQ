package com.physdaq.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementInfoTest {

    @Test
    fun nodeAdvertisement() {
        val info = AdvertisementInfo.parse(byteArrayOf(1, 0x01, 1, 0))!!
        assertEquals(DeviceKind.NODE, info.kind)
        assertEquals(1, info.ppgCount)
        assertFalse(info.hasSd)
        assertEquals(1, info.protocol)
    }

    @Test
    fun hubAdvertisementWithSdCard() {
        val info = AdvertisementInfo.parse(byteArrayOf(1, 0x02, 2, 1))!!
        assertEquals(DeviceKind.HUB, info.kind)
        assertEquals(2, info.ppgCount)
        assertTrue(info.hasSd)
    }

    @Test
    fun onlyBitZeroOfTheFlagsMeansSd() {
        assertFalse(AdvertisementInfo.parse(byteArrayOf(1, 0x02, 2, 0b10))!!.hasSd)
        assertTrue(AdvertisementInfo.parse(byteArrayOf(1, 0x02, 2, 0b11))!!.hasSd)
    }

    @Test
    fun unknownDeviceTypeIsLabelledUnknown() {
        assertEquals(DeviceKind.UNKNOWN, AdvertisementInfo.parse(byteArrayOf(1, 0x07, 1, 0))!!.kind)
    }

    @Test
    fun bytesAboveSevenFAreReadAsUnsigned() {
        val info = AdvertisementInfo.parse(byteArrayOf(0x80.toByte(), 0x01, 1, 0))!!
        assertEquals(128, info.protocol)
    }

    @Test
    fun missingOrShortPayloadGivesNull() {
        assertNull(AdvertisementInfo.parse(null))
        assertNull(AdvertisementInfo.parse(byteArrayOf(1, 1, 1)))
    }
}
