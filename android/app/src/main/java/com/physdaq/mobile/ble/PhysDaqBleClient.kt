package com.physdaq.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets

sealed class BleConnectionState {
    data object Disconnected : BleConnectionState()
    data class Connecting(val address: String) : BleConnectionState()
    data class Connected(val address: String) : BleConnectionState()
    data class Reconnecting(val address: String, val attempt: Int) : BleConnectionState()
}

fun BleConnectionState.label(): String = when (this) {
    BleConnectionState.Disconnected -> "Disconnected"
    is BleConnectionState.Connecting -> "Connecting to $address"
    is BleConnectionState.Connected -> "Connected to $address"
    is BleConnectionState.Reconnecting -> "Reconnecting to $address (attempt $attempt)"
}

/**
 * One PhysDAQ device's connection over BLE: connect, subscribe, and stay
 * connected. Scanning is [BleScanner]'s job; the app creates one of these per
 * device.
 *
 * Call the public functions from the main thread. GATT callbacks arrive on
 * binder threads, so anything that changes the connection is posted back to the
 * main thread before it touches state.
 *
 * Connection life cycle:
 *  - [connect] remembers the address as the one the user wants.
 *  - If the link drops, or an attempt fails or hangs, the client keeps retrying
 *    that address with [ReconnectPolicy] backoff until [disconnect] is called.
 *  - A node that went to sleep simply fails to answer until someone moves it;
 *    the retries pick it up when it starts advertising again.
 *  - Samples stop while the link is down and resume after. Nothing is
 *    back-filled, so a recording shows an honest gap.
 *
 * Every sample is stamped on arrival with SystemClock.elapsedRealtimeNanos(), the
 * same clock for every device in the app, which is what puts several devices on
 * one time axis. Permission checks happen at the entry points, hence the
 * class-level suppression.
 */
@SuppressLint("MissingPermission")
class PhysDaqBleClient(private val context: Context) {

    private val adapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null

    /** The device the user asked for. Null means "stay disconnected". */
    private var targetAddress: String? = null
    private var attempt = 0

    // The firmware chunks each line to (MTU - 3) bytes, so one notification is not
    // necessarily one line. Bytes accumulate here until a newline arrives.
    private val rxBuffer = StringBuilder()

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    /** The latest thing worth telling the user (a failure, mostly). Cleared on success. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _lines = MutableSharedFlow<DeviceLine>(extraBufferCapacity = 256)
    val lines: SharedFlow<DeviceLine> = _lines

    // ── Connecting ───────────────────────────────────────────────────────────

    fun connect(address: String) {
        if (!BlePermissions.hasAll(context)) {
            _notice.value = "Missing Bluetooth permission"
            return
        }
        mainHandler.removeCallbacks(reconnectRunnable)
        closeGatt()
        targetAddress = address
        attempt = 0
        _notice.value = null
        openGatt()
    }

    /** Stops for good: no further reconnect attempts until [connect] is called again. */
    fun disconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        targetAddress = null
        attempt = 0
        closeGatt()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun openGatt() {
        val address = targetAddress ?: return
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            _notice.value = "Cannot reach Bluetooth adapter or bad address $address"
            targetAddress = null
            _connectionState.value = BleConnectionState.Disconnected
            return
        }
        _connectionState.value =
            if (attempt == 0) BleConnectionState.Connecting(address)
            else BleConnectionState.Reconnecting(address, attempt)

        // If the link is not fully up (connected, services found, notifications
        // on) within this time, give up on the attempt and back off.
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        mainHandler.removeCallbacks(connectTimeout)
        gatt?.let {
            try {
                it.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed: $e")
            }
            it.close()
        }
        gatt = null
        synchronized(rxBuffer) { rxBuffer.clear() }
    }

    private fun scheduleReconnect() {
        val address = targetAddress ?: return
        attempt++
        _connectionState.value = BleConnectionState.Reconnecting(address, attempt)
        mainHandler.postDelayed(reconnectRunnable, ReconnectPolicy.delayMs(attempt))
    }

    private val reconnectRunnable = Runnable { openGatt() }

    private val connectTimeout = Runnable {
        _notice.value = "No response from the device; will retry"
        closeGatt()
        if (targetAddress != null) scheduleReconnect()
    }

    private fun setupFailed(g: BluetoothGatt, reason: String) {
        if (g !== gatt) return
        Log.w(TAG, reason)
        _notice.value = reason
        closeGatt()
        if (targetAddress != null) scheduleReconnect()
    }

    // ── GATT callbacks (binder threads) ──────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post { handleConnectionChange(g, status, newState) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { setupFailed(g, "Service discovery failed, status=$status") }
                return
            }
            val txChar = g.getService(NusUuids.SERVICE)?.getCharacteristic(NusUuids.TX_CHARACTERISTIC)
            val cccd = txChar?.getDescriptor(NusUuids.CLIENT_CHARACTERISTIC_CONFIG)
            if (txChar == null || cccd == null) {
                mainHandler.post { setupFailed(g, "NUS service or characteristic not found") }
                return
            }

            g.setCharacteristicNotification(txChar, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            // "Connected" is declared in onDescriptorWrite, once notifications are on.
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != NusUuids.CLIENT_CHARACTERISTIC_CONFIG) return
            mainHandler.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.removeCallbacks(connectTimeout)
                    attempt = 0
                    _notice.value = null
                    _connectionState.value = BleConnectionState.Connected(g.device.address)
                } else {
                    setupFailed(g, "Enabling notifications failed, status=$status")
                }
            }
        }

        // API 33+ signature.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NusUuids.TX_CHARACTERISTIC) onBytesReceived(value)
        }

        // Pre-API 33 signature.
        @Deprecated("Deprecated in Android API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != NusUuids.TX_CHARACTERISTIC) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onBytesReceived(value)
        }
    }

    private fun handleConnectionChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) {          // callback from an attempt we already abandoned
            g.close()
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            // 247 matches the firmware's L2CAP TX MTU (docs/firmware.md).
            g.requestMtu(247)
            return
        }
        // Disconnected, or a failed attempt (status 133 is the usual one).
        Log.i(TAG, "link down: status=$status newState=$newState")
        _notice.value = "Link lost (status $status)"
        closeGatt()
        if (targetAddress != null) scheduleReconnect()
        else _connectionState.value = BleConnectionState.Disconnected
    }

    private fun onBytesReceived(bytes: ByteArray) {
        val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(rxBuffer) {
            rxBuffer.append(String(bytes, StandardCharsets.US_ASCII))
            while (true) {
                val newline = rxBuffer.indexOf("\n")
                if (newline < 0) break
                val line = rxBuffer.substring(0, newline)
                rxBuffer.delete(0, newline + 1)
                if (line.isBlank()) continue
                _lines.tryEmit(DeviceLineParser.parse(line, receivedAtNanos))
            }
        }
    }

    private companion object {
        const val TAG = "PhysDaqBle"
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
