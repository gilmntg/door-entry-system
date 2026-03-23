import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val secrets = Properties().apply {
    val f = rootProject.file("../secrets.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.doorentry.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.doorentry.panel"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "MQTT_HOST",     "\"${secrets["MQTT_HOST"]     ?: "192.168.1.100"}\"")
        buildConfigField("int",    "MQTT_PORT",     "${secrets["MQTT_PORT"]       ?: 1883}")
        buildConfigField("String", "MQTT_USERNAME", "\"${secrets["MQTT_USERNAME"] ?: "door_panel"}\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"${secrets["MQTT_PASSWORD"] ?: ""}\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.paho.mqtt.client)
}
