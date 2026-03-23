package com.doorentry.panel

import android.content.Context

class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("door_panel_config", Context.MODE_PRIVATE)

    var mqttHost: String
        get() = prefs.getString("mqtt_host", BuildConfig.MQTT_HOST)!!
        set(v) { prefs.edit().putString("mqtt_host", v).commit() }

    var mqttPort: Int
        get() = prefs.getInt("mqtt_port", BuildConfig.MQTT_PORT)
        set(v) { prefs.edit().putInt("mqtt_port", v).commit() }

    var mqttUsername: String
        get() = prefs.getString("mqtt_username", BuildConfig.MQTT_USERNAME)!!
        set(v) { prefs.edit().putString("mqtt_username", v).commit() }

    var mqttPassword: String
        get() = prefs.getString("mqtt_password", BuildConfig.MQTT_PASSWORD)!!
        set(v) { prefs.edit().putString("mqtt_password", v).commit() }

    var useTls: Boolean
        get() = prefs.getBoolean("mqtt_tls", false)
        set(v) { prefs.edit().putBoolean("mqtt_tls", v).commit() }

    val mqttServerUri: String
        get() {
            val protocol = if (useTls) "ssl" else "tcp"
            return "$protocol://$mqttHost:$mqttPort"
        }
}
