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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

class SmokeTimerService : LifecycleService() {

    private lateinit var stateManager: StateManager
    private lateinit var notificationManager: NotificationManager
    private var timeUpdateJob: Job? = null

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
        startForeground(NOTIFICATION_ID, createNotification(false, 0L))
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
            combine(
                stateManager.isLocked,
                stateManager.lockEndTimestamp
            ) { isLocked, lockEndTimestamp ->
                Pair(isLocked, lockEndTimestamp)
            }.collect { (isLocked, lockEndTimestamp) ->
                if (isLocked) {
                    startTimeUpdate()
                    updateNotification(isLocked, lockEndTimestamp)
                } else {
                    stopTimeUpdate()
                    updateNotification(isLocked, 0L)
                }
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

    private fun updateNotification(isLocked: Boolean, lockEndTimestamp: Long = 0L) {
        val notification = createNotification(isLocked, lockEndTimestamp)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startTimeUpdate() {
        stopTimeUpdate()
        timeUpdateJob = lifecycleScope.launch {
            while (isActive) {
                val isLocked = stateManager.getIsLocked()
                if (!isLocked) {
                    break
                }
                val currentTimestamp = stateManager.getLockEndTimestamp()
                updateNotification(true, currentTimestamp)
                delay(1000) // Update every second
            }
        }
    }

    private fun stopTimeUpdate() {
        timeUpdateJob?.cancel()
        timeUpdateJob = null
    }

    private fun createNotification(isLocked: Boolean, lockEndTimestamp: Long = 0L): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isLocked) R.drawable.ic_notification_leaf else R.drawable.ic_notification_cigarette)
            .setOngoing(true)
            .setShowWhen(false)

        if (isLocked && lockEndTimestamp > 0) {
            val remainingTime = stateManager.getRemainingTimeFormatted(lockEndTimestamp)
            builder.setContentText(remainingTime)
        }

        if (!isLocked) {
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
            builder.addAction(action)
        }

        return builder.build()
    }
}
