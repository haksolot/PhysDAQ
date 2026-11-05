package com.physdaq.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Finds PhysDAQ devices. One scanner is shared by the whole app; each device
 * then gets its own [PhysDaqBleClient] for the connection.
 *
 * Discovery is by service UUID only (docs/firmware.md). The manufacturer-data
 * hint is decoded just to label rows in the list.
 *
 * Main thread only. Permission checks happen at [start], hence the class-level
 * suppression.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val adapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices

    /** The latest thing worth telling the user. Cleared when a scan starts. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    fun start() {
        if (!BlePermissions.hasAll(context)) {
            _notice.value = "Missing Bluetooth permission"
            return
        }
        val s = adapter?.bluetoothLeScanner
        if (s == null) {
            _notice.value = "Bluetooth is off or unavailable"
            return
        }
        if (_scanning.value) return

        scanner = s
        _devices.value = emptyList()
        _notice.value = null

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NusUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _scanning.value = true
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
        s.startScan(listOf(filter), settings, callback)
    }

    fun stop() {
        mainHandler.removeCallbacks(timeout)
        if (_scanning.value) scanner?.stopScan(callback)
        _scanning.value = false
    }

    private val timeout = Runnable {
        stop()
        if (_devices.value.isEmpty()) {
            _notice.value = "No PhysDAQ device found in ${SCAN_TIMEOUT_MS / 1000} s. " +
                "Wake it (a node sleeps when still and unworn) and scan again."
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val item = ScannedDevice(
                address = result.device.address,
                name = record?.deviceName,
                rssi = result.rssi,
                info = AdvertisementInfo.parse(
                    record?.getManufacturerSpecificData(AdvertisementInfo.COMPANY_ID)
                )
            )
            _devices.update { list ->
                val i = list.indexOfFirst { it.address == item.address }
                if (i < 0) list + item else list.toMutableList().also { it[i] = item }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.removeCallbacks(timeout)
            _scanning.value = false
            _notice.value = "Scan failed, code=$errorCode"
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 20_000L
    }
}
