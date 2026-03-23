package com.doorentry.household

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Handles the "Unlock" action from the doorbell notification without opening the app.
 * Creates a short-lived MQTT connection, publishes OPEN, then disconnects.
 */
class UnlockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.doorentry.household.ACTION_UNLOCK") return

        val config = AppConfig(context)
        val clientId = "hh_unlock_${UUID.randomUUID().toString().take(6)}"

        try {
            val client = MqttAsyncClient(config.mqttServerUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                userName = config.mqttUsername
                password = config.mqttPassword.toCharArray()
                isCleanSession = true
                connectionTimeout = 5
                keepAliveInterval = 10
            }

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) {
                    try {
                        val msg = MqttMessage("OPEN".toByteArray()).apply { qos = 1 }
                        client.publish(MqttManager.TOPIC_LOCK_COMMAND, msg)
                    } catch (e: Exception) { e.printStackTrace() }

                    // Disconnect after a short delay to let the publish complete
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { client.disconnect() } catch (e: Exception) {}
                    }, 2000)
                }
                override fun onFailure(token: IMqttToken?, exception: Throwable?) {}
            })
        } catch (e: Exception) { e.printStackTrace() }

        // Dismiss the doorbell notification
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(DoorbellService.NOTIF_ID_BELL)
    }
}
