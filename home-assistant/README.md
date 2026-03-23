# Home Assistant Configuration

Home Assistant is the brain of the door entry system. It runs on a Raspberry Pi alongside the Mosquitto MQTT broker add-on. All PIN validation, lockout logic, door lock control, PIR relay, and PIN management are handled by HA automations defined in a single deployable YAML package.

---

## Prerequisites

- Home Assistant running on Raspberry Pi (any recent version)
- **Mosquitto broker** HA add-on installed and running
  - TCP port **1883** must be accessible on the LAN (Android apps connect here)
  - WebSocket port **1884** must be mapped in the Docker/add-on config (used for browser-based clients if needed)
- HA mobile app installed on household phones (for push notifications)
- SSH access to the Raspberry Pi: `ssh root@192.168.68.61 -p 22222`

---

## Installation

### 1. Copy the package file

Transfer `home-assistant/packages/door_entry.yaml` to `/config/packages/` on the Raspberry Pi:

```bash
scp -P 22222 home-assistant/packages/door_entry.yaml root@192.168.68.61:/config/packages/door_entry.yaml
```

Or use the HA File Editor add-on to paste the contents manually.

### 2. Enable packages in configuration.yaml

Edit `/config/configuration.yaml` and add (or merge into an existing `homeassistant:` block):

```yaml
homeassistant:
  packages:
    door_entry: !include packages/door_entry.yaml
```

If a `packages:` key already exists, add the `door_entry` line inside it.

### 3. Restart Home Assistant

In the HA UI: **Settings > System > Restart**, or via SSH:

```bash
ha core restart
```

### 4. Verify helpers were created

Navigate to **Settings > Devices & Services > Helpers** and confirm the following exist:

| Helper | Type | Description |
|---|---|---|
| `input_text.door_pin_admin` | Input Text | Admin PIN code |
| `input_text.door_pin_resident_1` | Input Text | Resident 1 PIN code |
| `input_text.door_pin_resident_2` | Input Text | Resident 2 PIN code |
| `input_number.door_failed_attempts` | Input Number | Failed attempt counter (0–10) |
| `input_boolean.door_lockout_active` | Input Boolean | Lockout flag |

If any are missing, check the HA logs for YAML errors in `door_entry.yaml`.

### 5. Set initial PIN values

In the HA UI, go to **Settings > Devices & Services > Helpers**, open each `input_text.door_pin_*` helper, and set your desired PIN values. These are stored as plain text in HA.

---

## Mosquitto WebSocket Port

The Mosquitto add-on must have port **1884** mapped as a WebSocket listener. In the Mosquitto add-on configuration (`/config/mosquitto/mosquitto.conf` or via the add-on UI), ensure:

```
listener 1883
protocol mqtt

listener 1884
protocol websockets
```

The Android apps use TCP port 1883. Port 1884 (WebSocket) is required if any browser-based MQTT client is used (e.g. the legacy `door-call.html`).

> Do not use port 9001 — it is not exposed by the HA Docker configuration.

---

## Automations Included

The `door_entry.yaml` package contains all of the following automations:

### PIN Validation

- **Trigger**: message received on `door/panel/code/request`
- **Logic**:
  1. If `door_lockout_active` is `true`, publish `lockout` to `door/notify/access` and stop.
  2. Compare `trigger.payload | trim` against `input_text.door_pin_admin`, `door_pin_resident_1`, `door_pin_resident_2`.
  3. If a match is found: reset `door_failed_attempts` to 0, publish `granted` to `door/notify/access`, send `ON` to `cmnd/door_lock/POWER`.
  4. If no match: increment `door_failed_attempts`. If count reaches 5, set `door_lockout_active` to `true` and publish `lockout`; otherwise publish `denied`.

### Fingerprint Authentication

- **Trigger**: `verified` received on `door/panel/fingerprint/auth`
- **Logic**: Same as a successful PIN — reset failed attempts, publish `granted`, unlock door.

### Auto-Lock After Unlock

- **Trigger**: `door/lock/status` (or lock command confirmation) — 5 seconds after unlock
- **Logic**: Publishes `OFF` to `cmnd/door_lock/POWER` to re-engage the lock relay.

### Lockout Reset

- **Trigger**: Manual toggle of `input_boolean.door_lockout_active` to `off` (via HA UI)
- **Logic**: Resets `input_number.door_failed_attempts` to 0.

### PIR Motion Relay

- **Trigger**: `tele/door_lock/SENSOR` with payload containing `"Switch1":"ON"`
- **Logic**: Publishes `detected` to `door/panel/motion` so the door panel app wakes the screen.

### Household Unlock

- **Trigger**: `OPEN` received on `door/household/unlock`
- **Logic**: Publishes `ON` to `cmnd/door_lock/POWER` (same unlock path as PIN/fingerprint). Auto-lock fires after 5 seconds.

### Doorbell Notification

- **Trigger**: `ring` received on `door/panel/bell`
- **Logic**: Sends a push notification via the HA mobile app service to all household devices. Notification title: "Door Bell", with an **Answer** action button that opens the household call screen.

### PIN Management

- **Trigger**: New PIN received on `door/admin/pin/admin` (and equivalent topics for resident PINs)
- **Logic**: Updates the corresponding `input_text` helper. Re-publishes the new PIN value as a retained message on `door/config/pin/admin` (etc.) so household apps see the updated value immediately.

---

## File Reference

| File | Purpose |
|---|---|
| `packages/door_entry.yaml` | Single deployable package — all helpers + all automations. Drop into `/config/packages/`. |
| `automations/doorbell.yaml` | Reference copy of the doorbell automation only (for editing convenience — not deployed separately). |
| `automations/pin_validation.yaml` | Reference copy of PIN validation automation. |
| `www/` | Legacy directory — `door-call.html` is no longer used (replaced by household Android app). |

---

## Updating

To push a configuration change:

1. Edit `home-assistant/packages/door_entry.yaml` locally.
2. Copy to the Pi:
   ```bash
   scp -P 22222 home-assistant/packages/door_entry.yaml root@192.168.68.61:/config/packages/door_entry.yaml
   ```
3. Reload automations without a full restart (if only automations changed):
   **Developer Tools > YAML > Reload Automations**

   Or restart HA fully for helper/package changes.

---

## Troubleshooting

**Helpers not created after restart**
- Check HA logs for YAML parse errors: **Settings > System > Logs**
- Validate YAML syntax locally before uploading

**PIN validation not triggering**
- Use MQTT Explorer or Developer Tools > MQTT to publish a test message to `door/panel/code/request`
- Verify the automation trigger matches the exact topic

**Lockout not clearing**
- Toggle `input_boolean.door_lockout_active` off manually in **Settings > Devices & Services > Helpers**
- The lockout reset automation will clear the failed attempts counter

**Door lock not responding**
- Check that the Sonoff SV is online and subscribed to `cmnd/door_lock/POWER`
- Verify Tasmota MQTT settings match the Mosquitto broker credentials (see [docs/hardware.md](../docs/hardware.md))
