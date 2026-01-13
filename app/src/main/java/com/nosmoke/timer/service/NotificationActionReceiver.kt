package com.nosmoke.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LOCK -> {
                val stateManager = StateManager(context.applicationContext)
                runBlocking {
                    val isLocked = stateManager.getIsLocked()
                    if (!isLocked) {
                        stateManager.lock()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.nosmoke.timer.ACTION_LOCK"
    }
}

