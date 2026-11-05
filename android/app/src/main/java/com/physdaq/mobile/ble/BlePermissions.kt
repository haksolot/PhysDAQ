package com.physdaq.mobile.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PhysDAQ mobile is BLE-only (see project scope decisions) — no USB/serial
 * permissions are declared or requested anywhere in this app.
 */
object BlePermissions {

    /** Permissions to declare in AndroidManifest.xml and request at runtime. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION // required pre-API 31 to receive BLE scan results
            )
        }

    /**
     * What to ask for at runtime. Android 13+ also needs the notification
     * permission, or the foreground service's notification is hidden. It is
     * deliberately NOT part of [required]/[hasAll]: acquisition works without it.
     */
    fun requestList(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required() + Manifest.permission.POST_NOTIFICATIONS
        } else {
            required()
        }

    fun hasAll(context: Context): Boolean =
        required().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
