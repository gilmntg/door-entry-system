# OTA Updates via Home Assistant

Both Android apps check for updates on startup by fetching a version manifest from Home Assistant.

## How It Works

1. **Version Manifest**: `home-assistant/www/apps-version.json` contains current versions
2. **Apps fetch**: On startup, each app compares its `BuildConfig.VERSION_CODE` with the remote version
3. **If newer**: A dialog appears with download link (HTTP from HA)
4. **User taps**: Browser downloads and installs the new APK

## To Release an Update

### 1. Update version codes in app build.gradle.kts:
```kotlin
versionCode = 2  // increment from 1
versionName = "1.1"
```

### 2. Build APKs:
```bash
cd android/door-panel && ./gradlew assembleDebug
cd ../household && ./gradlew assembleDebug
```

### 3. Copy APKs to Home Assistant:
```bash
scp android/door-panel/app/build/outputs/apk/debug/app-debug.apk \
  root@192.168.68.61:/usr/share/hassio/homeassistant/www/door-panel-app-1.1.apk

scp android/household/app/build/outputs/apk/debug/app-debug.apk \
  root@192.168.68.61:/usr/share/hassio/homeassistant/www/door-household-app-1.1.apk
```

### 4. Update `home-assistant/www/apps-version.json`:
```json
{
  "panel": {
    "versionCode": 2,
    "versionName": "1.1",
    "downloadUrl": "http://192.168.68.61:8123/local/door-panel-app-1.1.apk",
    "releaseNotes": "Your release notes here"
  },
  "household": {
    "versionCode": 2,
    "versionName": "1.1",
    "downloadUrl": "http://192.168.68.61:8123/local/door-household-app-1.1.apk",
    "releaseNotes": "Your release notes here"
  }
}
```

### 5. Upload the JSON to HA:
```bash
scp home-assistant/www/apps-version.json \
  root@192.168.68.61:/usr/share/hassio/homeassistant/www/
```

### 6. Next time users start the app, they'll see an update notification

## Update Checker Implementation

- `android/door-panel/app/src/main/java/com/doorentry/panel/UpdateChecker.kt`
- `android/household/app/src/main/java/com/doorentry/household/UpdateChecker.kt`

Both apps:
1. Fetch `http://192.168.68.61:8123/local/apps-version.json` on startup
2. Parse JSON and compare `versionCode`
3. If remote > local, show a dialog with download link
4. User taps "Download" → browser opens APK → installs

Failures (network, JSON parse) are silent — no popup if update check fails.
