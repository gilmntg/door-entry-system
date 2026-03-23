package com.doorentry.household

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class DoorbellService : Service() {

    companion object {
        const val CHANNEL_MONITOR  = "door_monitor_channel"
        const val CHANNEL_BELL     = "door_bell_channel"
        const val NOTIF_ID_MONITOR = 1
        const val NOTIF_ID_BELL    = 2

        // Shared storage for the latest offer — CallActivity reads and clears this
        @Volatile var pendingOffer: String? = null
    }

    private lateinit var mqttManager: MqttManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_MONITOR, buildMonitorNotification())

        mqttManager = MqttManager(this)

        mqttManager.onBellRing = {
            handler.post { showDoorbellNotification() }
        }

        mqttManager.onWebRtcOffer = { offerJson ->
            // Cache offer — reject if empty (cleared) or too old
            if (offerJson.isNotEmpty()) {
                try {
                    val data = org.json.JSONObject(offerJson)
                    val ts = data.optLong("ts", 0L)
                    val age = System.currentTimeMillis() - ts
                    if (ts == 0L || age < 120_000) {
                        pendingOffer = offerJson
                    }
                } catch (e: Exception) { /* malformed JSON, ignore */ }
            } else {
                pendingOffer = null // panel cleared the offer
            }
        }

        mqttManager.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY  // restart automatically if killed

    override fun onDestroy() {
        super.onDestroy()
        try { mqttManager.disconnect() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

    private fun showDoorbellNotification() {
        val answerIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPending = PendingIntent.getActivity(
            this, 0, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val unlockIntent = Intent(this, UnlockActionReceiver::class.java).apply {
            action = "com.doorentry.household.ACTION_UNLOCK"
        }
        val unlockPending = PendingIntent.getBroadcast(
            this, 1, unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_BELL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Doorbell")
            .setContentText("Someone is at the door")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(answerPending, false)  // wake screen when locked; false = don't auto-launch when screen is on
            .setContentIntent(answerPending)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .addAction(android.R.drawable.ic_lock_idle_lock, "Unlock", unlockPending)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_BELL, notification)
    }

    private fun buildMonitorNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Door Monitor")
            .setContentText("Listening for doorbell…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Door Monitor",
                NotificationManager.IMPORTANCE_MIN).apply {
                description = "Persistent background service indicator"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BELL, "Doorbell Alerts",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when someone rings the doorbell"
            }
        )
    }
}
