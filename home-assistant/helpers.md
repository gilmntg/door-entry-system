# Required HA Helpers

Create these in **Settings → Helpers → Create Helper** before loading automations.

## Input Text (PIN storage)

| Entity ID                        | Type       | Notes                  |
|----------------------------------|------------|------------------------|
| `input_text.door_pin_admin`      | Input Text | Admin PIN              |
| `input_text.door_pin_resident_1` | Input Text | Resident 1 PIN         |
| `input_text.door_pin_resident_2` | Input Text | Resident 2 PIN (opt.)  |

> Set values via the Helper UI — never expose them in automations or logs.

## Input Number (lockout counter)

| Entity ID                          | Type         | Min | Max | Step |
|------------------------------------|--------------|-----|-----|------|
| `input_number.door_failed_attempts` | Input Number | 0   | 10  | 1    |

## Input Boolean (lockout state)

| Entity ID                       | Type          |
|---------------------------------|---------------|
| `input_boolean.door_lockout_active` | Toggle    |
