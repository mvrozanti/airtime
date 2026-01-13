# Cleanup Process Analysis

## Current Full Cleanup (from DEBUG_NOTES.md)
```bash
adb shell am force-stop com.nosmoke.timer  # Stops processes
adb shell pm clear com.nosmoke.timer       # Clears app data (wipes analytics!)
adb uninstall com.nosmoke.timer            # Uninstalls app
adb install app-debug.apk                  # Fresh install
```

## What Each Step Does

1. **`adb shell am force-stop`**: Force stops all processes of the app
   - Stops foreground service
   - Kills all app processes
   - **Impact on analytics**: None (data remains)
   - **Impact on updates**: Safe

2. **`adb shell pm clear`**: Clears all app data (SharedPreferences, DataStore, cache, etc.)
   - Wipes all stored data
   - Clears cached files
   - **Impact on analytics**: **WIPES ALL DATA** (including analytics)
   - **Impact on updates**: Not needed for normal updates

3. **`adb uninstall`**: Completely removes the app
   - Removes app and all data
   - Removes from system
   - **Impact on analytics**: Data is gone
   - **Impact on updates**: Not needed for normal updates (Android handles updates)

4. **`adb install`**: Fresh install
   - Installs app from scratch
   - **Impact on analytics**: None (fresh start)
   - **Impact on updates**: Use `adb install -r` for updates (reinstall without uninstall)

## Hypothesis: What's Causing the Double-Click Bug?

The bug appears to be related to **stale PendingIntent state** in Android's notification system. This could be:
- Cached PendingIntent in NotificationManager
- Stale BroadcastReceiver registration
- Notification action button state

## Potential Solutions (Without Clearing Data)

### Option 1: Try Without `pm clear`
For regular updates, try:
```bash
adb shell am force-stop com.nosmoke.timer
adb install -r app-debug.apk  # -r flag reinstalls without uninstalling
```

### Option 2: Change PendingIntent Request Code on Each Build
If the issue is cached PendingIntent, we could:
- Increment the request code in code (not practical)
- Use a timestamp-based or random request code (not recommended)
- Use `FLAG_MUTABLE` instead of `FLAG_IMMUTABLE` (security implications)

### Option 3: Cancel and Recreate Notification
The service could cancel the existing notification before creating a new one:
```kotlin
notificationManager.cancel(NOTIFICATION_ID)
notificationManager.notify(NOTIFICATION_ID, createNotification(...))
```

### Option 4: Test What's Actually Necessary
We could test each step individually:
1. Test with just force-stop + reinstall
2. Test with force-stop + clear + reinstall (without uninstall)
3. Test with force-stop + uninstall + reinstall (without clear)

## Recommended Approach for Updates

For **production updates** (not development testing):
- Use `adb install -r` (reinstall without clearing data)
- The double-click bug might only occur during development/testing
- Android's normal update mechanism should handle this correctly

For **development/testing**:
- **TESTING: Minimal cleanup (force-stop + reinstall)**
  ```bash
  adb shell am force-stop com.nosmoke.timer
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  - Preserves analytics data
  - Should work if stale state is just in running processes
  - **Status**: Testing if this is sufficient

- **Fallback: Full cleanup** (only if minimal doesn't work)
  - Use full cleanup to ensure clean state
  - But document that `pm clear` wipes analytics data
  - Consider if analytics can be preserved or exported before cleanup

