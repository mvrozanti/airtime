package com.nosmoke.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.nosmoke.timer.data.StateManager

class AlarmReceiver : BroadcastReceiver() {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.airtime.timer.UNLOCK") {
            scope.launch {
                val stateManager = StateManager(context)
                val isLocked = stateManager.getIsLocked()
                if (isLocked) {
                    stateManager.unlock()
                    // Restart service to update notification
                    SmokeTimerService.start(context)
                }
            }
        }
    }
}


