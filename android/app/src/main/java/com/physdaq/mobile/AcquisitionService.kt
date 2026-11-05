package com.physdaq.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps acquisition alive when the screen is off or the app is in the background.
 *
 * It does no BLE or recording work itself. All of that lives in
 * [AcquisitionRuntime]. This service exists so Android treats the process as
 * doing visible, user-started work (a "connected device" foreground service) and
 * does not kill it, and so there is a notification with a Stop button.
 *
 * Started by the screen when the user connects. Stopped by the Disconnect button
 * or the notification's Stop action.
 */
class AcquisitionService : Service() {

    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AcquisitionRuntime.init(applicationContext)
        createChannel()
        observer = AcquisitionRuntime.scope.launch {
            AcquisitionRuntime.summary().distinctUntilChanged().collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AcquisitionRuntime.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // If Android kills the process, the connection and recording are gone with it;
        // restarting an empty service would only show a notification for nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "PhysDAQ acquisition", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AcquisitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PhysDAQ")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    @SuppressLint("MissingPermission")  // without the notification permission this is silently ignored
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private companion object {
        const val CHANNEL_ID = "physdaq_acquisition"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.physdaq.mobile.action.STOP"
    }
}
