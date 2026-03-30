# Smart Door Entry System

A self-hosted smart door intercom system built on Home Assistant, MQTT, and WebRTC. A kiosk Android app at the door handles PIN entry, fingerprint authentication, and video calls. Resident phones receive doorbell notifications and can answer calls, view video, speak back, and remotely unlock the door — all over a local network with no cloud dependency.

---

## Architecture

```
[Door Panel Phone]          [Household Phones]
  Android App (Kotlin)        Android App (Kotlin)
       |                            |
       +----------MQTT--------------+
                    |
          [Raspberry Pi]
          Home Assistant
          Mosquitto Broker
                    |
          [Sonoff SV v1.0]
           Tasmota Firmware
           Door Lock Relay
                    |
           [HC-SR501 PIR]
           GPIO14 on Sonoff SV
```

**Communication paths:**
- Door panel publishes bell, PIN, fingerprint events via MQTT to Home Assistant
- Home Assistant validates, responds, and sends commands to the Sonoff SV lock relay
- WebRTC (video) + MQTT PCM streaming (audio) handle the live call between panel and household phones
- Home Assistant relays PIR motion events from the Sonoff SV back to the door panel to wake the screen

---

## Features

### Door Panel
- Full-screen PIN keypad with lockout protection (5 failed attempts)
- Fingerprint authentication via Android BiometricPrompt
- Doorbell button with visual/audio feedback
- Automatic screen dim after 30 seconds; PIR sensor wakes screen when someone approaches
- WebRTC video call initiated automatically on doorbell press
- Bidirectional audio via MQTT PCM streaming (bypasses WebView mic limitations)
- Kiosk mode: back button blocked, auto-starts on device boot

### Household App
- Push notification on doorbell press with an Answer button
- Full-screen call screen: live video from door panel, microphone, remote unlock button, hangup
- PIN management screen: view and update Admin, Resident 1, Resident 2 PINs
- Boots automatically with the device

### Home Assistant
- PIN validation against HA `input_text` helpers
- Failed-attempt counter with lockout (`input_boolean.door_lockout_active`)
- Auto-unlock relay command after successful auth (door relocks after 5 seconds)
- PIR motion detection relayed from Sonoff SV to the panel app
- PIN update automation: household app can push new PINs to HA helpers
- All logic in a single deployable YAML package

---

## Hardware Requirements

| Component | Purpose |
|---|---|
| Android phone (panel) | Door-mounted kiosk device |
| Android phone(s) (household) | Resident devices |
| Raspberry Pi (any model with HA) | Runs Home Assistant + Mosquitto |
| Sonoff SV v1.0 (Tasmota) | Door lock relay control |
| HC-SR501 PIR sensor | Proximity screen wake |
| 12V power supply | Powers door lock mechanism |

---

## Quick Start

1. **Home Assistant** — deploy the HA package and configure Mosquitto. See [home-assistant/README.md](home-assistant/README.md).
2. **Hardware** — wire up the Sonoff SV and HC-SR501 PIR sensor. See [docs/hardware.md](docs/hardware.md).
3. **Door Panel App** — build and install the kiosk app on the panel phone. See [android/door-panel/README.md](android/door-panel/README.md).
4. **Household App** — build and install the resident app on household phones. See [android/household/README.md](android/household/README.md).

---

## Project Structure

```
Door Entry System/
├── android/
│   ├── door-panel/                 # Kiosk app — keypad, fingerprint, bell, WebRTC
│   ├── household/                  # Resident app — notifications, call, unlock, PINs
│   └── secrets.properties.example # MQTT credentials template
├── home-assistant/
│   ├── packages/door_entry.yaml   # Single deployable HA package
│   ├── automations/               # Reference copies of individual automations
│   └── www/                       # apps-version.json + APK files for OTA updates
├── esp32/door-lock/               # Legacy Arduino sketch (replaced by Tasmota)
└── docs/
    ├── setup-guide.md
    └── hardware.md
```

---

## MQTT Topics

| Topic | Payload | Direction | Description |
|---|---|---|---|
| `door/panel/bell` | `ring` | Panel → HA | Doorbell button pressed |
| `door/panel/code/request` | e.g. `1234` | Panel → HA | PIN submission (raw string) |
| `door/panel/fingerprint/auth` | `verified` | Panel → HA | Fingerprint authenticated |
| `door/notify/access` | `granted` / `denied` / `lockout` | HA → Panel | Auth result |
| `door/panel/motion` | `detected` | HA → Panel | PIR motion relayed by HA |
| `door/household/unlock` | `OPEN` | Household → HA | Remote unlock request |
| `door/webrtc/offer` | SDP JSON (retained) | Panel → Household | WebRTC offer |
| `door/webrtc/answer` | SDP JSON | Household → Panel | WebRTC answer |
| `door/webrtc/hangup` | `hangup` | Either side | End call |
| `door/audio/household` | Raw PCM bytes | Household → Panel | Household mic audio |
| `door/audio/panel` | Raw PCM bytes | Panel → Household | Panel mic audio |
| `door/config/pin/admin` | PIN value (retained) | HA → Household | Admin PIN read-back |
| `door/config/pin/resident1` | PIN value (retained) | HA → Household | Resident 1 PIN read-back |
| `door/config/pin/resident2` | PIN value (retained) | HA → Household | Resident 2 PIN read-back |
| `door/admin/pin/admin` | New PIN | Household → HA | Admin PIN update |
| `cmnd/door_lock/POWER` | `ON` / `OFF` | HA → Sonoff SV | Lock relay command (Tasmota) |
| `tele/door_lock/SENSOR` | `{"Switch1":"ON/OFF"}` | Sonoff SV → HA | PIR state from Tasmota rule |

