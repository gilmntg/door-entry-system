# Door Panel App

Android kiosk application mounted at the door. Provides a full-screen PIN keypad, fingerprint authentication, doorbell button, and two-way video/audio calls with household residents. Designed to run on a dedicated Android phone that never leaves the door.

---

## What It Does

- Displays a full-screen numeric keypad for PIN entry
- Supports fingerprint authentication via Android BiometricPrompt
- Provides a doorbell button that notifies residents and initiates a video call
- Dims the screen to black after 30 seconds of inactivity; wakes on PIR motion detection
- Conducts two-way WebRTC video calls with the household app
- Streams bidirectional audio over MQTT PCM (bypasses Android WebView mic limitations)
- Runs as a kiosk: back button is blocked, app auto-starts on device boot

---

## Prerequisites

- Android phone running Android 8.0 (API 26) or higher
- Front-facing camera (for video calls)
- Microphone
- Wi-Fi connection on the same network as the MQTT broker
- MQTT broker (Mosquitto) running and accessible on port 1883 (TCP)
- Home Assistant configured with the `door_entry` package

---

## Build Instructions

### 1. Clone the repository

```bash
git clone <repo-url>
cd "Door Entry System"
```

### 2. Create secrets.properties

Copy the example file and fill in your MQTT credentials:

```bash
cp android/secrets.properties.example android/secrets.properties
```

Edit `android/secrets.properties`:

```properties
MQTT_HOST=192.168.68.61
MQTT_PORT=1883
MQTT_USERNAME=your_mqtt_username
MQTT_PASSWORD=your_mqtt_password
```

> `secrets.properties` is gitignored and must never be committed.

### 3. Build the APK

```bash
cd android/door-panel
./gradlew assembleDebug
```

The APK will be output to:
```
android/door-panel/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install via ADB

Connect the phone via USB with USB debugging enabled:

```bash
adb install android/door-panel/app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to the device and install manually.

---

## First-Time Setup

After installing and launching the app:

1. **Open Settings** — long-press the status text in the top area of the screen. A Settings dialog will appear.
2. **Enter MQTT details**:
   - Host (e.g. `192.168.68.61`)
   - Port (default: `1883`)
   - Username and password
3. Tap **Save**. The app will connect to the broker immediately.
4. The connection status indicator will turn green when connected.

Settings are stored in SharedPreferences and persist across restarts.

---

## Features

### PIN Keypad

- Full-screen numeric keypad, digits 0–9 plus backspace and submit
- Entered digits are shown as masked dots
- On submit, the PIN is published as a raw string to `door/panel/code/request`
- Home Assistant validates the PIN and responds on `door/notify/access`:
  - `granted` — green flash, door unlocks
  - `denied` — red flash, attempt counted
  - `lockout` — red flash, lockout screen shown (further attempts blocked)

### Fingerprint Authentication

- Fingerprint button triggers Android `BiometricPrompt`
- On successful biometric, publishes `verified` to `door/panel/fingerprint/auth`
- HA treats this the same as a correct PIN — unlocks the door immediately
- Face recognition is **not supported** in the current version

### Doorbell Button

- Bell icon button on the main screen
- Publishes `ring` to `door/panel/bell`
- HA sends a push notification to household phones
- The panel transitions to the call screen (`VideoCallActivity`) immediately

### WebRTC Video Call

- `VideoCallActivity` opens a WebView loading `call.html` (bundled in assets)
- `call.html` captures the front camera and creates an SDP offer
- The offer is passed to native Kotlin via `Android.publishOffer()` (JavaScript bridge) to avoid Chrome Private Network Access restrictions on WebSocket connections from `localhost` to a LAN IP
- The offer is published as a **retained** MQTT message on `door/webrtc/offer`
- When the household app answers, the SDP answer is delivered to the WebView via `evaluateJavascript`
- ICE uses direct LAN host candidates — no STUN server needed
- On hangup (local or remote), a `hangup` signal is published to `door/webrtc/hangup` and the call screen closes
- On exit, an empty retained message is published to `door/webrtc/offer` to clear the stale offer

### Audio (MQTT PCM Streaming)

WebView's internal `AudioRecord` fails on some Samsung devices because the wake-word engine holds the microphone. Audio is therefore handled entirely in native Kotlin:

- `VideoCallActivity` uses `AudioRecord` to capture 16 kHz / 16-bit mono PCM
- Audio is published in 640-byte (20 ms) chunks at QoS 0 on `door/audio/panel`
- Incoming household audio is received from `door/audio/household` and played via `AudioTrack` (speaker output)
- A `LinkedBlockingQueue` and dedicated playback thread prevent the MQTT callback from blocking

### Screen Wake via PIR Motion

- The screen dims to black after 30 seconds of no interaction
- When the HC-SR501 PIR sensor detects motion, the Sonoff SV Tasmota rule fires, HA receives the event, and publishes `detected` to `door/panel/motion`
- The app subscribes to this topic and wakes the screen immediately
- This allows the panel to appear blank when unattended while still responding when someone approaches

### Kiosk Mode

- The back button and recent-apps gesture are intercepted and blocked
- A `BootReceiver` broadcast receiver launches the app automatically when the device boots
- The app requests to be displayed over the lock screen so it is visible without unlocking

---

## MQTT Topics Used

| Topic | Direction | Payload |
|---|---|---|
| `door/panel/bell` | Publish | `ring` |
| `door/panel/code/request` | Publish | Raw PIN string |
| `door/panel/fingerprint/auth` | Publish | `verified` |
| `door/notify/access` | Subscribe | `granted` / `denied` / `lockout` |
| `door/panel/motion` | Subscribe | `detected` |
| `door/webrtc/offer` | Publish | SDP JSON (retained) |
| `door/webrtc/answer` | Subscribe | SDP JSON |
| `door/webrtc/hangup` | Publish + Subscribe | `hangup` |
| `door/audio/panel` | Publish | Raw PCM bytes |
| `door/audio/household` | Subscribe | Raw PCM bytes |

---

## Permissions Required

- `INTERNET` — MQTT and WebRTC
- `CAMERA` — video call
- `RECORD_AUDIO` — native PCM audio capture
- `USE_BIOMETRIC` / `USE_FINGERPRINT` — fingerprint auth
- `RECEIVE_BOOT_COMPLETED` — auto-start on boot
- `SYSTEM_ALERT_WINDOW` — display over lock screen

---

## Troubleshooting

**App cannot connect to MQTT broker**
- Verify host and port in Settings (long-press status text)
- Confirm port 1883 is open and Mosquitto is running
- Check that the phone is on the same Wi-Fi network as the broker

**Fingerprint button does nothing**
- Ensure at least one fingerprint is enrolled in Android Settings > Security
- Grant biometric permission if prompted

**No video in call**
- Check that camera permission is granted
- Ensure `call.html` is present in `app/src/main/assets/`

**No audio during call**
- Grant `RECORD_AUDIO` permission in Android Settings > Apps
- Check that `door/audio/panel` messages are reaching the broker (use an MQTT client to monitor)

**Screen does not wake on approach**
- Verify PIR wiring and Tasmota rule (see [docs/hardware.md](../../docs/hardware.md))
- Check that HA is publishing `detected` to `door/panel/motion` when PIR fires
