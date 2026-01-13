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

## Solution
The final working solution:
- BroadcastReceiver uses `runBlocking` for synchronous state changes
- PendingIntent uses unique request code `LOCK_ACTION_REQUEST_CODE` (1002)
- Fresh install was necessary to clear any cached/stale state

