package com.doorentry.household

import android.content.Context
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttManager(private val context: Context) {

    companion object {
        const val TOPIC_BELL          = "door/panel/bell"
        const val TOPIC_WEBRTC_OFFER  = "door/webrtc/offer"
        const val TOPIC_WEBRTC_ANSWER = "door/webrtc/answer"
        const val TOPIC_LOCK_COMMAND  = "door/lock/command"
        const val TOPIC_HOUSEHOLD_UNLOCK = "door/household/unlock"
        const val TOPIC_NOTIFY_ACCESS = "door/notify/access"
        const val TOPIC_AUDIO         = "door/audio/household"
        const val TOPIC_AUDIO_PANEL   = "door/audio/panel"
        const val TOPIC_PIN_UPDATE    = "door/admin/pin"    // /<slot> appended at publish
        const val TOPIC_PIN_CONFIG    = "door/config/pin"   // /<slot> appended on subscribe
        const val TOPIC_WEBRTC_HANGUP = "door/webrtc/hangup"
    }

    private val config = AppConfig(context)
    private var mqttClient: MqttAsyncClient? = null

    var onBellRing: (() -> Unit)? = null
    var onWebRtcOffer: ((String) -> Unit)? = null
    var onAccessResult: ((String) -> Unit)? = null
    var onAudio: ((ByteArray) -> Unit)? = null
    var onPinValue: ((slot: String, pin: String) -> Unit)? = null

    fun connect() {
        val clientId = "household_${UUID.randomUUID().toString().take(8)}"
        mqttClient = MqttAsyncClient(config.mqttServerUri, clientId, MemoryPersistence())
        mqttClient!!.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                subscribe(TOPIC_BELL)
                subscribe(TOPIC_WEBRTC_OFFER)
                subscribe(TOPIC_NOTIFY_ACCESS)
                subscribe(TOPIC_AUDIO_PANEL)
                subscribe("$TOPIC_PIN_CONFIG/+")
            }
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                when {
                    topic == TOPIC_AUDIO_PANEL -> onAudio?.invoke(message.payload)
                    topic.startsWith("$TOPIC_PIN_CONFIG/") -> {
                        val slot = topic.removePrefix("$TOPIC_PIN_CONFIG/")
                        onPinValue?.invoke(slot, message.toString())
                    }
                    else -> handleMessage(topic, message.toString())
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            userName = config.mqttUsername
            password = config.mqttPassword.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 60
        }

        mqttClient?.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {}
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {}
        })
    }

    fun disconnect() = mqttClient?.disconnect()

    fun publishAnswer(json: String) {
        try {
            val msg = MqttMessage(json.toByteArray()).apply { qos = 1; isRetained = false }
            mqttClient?.publish(TOPIC_WEBRTC_ANSWER, msg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun publishHangup() {
        try {
            val msg = MqttMessage("hangup".toByteArray()).apply { qos = 0; isRetained = false }
            mqttClient?.publish(TOPIC_WEBRTC_HANGUP, msg)
        } catch (e: Exception) { /* best-effort */ }
    }

    fun publishPinUpdate(slot: String, pin: String) {
        try {
            val msg = MqttMessage(pin.toByteArray()).apply { qos = 1; isRetained = false }
            mqttClient?.publish("$TOPIC_PIN_UPDATE/$slot", msg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun publishAudio(pcm: ByteArray) {
        try {
            val msg = MqttMessage(pcm).apply { qos = 0; isRetained = false }
            mqttClient?.publish(TOPIC_AUDIO, msg)
        } catch (e: Exception) { /* drop frame on error */ }
    }

    fun publishUnlock() {
        try {
            val msg = MqttMessage("OPEN".toByteArray()).apply { qos = 1 }
            mqttClient?.publish(TOPIC_HOUSEHOLD_UNLOCK, msg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun subscribe(topic: String) {
        mqttClient?.subscribe(topic, 1)
    }

    private fun handleMessage(topic: String, payload: String) {
        when (topic) {
            TOPIC_BELL         -> onBellRing?.invoke()
            TOPIC_WEBRTC_OFFER -> onWebRtcOffer?.invoke(payload)
            TOPIC_NOTIFY_ACCESS -> onAccessResult?.invoke(payload)
        }
    }
}
