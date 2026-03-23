package com.doorentry.panel

import android.content.Context
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttManager(private val context: Context) {

    companion object {
        const val TOPIC_CODE_REQUEST     = "door/panel/code/request"
        const val TOPIC_FINGERPRINT_AUTH = "door/panel/fingerprint/auth"
        const val TOPIC_BELL             = "door/panel/bell"
        const val TOPIC_NOTIFY_ACCESS    = "door/notify/access"
        const val TOPIC_LOCK_STATUS      = "door/lock/status"
        const val TOPIC_WEBRTC_OFFER     = "door/webrtc/offer"
        const val TOPIC_WEBRTC_ANSWER    = "door/webrtc/answer"
        const val TOPIC_AUDIO_HOUSEHOLD  = "door/audio/household"
        const val TOPIC_AUDIO_PANEL      = "door/audio/panel"
        const val TOPIC_WEBRTC_HANGUP    = "door/webrtc/hangup"
        const val TOPIC_MOTION           = "door/panel/motion"
    }

    private val config = AppConfig(context)
    private var mqttClient: MqttAsyncClient? = null

    var onAccessResult: ((granted: Boolean) -> Unit)? = null
    var onLockStatus: ((status: String) -> Unit)? = null
    var onWebRtcAnswer: ((String) -> Unit)? = null
    var onAudio: ((ByteArray) -> Unit)? = null
    var onHangup: (() -> Unit)? = null
    var onMotion: (() -> Unit)? = null

    fun connect() {
        val clientId = "door_panel_${UUID.randomUUID().toString().take(8)}"

        mqttClient = MqttAsyncClient(config.mqttServerUri, clientId, MemoryPersistence())
        mqttClient!!.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                subscribe(TOPIC_NOTIFY_ACCESS)
                subscribe(TOPIC_LOCK_STATUS)
                subscribe(TOPIC_WEBRTC_ANSWER)
                subscribe(TOPIC_AUDIO_HOUSEHOLD)
                subscribe(TOPIC_WEBRTC_HANGUP)
                subscribe(TOPIC_MOTION)
            }
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                when (topic) {
                    TOPIC_AUDIO_HOUSEHOLD -> onAudio?.invoke(message.payload)
                    TOPIC_WEBRTC_HANGUP   -> onHangup?.invoke()
                    TOPIC_MOTION          -> onMotion?.invoke()
                    else                  -> handleMessage(topic, message.toString())
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

    fun publishAudio(pcm: ByteArray) {
        try {
            val msg = MqttMessage(pcm).apply { qos = 0; isRetained = false }
            mqttClient?.publish(TOPIC_AUDIO_PANEL, msg)
        } catch (e: Exception) { /* drop frame on error */ }
    }

    fun publishCode(code: String) = publish(TOPIC_CODE_REQUEST, code)
    fun publishFingerprintAuth() = publish(TOPIC_FINGERPRINT_AUTH, "verified")
    fun publishBell() = publish(TOPIC_BELL, "ring")

    /** Publishes the WebRTC offer (retained so household page gets it on subscribe). */
    fun publishVideoOffer(json: String) {
        try {
            val msg = MqttMessage(json.toByteArray()).apply { qos = 1; isRetained = true }
            mqttClient?.publish(TOPIC_WEBRTC_OFFER, msg)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Wipes the retained offer so stale offers don't confuse future sessions. */
    fun clearVideoOffer() {
        try {
            val msg = MqttMessage("".toByteArray()).apply { qos = 1; isRetained = true }
            mqttClient?.publish(TOPIC_WEBRTC_OFFER, msg)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun publish(topic: String, payload: String) {
        try {
            val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
            mqttClient?.publish(topic, msg)
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    private fun subscribe(topic: String) {
        mqttClient?.subscribe(topic, 1)
    }

    private fun handleMessage(topic: String, payload: String) {
        when (topic) {
            TOPIC_NOTIFY_ACCESS -> onAccessResult?.invoke(payload == "granted")
            TOPIC_LOCK_STATUS   -> onLockStatus?.invoke(payload)
            TOPIC_WEBRTC_ANSWER -> onWebRtcAnswer?.invoke(payload)
        }
    }
}
