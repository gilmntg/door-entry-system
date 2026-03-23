# Door Entry System - Setup Guide

## Architecture Overview

```
[Door Panel Phone] ──MQTT──► [Home Assistant (Raspberry Pi)]
                                        │
                              ┌─────────┴─────────┐
                         Mosquitto            HA Package
                         (HA Add-on)          (helpers + automations
                                               all in one file)
                                        │
                              ┌─────────┴─────────┐
                         [ESP32 Lock]         [HA App on phones]
                         (relay control)      (resident UI)
```

---

## 1. Home Assistant Setup (Raspberry Pi)

### 1.1 Install Home Assistant OS
Follow: https://www.home-assistant.io/installation/raspberrypi

### 1.2 Install Add-ons
**Settings → Add-ons → Add-on Store**, install:
- **Mosquitto broker** — MQTT broker
- **Studio Code Server** (recommended) — edit config files from browser

### 1.3 Configure Mosquitto
In the Mosquitto add-on → **Configuration** tab:

```yaml
logins:
  - username: door_panel
    password: your_panel_password
  - username: esp32_lock
    password: your_esp32_password
require_certificate: false
```

Click **Save**, then **Restart** the add-on.

### 1.4 Enable MQTT Integration in HA
**Settings → Devices & Services → Add Integration → MQTT**
- Broker: `localhost`
- Port: `1883`
- Username: *(leave blank — broker handles auth per-client)*

### 1.5 Deploy the Door Entry Package

**SSH into your Pi** (or use the Terminal add-on):

```bash
mkdir -p /config/packages
cp door_entry.yaml /config/packages/door_entry.yaml
```

Then add to `/config/configuration.yaml`:

```yaml
homeassistant:
  packages:
    door_entry: !include packages/door_entry.yaml
```

**Restart Home Assistant** — all helpers and automations are created automatically.

### 1.6 Set PIN Codes
**Settings → Helpers**, find the Door PIN helpers, click each and set your PINs.

> PINs are stored as `mode: password` — they show as `****` in the UI.

### 1.7 Verify Automations
**Settings → Automations** — you should see 6 automations prefixed with "Door -".

---

## 2. ESP32 Setup

### 2.1 Required Libraries (Arduino IDE)
Install via **Tools → Manage Libraries**:
- `PubSubClient` by Nick O'Leary

### 2.2 Configuration
Edit `esp32/door-lock/door-lock.ino`:

```cpp
#define WIFI_SSID      "YOUR_WIFI_SSID"
#define WIFI_PASSWORD  "YOUR_WIFI_PASSWORD"
#define MQTT_SERVER    "192.168.1.x"   // Raspberry Pi IP
#define MQTT_PASSWORD  "your_esp32_password"
```

Flash to ESP32 via **Sketch → Upload**.

### 2.3 Wiring

```
ESP32 GPIO 26 ──► Relay IN
ESP32 GND     ──► Relay GND
ESP32 3.3V    ──► Relay VCC
Relay COM     ──► Lock power supply +
Relay NO      ──► Lock +
```

> The relay is **active LOW** — GPIO 26 LOW = lock open, HIGH = lock closed.

### 2.4 Verify
Open Arduino Serial Monitor at 115200 baud. You should see:
```
WiFi connected, IP: 192.168.1.x
Connecting to MQTT...connected
Published lock status: LOCKED
```

---

## 3. Door Panel App

### 3.1 Build
Open `android/door-panel/` in Android Studio → **Build → Build APK** → install on door phone.

### 3.2 Configure MQTT
Edit defaults in `AppConfig.kt` before building, or expose a settings screen:

| Setting | Value |
|---|---|
| MQTT Host | Raspberry Pi IP |
| MQTT Port | `1883` |
| MQTT Username | `door_panel` |
| MQTT Password | (as set in Mosquitto) |

### 3.3 Kiosk Mode
**Settings → Security → Screen Pinning** — pin the door panel app so it can't be exited.

---

## 4. Household Phones

1. Install the **Home Assistant** app from Play Store / App Store
2. Log in with a HA user account
3. Enable push notifications in the app

Residents will receive notifications for:
- **Doorbell** (with **Unlock** action button that remotely opens the door)
- Lockout alerts

---

## 5. MQTT Topic Reference

| Topic                     | Direction       | Payload                      | Description           |
|---------------------------|-----------------|------------------------------|-----------------------|
| `door/panel/bell`         | Panel → HA      | `ring`                       | Doorbell pressed      |
| `door/panel/code/request` | Panel → HA      | `1234` (raw string)          | PIN code entered      |
| `door/panel/fingerprint/auth` | Panel → HA  | `verified`                   | Fingerprint verified  |
| `door/lock/command`       | HA → ESP32      | `OPEN` / `LOCK`              | Lock control          |
| `door/lock/status`        | ESP32 → HA      | `OPEN` / `LOCKED`            | Lock state (retained) |
| `door/notify/access`      | HA → Panel      | `granted`/`denied`/`lockout` | Auth result           |

---

## 6. Testing Checklist

- [ ] ESP32 connects to MQTT and publishes `LOCKED` status on boot
- [ ] Publish `OPEN` to `door/lock/command` manually → relay clicks, LED lights
- [ ] Enter PIN on door panel → `granted` received → lock opens → auto-locks after 5s
- [ ] Enter wrong PIN 5× → lockout notification received on household phone
- [ ] Press bell → notification with Unlock button appears on household phone
- [ ] Tap Unlock in notification → lock opens
- [ ] Fingerprint on door panel → lock opens
