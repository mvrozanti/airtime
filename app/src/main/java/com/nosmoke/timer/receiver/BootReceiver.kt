package com.nosmoke.timer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nosmoke.timer.service.SmokeTimerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            SmokeTimerService.start(context)
        }
    }
}


