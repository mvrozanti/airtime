package com.nosmoke.timer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.R
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SmokeTimerService : LifecycleService() {

    private lateinit var stateManager: StateManager
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smoke_timer_channel"

        fun start(context: Context) {
            val intent = Intent(context, SmokeTimerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateManager = StateManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(false))
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "LOCK") {
            lifecycleScope.launch {
                val isLocked = stateManager.getIsLocked()
                if (!isLocked) {
                    stateManager.lock()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun observeState() {
        lifecycleScope.launch {
            stateManager.isLocked.collect { isLocked ->
                updateNotification(isLocked)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timer",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(isLocked: Boolean) {
        val notification = createNotification(isLocked)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(isLocked: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NoSmoke")
            .setSmallIcon(if (isLocked) R.drawable.ic_notification_leaf else R.drawable.ic_notification_cigarette)
            .setOngoing(true)

        if (isLocked) {
            builder.setContentText("Timer locked")
        } else {
            val intent = Intent(this, SmokeTimerService::class.java).apply {
                action = "LOCK"
            }
            val pendingIntent = PendingIntent.getService(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_notification_cigarette,
                "Lock",
                pendingIntent
            ).build()
            builder.setContentText("Tap to lock")
                .addAction(action)
        }

        return builder.build()
    }
}
