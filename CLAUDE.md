Project: Smart Door Intercom
Architecture:

Brain: Home Assistant (Raspberry Pi).

Broker: Mosquitto (HA Add-on).

Database: HA Helpers (Input Text/Numbers) for PIN codes.

UI on door panel phone: Mobile Phone app (Android/iOS) acting as the keypad and video terminal.
UI on home inhibitants: To begin with we will use HA app, later on we may move to a full blown android app

Hardware: ESP32 running arduino mqtt client for the door lock relay.

Protocols: MQTT for signaling/state; WebRTC for video/audio.