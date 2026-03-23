/**
 * Door Entry System - ESP32 Door Lock Controller
 *
 * Hardware:
 *   - ESP32 (any variant)
 *   - Relay module on RELAY_PIN (controls electric door lock)
 *   - Optional: LED indicator on LED_PIN
 *
 * Libraries required (install via Arduino Library Manager):
 *   - PubSubClient by Nick O'Leary
 *   - ArduinoJson by Benoit Blanchon
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// ---- Configuration ----
#define WIFI_SSID       "YOUR_WIFI_SSID"
#define WIFI_PASSWORD   "YOUR_WIFI_PASSWORD"

#define MQTT_SERVER     "192.168.1.100"  // Raspberry Pi IP
#define MQTT_PORT       1883
#define MQTT_USERNAME   "esp32_lock"
#define MQTT_PASSWORD   "change_me_esp32_password"
#define MQTT_CLIENT_ID  "door_lock_esp32"

// GPIO Pins
#define RELAY_PIN       26   // Relay IN pin (active LOW typical)
#define LED_PIN         2    // Onboard LED

// Lock timing
#define LOCK_OPEN_DURATION_MS   5000  // Keep lock open for 5 seconds

// MQTT Topics
#define TOPIC_LOCK_COMMAND  "door/lock/command"
#define TOPIC_LOCK_STATUS   "door/lock/status"
#define TOPIC_NOTIFY_ACCESS "door/notify/access"

// ---- State ----
WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

bool lockOpen = false;
unsigned long lockOpenedAt = 0;

// ---- Setup ----
void setup() {
    Serial.begin(115200);
    Serial.println("Door Lock ESP32 starting...");

    pinMode(RELAY_PIN, OUTPUT);
    pinMode(LED_PIN, OUTPUT);
    setLockClosed();

    connectWiFi();
    mqttClient.setServer(MQTT_SERVER, MQTT_PORT);
    mqttClient.setCallback(onMqttMessage);
    mqttClient.setKeepAlive(60);
    connectMQTT();
}

// ---- Main Loop ----
void loop() {
    if (!mqttClient.connected()) {
        connectMQTT();
    }
    mqttClient.loop();

    // Auto-close lock after timeout
    if (lockOpen && (millis() - lockOpenedAt >= LOCK_OPEN_DURATION_MS)) {
        setLockClosed();
        publishStatus("LOCKED");
        Serial.println("Lock auto-closed after timeout");
    }
}

// ---- WiFi ----
void connectWiFi() {
    Serial.print("Connecting to WiFi: ");
    Serial.println(WIFI_SSID);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
        if (++attempts > 30) {
            Serial.println("\nWiFi failed, restarting...");
            ESP.restart();
        }
    }
    Serial.print("\nWiFi connected, IP: ");
    Serial.println(WiFi.localIP());
}

// ---- MQTT ----
void connectMQTT() {
    while (!mqttClient.connected()) {
        Serial.print("Connecting to MQTT...");
        if (mqttClient.connect(MQTT_CLIENT_ID, MQTT_USERNAME, MQTT_PASSWORD)) {
            Serial.println("connected");
            mqttClient.subscribe(TOPIC_LOCK_COMMAND, 1);
            publishStatus("LOCKED");
        } else {
            Serial.print("failed, rc=");
            Serial.print(mqttClient.state());
            Serial.println(". Retrying in 5s...");
            delay(5000);
        }
    }
}

void onMqttMessage(char* topic, byte* payload, unsigned int length) {
    String topicStr = String(topic);
    String message = "";
    for (unsigned int i = 0; i < length; i++) {
        message += (char)payload[i];
    }

    Serial.print("MQTT message on [");
    Serial.print(topicStr);
    Serial.print("]: ");
    Serial.println(message);

    if (topicStr == TOPIC_LOCK_COMMAND) {
        if (message == "OPEN") {
            openLock();
        } else if (message == "LOCK") {
            setLockClosed();
            publishStatus("LOCKED");
        }
    }
}

void publishStatus(const char* status) {
    mqttClient.publish(TOPIC_LOCK_STATUS, status, true);  // retained=true
    Serial.print("Published lock status: ");
    Serial.println(status);
}

// ---- Lock Control ----
void openLock() {
    if (lockOpen) return;  // Already open
    lockOpen = true;
    lockOpenedAt = millis();

    // Activate relay (active LOW = LOW opens lock)
    digitalWrite(RELAY_PIN, LOW);
    digitalWrite(LED_PIN, HIGH);

    publishStatus("OPEN");
    Serial.println("Lock OPENED");
}

void setLockClosed() {
    lockOpen = false;

    // Deactivate relay
    digitalWrite(RELAY_PIN, HIGH);
    digitalWrite(LED_PIN, LOW);

    Serial.println("Lock CLOSED");
}
