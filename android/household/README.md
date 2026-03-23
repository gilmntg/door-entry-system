# Household App

Android application for residents. Receives doorbell notifications with an Answer button, handles two-way video and audio calls with the door panel, allows remote door unlock, and provides a PIN management screen to view and update entry codes.

---

## What It Does

- Receives a push notification (with an Answer button) when the doorbell is pressed
- Opens a full-screen call screen showing live video from the door panel
- Provides bidirectional audio during the call (microphone + speaker)
- Includes a remote unlock button on the call screen
- Allows residents to view and update Admin, Resident 1, and Resident 2 PINs
- Auto-starts on device boot

---

## Prerequisites

- Android phone running Android 8.0 (API 26) or higher
- Microphone and speaker
- Wi-Fi connection on the same network as the MQTT broker
- MQTT broker (Mosquitto) running and accessible on port 1883 (TCP)
- Home Assistant configured with the `door_entry` package
- Firebase project configured (for push notifications via FCM)

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
cd android/household
./gradlew assembleDebug
```

The APK will be output to:
```
android/household/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install via ADB

```bash
adb install android/household/app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK file to each household phone and install manually (see Distribution below).

---

## First-Time Setup

After installing and launching the app:

1. **Open Settings** — tap the settings icon or navigate to Settings from the main screen.
2. **Enter MQTT details**:
   - Host (e.g. `192.168.68.61`)
   - Port (default: `1883`)
   - Username and password
3. Tap **Save**. The app connects to the broker and subscribes to relevant topics.
4. **Grant notification permission** — Android 13+ will prompt; grant it so doorbell alerts are delivered.
5. **Enable auto-start on boot** — toggle the option in the app's Settings screen if desired.

---

## Features

### Doorbell Notification

- When the door panel publishes `ring` to `door/panel/bell`, Home Assistant sends a push notification to all registered household phones
- The notification is styled as a call notification (`CATEGORY_CALL`) with a custom sound
- An **Answer** button is shown directly on the notification — tapping it opens the call screen without needing to unlock the phone first
- Dismissing the notification without answering does not affect the door panel (the video offer remains available)

### Video and Audio Call Screen

The call screen (`CallActivity`) opens when the resident answers or taps the notification:

- **Video** — receives the WebRTC SDP offer from `door/webrtc/offer`, creates an answer, and publishes it to `door/webrtc/answer`. The panel's front camera stream appears full-screen.
- **Audio (household → panel)** — `CallActivity` captures the microphone using native `AudioRecord` (16 kHz / 16-bit mono PCM) and publishes 640-byte chunks at QoS 0 to `door/audio/household`.
- **Audio (panel → household)** — subscribes to `door/audio/panel` and plays incoming PCM chunks via `AudioTrack` (speaker output). A `LinkedBlockingQueue` and dedicated thread ensure the MQTT callback is never blocked.
- **Unlock button** — publishes `OPEN` to `door/household/unlock`. Home Assistant receives this, validates the request, and sends `ON` to `cmnd/door_lock/POWER` on the Sonoff SV.
- **Hangup button** — publishes `hangup` to `door/webrtc/hangup` and closes the call screen. The panel receives the hangup signal and closes its call screen simultaneously.
- If the remote side hangs up first, the call screen closes automatically on receipt of the hangup message.

### PIN Management Screen

- Accessible from the main screen via a **Manage PINs** button
- Displays the current values of Admin, Resident 1, and Resident 2 PINs
- PINs are read from retained MQTT messages published by HA on:
  - `door/config/pin/admin`
  - `door/config/pin/resident1`
  - `door/config/pin/resident2`
- Each PIN field has a **show/hide toggle** (eye icon) — PINs are masked by default
- To update a PIN, edit the field and tap **Save** next to it
- The new PIN is published to `door/admin/pin/admin` (or the relevant topic); a Home Assistant automation updates the corresponding `input_text` helper and re-publishes the retained read-back message

### Auto-Start on Boot

- A `BootReceiver` listens for `ACTION_BOOT_COMPLETED` and starts the app's main activity
- This can be toggled on/off from the app's Settings screen
- Useful so residents do not need to manually re-open the app after a phone reboot

---

## MQTT Topics Used

| Topic | Direction | Payload |
|---|---|---|
| `door/panel/bell` | Subscribe | `ring` |
| `door/notify/access` | Subscribe | `granted` / `denied` / `lockout` |
| `door/household/unlock` | Publish | `OPEN` |
| `door/webrtc/offer` | Subscribe | SDP JSON (retained) |
| `door/webrtc/answer` | Publish | SDP JSON |
| `door/webrtc/hangup` | Publish + Subscribe | `hangup` |
| `door/audio/household` | Publish | Raw PCM bytes |
| `door/audio/panel` | Subscribe | Raw PCM bytes |
| `door/config/pin/admin` | Subscribe | PIN value (retained) |
| `door/config/pin/resident1` | Subscribe | PIN value (retained) |
| `door/config/pin/resident2` | Subscribe | PIN value (retained) |
| `door/admin/pin/admin` | Publish | New PIN string |

---

## Permissions Required

- `INTERNET` — MQTT and WebRTC
- `RECORD_AUDIO` — microphone during calls
- `POST_NOTIFICATIONS` — doorbell push notification (Android 13+)
- `RECEIVE_BOOT_COMPLETED` — auto-start on boot
- `USE_FULL_SCREEN_INTENT` — full-screen call notification on locked screen

---

## Distributing to Multiple Phones

### Bootstrap (initial install)

The simplest approach for a small household is to share the debug APK directly:

1. Build the APK with `./gradlew assembleDebug`
2. Transfer `app-debug.apk` to each phone — ADB, WhatsApp file share, or a USB drive all work
3. Enable **Install unknown apps** in Android Settings for the app you used to receive the file
4. Install and complete first-time setup on each device

### Over-the-Air Updates (future)

Firebase App Distribution is the planned mechanism for pushing updates to household phones without requiring physical access:

1. Create a Firebase project and add the household app
2. Add testers (household phone Google accounts) to the distribution group
3. Upload new APKs to Firebase App Distribution after each build
4. Testers receive an email with a download link; the Firebase App Distribution app handles installation

> Firebase App Distribution is not yet implemented in this version.

---

## Troubleshooting

**No notification when doorbell is pressed**
- Confirm notification permission is granted (Android Settings > Apps > Household > Notifications)
- Check that HA is sending the push notification (verify in HA logs)
- Ensure the phone is connected to the same network as the broker

**Call screen opens but no video**
- Check that the panel app is running and published an offer to `door/webrtc/offer`
- Verify the retained offer message exists using an MQTT client (e.g. MQTT Explorer)
- Ensure both devices are on the same Wi-Fi network (ICE uses LAN host candidates only)

**No audio during call**
- Grant `RECORD_AUDIO` permission in Android Settings > Apps > Household
- Check that `door/audio/household` messages appear in the broker during a call

**Unlock button does nothing**
- Verify HA has the unlock automation enabled
- Check that the Sonoff SV is online and responding to `cmnd/door_lock/POWER`

**PINs not showing in PIN management screen**
- Confirm HA is publishing retained messages on the `door/config/pin/*` topics
- These are populated when the HA package is first loaded and updated each time a PIN changes
