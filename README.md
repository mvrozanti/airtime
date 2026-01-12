# NoSmoke Timer - Android Lock Screen Timer Controller

A minimal Android service app that provides a persistent smoke timer controller accessible from the lock screen, similar to KDE Connect.

## Architecture

- **ForegroundService**: Runs continuously, maintains persistent notification
- **StateManager**: Manages timer state using DataStore (lock status, end timestamp, increment)
- **AlarmManager**: Handles timer expiration robustly (works in doze mode)
- **BootReceiver**: Auto-starts service on device boot
- **No UI**: Service-driven architecture, no visible activities

## Timer Logic

- **Base lock duration**: 40 minutes
- **Increment system**: Each activation increases lock duration by +1 second
  - Activation 1: 40:01 (40 min + 1 sec)
  - Activation 2: 40:02 (40 min + 2 sec)
  - Activation 3: 40:03 (40 min + 3 sec)
  - And so on...
- **State indicators**:
  - 🚬 = Unlocked (timer can be activated)
  - 🌿 = Locked (waiting for timer)

## Building the APK

### Prerequisites

- Android Studio (latest stable version)
- JDK 17 or higher
- Android SDK with API level 26+ (Android 8.0+)
- Target SDK: 34 (Android 14)

### Build Steps

1. **Open the project**:
   ```bash
   cd /path/to/nosmoke
   # Open in Android Studio, or use command line:
   ```

2. **Build debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

3. **Build release APK** (recommended for production):
   ```bash
   ./gradlew assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`

   **Note**: Release builds are code-signed. For sideloading, you may need to sign with your own keystore or use the debug keystore.

4. **Alternative: Build unsigned release** (for testing):
   Edit `app/build.gradle` and add inside `android` block:
   ```gradle
   android {
       ...
       signingConfigs {
           debug {
               storeFile file('debug.keystore')
           }
       }
   }
   ```

## Installation (Sideloading)

### Enable Developer Options & USB Debugging

1. Go to **Settings** → **About phone**
2. Tap **Build number** 7 times to enable Developer options
3. Go to **Settings** → **Developer options**
4. Enable **USB debugging**
5. Enable **Install via USB** (if available)

### Install via ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or for release build:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Install via File Manager

1. Transfer APK to device (via USB, email, cloud storage, etc.)
2. Open file manager on device
3. Navigate to APK location
4. Tap the APK file
5. Allow installation from unknown sources if prompted
6. Tap **Install**

## Samsung Galaxy M62 Specific Configuration

The app uses the same foreground service type as KDE Connect (`connectedDevice`), which should work reliably on Samsung devices without requiring battery optimization changes. However, if you experience issues with the service being stopped, you can optionally configure the following:

### Optional: Battery Optimization (if needed)

If the service stops unexpectedly, you can exclude the app from battery optimization:

1. **Settings** → **Battery** → **Background app limits** (or **App power management**)
2. Find **NoSmoke Timer** in the list
3. Set to **Unrestricted** or **Never sleeping**

**Alternative method**:
1. **Settings** → **Apps** → **NoSmoke Timer**
2. **Battery** → **Unrestricted** (or **Allow background activity**)

### Optional: Disable Sleep/Doze (if needed)

1. **Settings** → **Device care** → **Battery** → **Background limits**
2. Find **NoSmoke Timer**
3. Remove from sleeping apps / Add to never sleeping apps

### Allow Auto-start (Recommended for Boot Receiver)

1. **Settings** → **Apps** → **NoSmoke Timer**
2. **Battery** → **Allow background activity**
3. Look for **Auto-start** option and enable it

**Note**: Some Samsung devices have a separate "Auto-start" menu in Settings → Battery.

### Notification Permissions (Android 13+)

The app will request notification permission on first launch. If denied:

1. **Settings** → **Apps** → **NoSmoke Timer** → **Notifications**
2. Enable **Allow notifications**
3. Ensure lock screen notifications are enabled

