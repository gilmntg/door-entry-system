# Hardware Setup Guide

This document covers the physical wiring, firmware flashing, and Tasmota configuration for the door entry system hardware: the Sonoff SV v1.0 lock relay and the HC-SR501 PIR motion sensor.

---

## Components

| Component | Role |
|---|---|
| Sonoff SV v1.0 | Controls the door lock relay; runs Tasmota firmware |
| HC-SR501 PIR sensor | Detects approach; wired to GPIO14 on the Sonoff SV |

---

## Sonoff SV v1.0 — Overview

The Sonoff SV is a low-voltage relay board designed for safe 5–24V loads. It runs Tasmota firmware which provides MQTT control without any custom code.

**Board features:**
- ESP8266 microcontroller
- 1-channel relay (normally open / normally closed contacts)
- 4-pin serial header: `VCC (3.3V)`, `TX`, `RX`, `GND`
- GPIO14 is not broken out to a labeled pin header — it is accessible as a solder pad on the PCB (see Tasmota device page: `tasmota.github.io/docs/devices/Sonoff-SV/`)

---

## Tasmota Flashing — Overview

Tasmota must be flashed via the serial header before first use. The board does not support OTA flashing from factory firmware.

### What you need

- USB-to-serial adapter (3.3V logic level — do NOT use 5V)
- Jumper wires
- [Tasmota installer](https://tasmota.github.io/install/) (web-based, Chrome recommended) or `esptool.py`

### Serial header pinout (4-pin, left to right when relay is at top)

```
[ VCC 3.3V ] [ TX ] [ RX ] [ GND ]
```

Connect to your USB-serial adapter:

| Sonoff SV | USB-Serial Adapter |
|---|---|
| VCC 3.3V | 3.3V |
| TX | RX |
| RX | TX |
| GND | GND |

### Flash procedure

1. Hold the button on the Sonoff SV, connect USB power (or connect the serial adapter's 3.3V), then release — this puts the ESP8266 into bootloader mode.
2. Open [tasmota.github.io/install](https://tasmota.github.io/install/) in Chrome.
3. Select the correct serial port and click **Install Tasmota**.
4. Once flashing completes, the device reboots into Tasmota.
5. Connect to the `tasmota-XXXX` Wi-Fi AP broadcast by the device.
6. Enter your Wi-Fi SSID and password. The device will join your network.

---

## Tasmota Configuration

After joining the network, open the Tasmota web UI at the device's IP address (find it in your router's DHCP list or HA network scanner).

### 1. Configure Module

Navigate to **Configuration > Configure Module**:

- Module type: **Sonoff SV** (or Generic if not listed)
- GPIO14: set to `Switch (8)` — this maps the PIR output to Tasmota's Switch8 input

Save and allow the device to reboot.

### 2. MQTT Settings

Navigate to **Configuration > Configure MQTT**:

| Field | Value |
|---|---|
| Host | `192.168.68.61` (HA/Mosquitto IP) |
| Port | `1883` |
| Client | `door_lock` |
| User | your MQTT username |
| Password | your MQTT password |
| Topic | `door_lock` |
| Full Topic | `%prefix%/%topic%/` |

With this topic structure:
- HA sends lock commands to: `cmnd/door_lock/POWER`
- Tasmota publishes state to: `stat/door_lock/POWER`
- Tasmota publishes telemetry to: `tele/door_lock/SENSOR`

Save and reboot.

### 3. Switch Mode for PIR

Open the Tasmota **Console** (from the web UI) and enter:

```
SwitchMode8 1
```

This sets Switch8 (GPIO14) to follow mode — the switch state tracks the PIR output level directly (HIGH = detected, LOW = clear).

```
SetOption114 1
```

This keeps switch MQTT messages publishing even when the switch state does not change after a restart, ensuring motion events are always reported.

### 4. PIR Tasmota Rule

In the Tasmota Console, enter the following as a single line:

```
Rule1 ON Switch8#State=1 DO Publish tele/door_lock/SENSOR {"Switch1":"ON"} ENDON ON Switch8#State=0 DO Publish tele/door_lock/SENSOR {"Switch1":"OFF"} ENDON
```

Then enable the rule:

```
Rule1 1
```

Verify it is active:

```
Rule1
```

Expected response: `{"Rule1":{"State":"ON", ...}}`

**What this does:** When the PIR output goes high (motion detected), Tasmota publishes `{"Switch1":"ON"}` to `tele/door_lock/SENSOR`. Home Assistant's automation listens for this and relays a `detected` message to `door/panel/motion` to wake the panel screen.

---

## HC-SR501 PIR Sensor — Wiring

The HC-SR501 is a passive infrared motion sensor with a 3-pin interface.

### HC-SR501 Pinout

```
  +-------+
  |  VCC  |  -- 5V power
  |  OUT  |  -- digital output (HIGH on motion)
  |  GND  |  -- ground
  +-------+
  (viewed from front, pins at bottom)
```

### Wiring Diagram (ASCII)

```
HC-SR501                     Sonoff SV v1.0
---------                    --------------
  VCC  ---------------------- 5V (or external 5V supply)
  OUT  ---------------------- GPIO14 (solder pad, see note below)
  GND  ---------------------- GND
```

> **GPIO14 access note**: On the Sonoff SV v1.0, GPIO14 is not on the 4-pin serial header. It is accessible as a labelled solder pad on the PCB. Use a short length of wire-wrap wire soldered carefully to this pad. Refer to the board diagram at `tasmota.github.io/docs/devices/Sonoff-SV/` for the exact pad location.
>
> **Alternative**: If soldering to the Sonoff SV is not desirable, a separate Wemos D1 Mini running a minimal MQTT sketch can read the PIR and publish directly — avoiding any modification to the lock board.

### PIR Sensor Adjustment

The HC-SR501 has two potentiometers on the back:

| Potentiometer | Function | Recommended Setting |
|---|---|---|
| Sensitivity (left) | Detection range (3m–7m) | Turn to mid-point (~5m) |
| Time delay (right) | Output hold time (5s–300s) | Turn fully anticlockwise (minimum ~5s) |

Set the jumper between the two pots to **single trigger mode** (H position) so each detection event generates a single pulse rather than a continuous high.

---

## Door Lock Wiring

The Sonoff SV relay switches the lock mechanism power supply.

```
12V PSU (+) ---- [Sonoff SV COM] ---- [Sonoff SV NO] ---- Door Lock (+)
12V PSU (-) ------------------------------------------------ Door Lock (-)
```

- **COM**: common terminal (always connected)
- **NO**: normally open — circuit closes when relay fires (lock releases)
- **NC**: normally closed — not used in this configuration

When HA publishes `ON` to `cmnd/door_lock/POWER`, the relay closes, current flows through the lock, and the door releases. After 5 seconds, HA publishes `OFF` and the relay opens, re-engaging the lock.

> Ensure the 12V PSU is rated for the lock's current draw. Most electric strike locks draw 300–500 mA. The Sonoff SV relay is rated for 10A at low voltage — well within range.

---

## MQTT Topic Summary (Hardware)

| Topic | Direction | Payload | Description |
|---|---|---|---|
| `cmnd/door_lock/POWER` | HA → Sonoff SV | `ON` / `OFF` | Lock relay control |
| `stat/door_lock/POWER` | Sonoff SV → HA | `ON` / `OFF` | Relay state confirmation |
| `tele/door_lock/SENSOR` | Sonoff SV → HA | `{"Switch1":"ON/OFF"}` | PIR motion state (via Rule1) |
| `door/panel/motion` | HA → Panel app | `detected` | Motion relayed to wake panel screen |

---

## Verification

After completing the setup:

1. Open **MQTT Explorer** (or Developer Tools > MQTT in HA) and subscribe to `tele/door_lock/#`.
2. Wave your hand in front of the HC-SR501.
3. Confirm `{"Switch1":"ON"}` appears on `tele/door_lock/SENSOR`.
4. Confirm the door panel screen wakes (if the app is running and dimmed).
5. In HA Developer Tools > MQTT, publish `ON` to `cmnd/door_lock/POWER` and verify the relay clicks and the lock releases.
6. After 5 seconds, verify the auto-lock automation fires and publishes `OFF`.