---

## Video and Audio Architecture

### Video — WebRTC

Video is handled by a standard WebRTC peer connection:

1. On doorbell press, the door panel app opens a `VideoCallActivity` containing a WebView that loads `call.html`.
2. `call.html` calls `getUserMedia` to capture the front camera, creates an RTCPeerConnection, and generates an SDP offer.
3. The offer is passed to native Kotlin via a `Android.publishOffer()` JavaScript bridge, which publishes it as a **retained** MQTT message on `door/webrtc/offer`.
4. The household app receives the offer, creates an RTCPeerConnection in answer mode, and publishes the SDP answer to `door/webrtc/answer`.
5. The panel WebView receives the answer via `evaluateJavascript("window.onWebRtcAnswer(...)")` and the peer connection completes.
6. ICE uses LAN-only host candidates — no STUN server is required since both devices are on the same Wi-Fi network.
7. On hangup, the panel publishes an empty retained message to `door/webrtc/offer` to clear the stale offer.

The JavaScript bridge (`Android.publishOffer()`) is used because Chrome's Private Network Access policy blocks WebSocket connections from a `localhost` WebView to a LAN IP. Publishing via native MQTT bypasses this restriction.

### Audio — MQTT PCM Streaming

WebView's internal `AudioRecord` fails on some Android devices (notably Samsung) because a wake-word engine holds the microphone. To work around this, audio is handled entirely in native Kotlin, bypassing WebRTC audio:

- **Panel mic → Household**: `VideoCallActivity` uses `AudioRecord` to capture 16 kHz / 16-bit mono PCM in 640-byte (20 ms) chunks and publishes them at QoS 0 on `door/audio/panel`.
- **Household mic → Panel**: `CallActivity` does the same, publishing on `door/audio/household`.
- Each side subscribes to the other's topic and plays received chunks via `AudioTrack` (routed to speaker via `USAGE_MEDIA`).
- Playback uses a `LinkedBlockingQueue` with a dedicated thread so the MQTT callback thread is never blocked.

This gives low-latency, full-duplex audio without relying on WebRTC's audio pipeline.

---

## Deployment Notes

- Mosquitto must expose **port 1883** (TCP for Android native MQTT) and **port 1884** (WebSocket — not used in current implementation but mapped in HA Docker).
- The HA package (`door_entry.yaml`) must be dropped into `/config/packages/` and referenced from `configuration.yaml`. See [home-assistant/README.md](home-assistant/README.md).
- SSH into HA: `ssh root@192.168.68.61`
- All logic is local. No cloud services, no external APIs.

---

## Releasing a New App Version (OTA)

Both Android apps support over-the-air updates. When a newer version is available on the HA server, the app shows an update dialog on startup with a download link.

### Steps

**1. Bump version numbers** in both `build.gradle.kts` files:

```kotlin
// android/door-panel/app/build.gradle.kts
versionCode = 3        // increment by 1
versionName = "1.2"    // human-readable label

// android/household/app/build.gradle.kts
versionCode = 3
versionName = "1.2"
```

**2. Build both APKs:**

```powershell
$env:JAVA_HOME = "C:\android-studio\jbr"
cd "c:\My Projects\Claude code projects\Door Entry System"

cd android\door-panel;  .\gradlew assembleDebug; cd ..\..
cd android\household;   .\gradlew assembleDebug; cd ..\..
```

**3. Copy APKs to HA** (`/config/www/` on the Raspberry Pi):

```powershell
$ver = "1.2"
scp android\door-panel\app\build\outputs\apk\debug\app-debug.apk `
    root@192.168.68.61:/config/www/door-panel-app-$ver.apk

scp android\household\app\build\outputs\apk\debug\app-debug.apk `
    root@192.168.68.61:/config/www/door-household-app-$ver.apk
```

**4. Update `home-assistant/www/apps-version.json`:**

```json
{
  "panel": {
    "versionCode": 3,
    "versionName": "1.2",
    "downloadUrl": "http://192.168.68.61:8123/local/door-panel-app-1.2.apk",
    "releaseNotes": "Brief description of what changed"
  },
  "household": {
    "versionCode": 3,
    "versionName": "1.2",
    "downloadUrl": "http://192.168.68.61:8123/local/door-household-app-1.2.apk",
    "releaseNotes": "Brief description of what changed"
  }
}
```

**5. Copy the updated JSON to HA:**

```powershell
scp home-assistant\www\apps-version.json `
    root@192.168.68.61:/config/www/apps-version.json
```

**6. Install directly on connected phones via ADB** (optional — or let users get the OTA prompt):

```powershell
adb install -r android\door-panel\app\build\outputs\apk\debug\app-debug.apk
```

**7. Commit and push to GitHub.**

> The door-panel is an anonymous kiosk phone — install via ADB cable.
> Household phones pick up the update automatically next time they open the app.