### Lock Screen Notification Visibility

1. **Settings** → **Lock screen** → **Notifications**
2. Ensure notifications are visible on lock screen
3. **Settings** → **Apps** → **NoSmoke Timer** → **Notifications**
4. Ensure **Lock screen** notifications are enabled

## Usage

1. **First launch**: App requests permissions and starts the service automatically
2. **Notification**: A persistent notification appears in the notification shade
3. **Lock screen**: Notification is visible on lock screen (if permissions granted)
4. **Activate timer**: Tap **Lock** button in notification (when unlocked)
5. **Check time**: Tap **Show Time** button to refresh remaining time display
6. **Auto-unlock**: Timer automatically unlocks when duration expires

## Notification Behavior

- **Unlocked state** (🚬):
  - Title: "🚬 Timer Unlocked"
  - Text: "Tap to lock timer"
  - Action: **Lock** button

- **Locked state** (🌿):
  - Title: "🌿 Timer Locked"
  - Text: "Remaining: Xm Ys" (or "Xs" if < 1 minute)
  - Action: **Show Time** button (refreshes display)

## Technical Details

### Permissions Required

- `POST_NOTIFICATIONS` (Android 13+): For persistent notification
- `FOREGROUND_SERVICE`: Required for foreground service
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`: For connected device foreground service (same as KDE Connect)
- `RECEIVE_BOOT_COMPLETED`: Auto-start on boot
- `WAKE_LOCK`: Keep CPU awake for timer operations
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`: For precise timer expiration

### Service Type

Uses `FOREGROUND_SERVICE_CONNECTED_DEVICE` type (same as KDE Connect). This is a standard foreground service type that works reliably on Samsung devices without requiring battery optimization changes.

### State Persistence

- Uses **DataStore Preferences** (modern replacement for SharedPreferences)
- Stores: `is_locked` (boolean), `lock_end_timestamp` (long), `increment_seconds` (long)
- Location: `/data/data/com.nosmoke.timer/datastore/smoke_timer_state.preferences_pb`

### Timer Implementation

- Uses **AlarmManager.setExactAndAllowWhileIdle()** (Android 6.0+) for robust expiration handling
- Works in doze mode and deep sleep
- AlarmReceiver unlocks timer when expiration alarm fires

### Boot Auto-start

- BootReceiver listens for `BOOT_COMPLETED` and `QUICKBOOT_POWERON` (Samsung-specific)
- Automatically starts SmokeTimerService on device boot

## Troubleshooting

### Service stops running

1. The app should work without battery optimization changes (uses same service type as KDE Connect)
2. If issues persist, try disabling battery optimization (see above)
3. Ensure app is not in "Sleeping apps" list
4. Check if device has aggressive battery saver enabled
5. Verify auto-start is enabled (Samsung devices)

### Notification not visible on lock screen

1. Check notification permissions (Settings → Apps → NoSmoke Timer → Notifications)
2. Verify lock screen notification settings (Settings → Lock screen → Notifications)
3. Ensure notification channel is not blocked

### Timer doesn't expire / unlock

1. Check AlarmManager permissions (should be automatic on Android 12+)
2. Check if device has aggressive doze mode enabled
3. If issues persist, try disabling battery optimization (see above)
4. Restart the app/service

### App crashes on launch

1. Check Android version (minimum: Android 8.0 / API 26)
2. Verify all permissions are granted
3. Check logcat for error messages: `adb logcat | grep -i nosmoke`

## Development Notes

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Language**: Kotlin
- **Architecture**: Service-driven, no UI components
- **State Management**: DataStore Preferences
- **Background Tasks**: AlarmManager (not WorkManager) for precise timing

## License

This is a personal utility app. Use at your own discretion.

## Support

For issues specific to Samsung Galaxy M62:
- Ensure all Samsung-specific battery management settings are configured
- Check Samsung's "Device care" app for additional restrictions
- Some Samsung devices have "Adaptive battery" - consider disabling for this app

