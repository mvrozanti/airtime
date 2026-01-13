package com.nosmoke.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val timestamp = intent.getLongExtra("timestamp", 0L)
        val currentTime = System.currentTimeMillis()
        Log.d("NotificationActionReceiver", "Received action: ${intent.action}, timestamp: $timestamp, currentTime: $currentTime, delay: ${currentTime - timestamp}ms")

        when (intent.action) {
            ACTION_LOCK -> {
                try {
                    val stateManager = StateManager(context.applicationContext)
                    runBlocking {
                        val isLocked = stateManager.getIsLocked()
                        Log.d("NotificationActionReceiver", "Current lock state: $isLocked")
                        // Only lock if not already locked (cannot unlock by tapping)
                        if (!isLocked) {
                            stateManager.lock()
                            Log.d("NotificationActionReceiver", "Timer locked successfully")
                        } else {
                            Log.d("NotificationActionReceiver", "Timer already locked, ignoring")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NotificationActionReceiver", "Error processing lock action", e)
                } finally {
                    pendingResult.finish()
                }
            }
            else -> {
                Log.w("NotificationActionReceiver", "Unknown action: ${intent.action}")
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.nosmoke.timer.ACTION_LOCK"
    }
}

