package com.nosmoke.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("NotificationActionReceiver", "=== BROADCAST RECEIVER TRIGGERED ===")
        Log.e("NotificationActionReceiver", "Action: ${intent.action}")
        Log.e("NotificationActionReceiver", "Component: ${intent.component}")
        Log.e("NotificationActionReceiver", "Package: ${intent.`package`}")
        Log.e("NotificationActionReceiver", "Extras: ${intent.extras}")

        when (intent.action) {
            ACTION_LOCK -> {
                Log.e("NotificationActionReceiver", "Processing ACTION_LOCK")
                try {
                    val stateManager = StateManager(context.applicationContext)
                    runBlocking {
                        val isLockedBefore = stateManager.getIsLocked()
                        Log.e("NotificationActionReceiver", "Lock state BEFORE action: $isLockedBefore")

                        // Only lock if not already locked (cannot unlock by tapping)
                        if (!isLockedBefore) {
                            Log.e("NotificationActionReceiver", "Locking timer...")
                            stateManager.lock()
                            val isLockedAfter = stateManager.getIsLocked()
                            Log.e("NotificationActionReceiver", "Timer locked successfully. Lock state AFTER: $isLockedAfter")
                        } else {
                            Log.e("NotificationActionReceiver", "Timer already locked, ignoring tap")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NotificationActionReceiver", "Error in ACTION_LOCK", e)
                }
            }
            else -> {
                Log.e("NotificationActionReceiver", "Unknown action: ${intent.action}")
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.nosmoke.timer.ACTION_LOCK"
    }
}

