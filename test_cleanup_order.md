# Testing Cleanup Order

## Current Order (from DEBUG_NOTES Attempt 7 - marked as SUCCESS)
1. `adb shell am force-stop com.nosmoke.timer`
2. `adb shell pm clear com.nosmoke.timer`
3. `adb uninstall com.nosmoke.timer`
4. `adb install app-debug.apk`

## Alternative Orders to Test

### Order A: Uninstall first, then install (no clear)
1. `adb shell am force-stop com.nosmoke.timer`
2. `adb uninstall com.nosmoke.timer`
3. `adb install app-debug.apk`

### Order B: Clear, uninstall, then install (no force-stop)
1. `adb shell pm clear com.nosmoke.timer`
2. `adb uninstall com.nosmoke.timer`
3. `adb install app-debug.apk`

### Order C: Force-stop, uninstall, clear (if possible), install
1. `adb shell am force-stop com.nosmoke.timer`
2. `adb uninstall com.nosmoke.timer`
3. `adb shell pm clear com.nosmoke.timer` (may fail if app doesn't exist)
4. `adb install app-debug.apk`

### Order D: Clear before uninstall (current)
1. `adb shell am force-stop com.nosmoke.timer`
2. `adb shell pm clear com.nosmoke.timer`
3. `adb uninstall com.nosmoke.timer`
4. `adb install app-debug.apk`

## Notes
- `pm clear` requires the app to be installed (will fail if app is uninstalled first)
- `uninstall` removes the app completely
- `force-stop` only stops running processes
- The order might matter for clearing cached system state


