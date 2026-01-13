package com.nosmoke.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("NotificationActionReceiver", "=== BROADCAST RECEIVER TRIGGERED ===")
        Log.d("NotificationActionReceiver", "Action: ${intent.action}")
        Log.d("NotificationActionReceiver", "Component: ${intent.component}")
        Log.d("NotificationActionReceiver", "Package: ${intent.`package`}")

        when (intent.action) {
            ACTION_LOCK -> {
                Log.d("NotificationActionReceiver", "Processing ACTION_LOCK")
                val stateManager = StateManager(context.applicationContext)
                runBlocking {
                    val isLockedBefore = stateManager.getIsLocked()
                    Log.d("NotificationActionReceiver", "Lock state BEFORE action: $isLockedBefore")

                    // Only lock if not already locked (cannot unlock by tapping)
                    if (!isLockedBefore) {
                        Log.d("NotificationActionReceiver", "Locking timer...")
                        stateManager.lock()
                        val isLockedAfter = stateManager.getIsLocked()
                        Log.d("NotificationActionReceiver", "Timer locked successfully. Lock state AFTER: $isLockedAfter")
                    } else {
                        Log.d("NotificationActionReceiver", "Timer already locked, ignoring tap")
                    }
                }
            }
            else -> {
                Log.w("NotificationActionReceiver", "Unknown action: ${intent.action}")
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.nosmoke.timer.ACTION_LOCK"
    }
}

