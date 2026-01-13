# Debug Notes - Notification Lock Button Double-Click Issue

## Issue
Notification lock button requires two clicks - first click does nothing, second click works.

## Attempts Made

### Attempt 1: Changed PendingIntent from getService to getBroadcast
- Used BroadcastReceiver instead of sending intent directly to service
- Status: Failed - still requires two clicks

### Attempt 2: Changed to use getForegroundService on Android 12+
- Status: Failed - still requires two clicks

### Attempt 3: BroadcastReceiver directly modifies state, removed service action handling
- Status: Failed - still requires two clicks

### Attempt 4: Changed request code from 0 to unique LOCK_ACTION_REQUEST_CODE (1002)
- Status: Failed - still requires two clicks

### Attempt 5: Using goAsync() in BroadcastReceiver + correct request code
- Changed BroadcastReceiver to use `goAsync()` to keep receiver alive during async work
- Changed request code from 0 to LOCK_ACTION_REQUEST_CODE (1002)
- Used Dispatchers.IO for the coroutine
- Status: FAILED - exact same behavior persists

### Attempt 6: Using runBlocking (synchronous) instead of async coroutine
- Changed BroadcastReceiver to use runBlocking to make state change synchronous
- Removed goAsync() and coroutines entirely
- Status: FAILED - exact same behavior persists

### Attempt 7: Full app/service restart - kill all processes and reinstall
- Stopped service, killed app process, cleared data, uninstalled, reinstalled
- Status: SUCCESS - After fresh install, notification lock button works on first click!

### Attempt 8: Cancel notification before updating to clear cached PendingIntent
- Added `notificationManager.cancel(NOTIFICATION_ID)` before `notify()` in `updateNotification()`
- Theory: Canceling the notification should clear any cached PendingIntent state
- Status: **FAILED** - Double-click bug still persists

### Attempt 9: Revert notification cancel change
- Removed the `notificationManager.cancel()` call to go back to previous working state
- Status: **FAILED** - Double-click bug STILL persists even after reverting

### Attempt 10: Test cleanup order hypothesis
- Theory: The order of cleanup commands might matter
- Note: Attempt 7 was marked as SUCCESS with order: force-stop → clear → uninstall → install
- Current tests needed: Verify exact order that worked in Attempt 7
- Status: **FAILED** - Bug still persisted

### Attempt 11: PendingIntent FLAG_CANCEL_CURRENT + unique timestamp
- **Status**: SUCCESS
- **Changes**:
  - Changed PendingIntent flags from `FLAG_UPDATE_CURRENT` to `FLAG_CANCEL_CURRENT`
  - Added unique timestamp extra to intent to prevent caching
  - Used request code 0 instead of fixed code
  - Added `goAsync()` to BroadcastReceiver for proper async handling
  - Added comprehensive error handling and logging
  - Fixed wakeLock timeout to prevent crashes
- **Result**: **SUCCESS** - Single-click now works correctly!

## Solution
The double-click bug was caused by Android's PendingIntent caching system. The key fixes were:

1. **Use `FLAG_CANCEL_CURRENT`** instead of `FLAG_UPDATE_CURRENT` to force cancellation of any existing PendingIntent
2. **Add unique intent extras** (timestamp) to ensure each PendingIntent is treated as unique
3. **Use `goAsync()`** in BroadcastReceiver to properly handle asynchronous operations
4. **Add proper error handling** to prevent silent crashes
5. **Fix wakeLock timeout** to prevent service lifecycle issues

## Current Status
**RESOLVED** - The double-click bug has been successfully fixed. The notification now responds correctly to single taps.

## Important Note: App Reinstall Bug
**The double-click bug occurred after every app reinstall.** To fix it, you must perform a full cleanup before reinstalling:

```bash
adb shell am force-stop com.nosmoke.timer
adb shell pm clear com.nosmoke.timer
adb uninstall com.nosmoke.timer
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nosmoke.timer/.MainActivity
```

Without this cleanup, the notification lock button will require two clicks. This appears to be related to cached/stale PendingIntent or BroadcastReceiver state that persists even after app reinstall.