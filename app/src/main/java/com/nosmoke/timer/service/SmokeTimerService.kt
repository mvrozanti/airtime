package com.nosmoke.timer.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.MainActivity
import com.nosmoke.timer.R
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SmokeTimerService : LifecycleService() {

    private lateinit var stateManager: StateManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smoke_timer_channel"
        private const val ALARM_REQUEST_CODE = 1001

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, SmokeTimerService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SmokeTimerService", "Service onCreate")
        stateManager = StateManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoSmoke::WakeLock")
        wakeLock.acquire() // No timeout - released in onDestroy

        createNotificationChannel()
        val initialNotification = createNotification(false, 0L)
        startForeground(NOTIFICATION_ID, initialNotification)

        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SmokeTimerService", "onStartCommand: ${intent?.action}")

        // Handle notification tap
        if (intent?.action == "ACTION_LOCK_FROM_NOTIFICATION") {
            Log.e("SmokeTimerService", "=== NOTIFICATION TAP DETECTED ===")
            lifecycleScope.launch {
                val isLocked = stateManager.getIsLocked()
                Log.e("SmokeTimerService", "Current lock state: $isLocked")
                if (!isLocked) {
                    stateManager.lock()
                    Log.e("SmokeTimerService", "Timer locked from notification!")
                } else {
                    Log.e("SmokeTimerService", "Timer already locked, ignoring notification tap")
                }
            }
        }

        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.d("SmokeTimerService", "Service onDestroy")
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                stateManager.isLocked,
                stateManager.lockEndTimestamp
            ) { isLocked, lockEndTimestamp ->
                Pair(isLocked, lockEndTimestamp)
            }.collect { (isLocked, lockEndTimestamp) ->
                updateNotification(isLocked, lockEndTimestamp)
                
                if (isLocked && lockEndTimestamp > 0) {
                    scheduleUnlockAlarm(lockEndTimestamp)
                } else {
                    cancelUnlockAlarm()
                }
                
                // Check if timer expired
                if (isLocked && lockEndTimestamp > 0) {
                    val remaining = stateManager.getRemainingTimeMillis(lockEndTimestamp)
                    if (remaining <= 0) {
                        Log.d("SmokeTimerService", "Timer expired, unlocking")
                        stateManager.unlock()
                    }
                }
            }
        }
    }




    private fun scheduleUnlockAlarm(lockEndTimestamp: Long) {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.nosmoke.timer.UNLOCK"
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    lockEndTimestamp,
                    pendingIntent
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, lockEndTimestamp, pendingIntent)
            }
            else -> {
                alarmManager.set(AlarmManager.RTC_WAKEUP, lockEndTimestamp, pendingIntent)
            }
        }
    }

    private fun cancelUnlockAlarm() {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.nosmoke.timer.UNLOCK"
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags)
        alarmManager.cancel(pendingIntent)
    }

    private fun updateNotification(isLocked: Boolean, lockEndTimestamp: Long) {
        try {
            Log.d("SmokeTimerService", "Updating notification, isLocked: $isLocked")
            // Cancel existing notification before updating to clear any cached PendingIntent
            notificationManager.cancel(NOTIFICATION_ID)
            val notification = createNotification(isLocked, lockEndTimestamp)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("SmokeTimerService", "Error updating notification", e)
        }
    }

    private fun createNotification(isLocked: Boolean, lockEndTimestamp: Long): Notification {
        val title = if (isLocked) {
            getString(R.string.notification_title_locked)
        } else {
            getString(R.string.notification_title_unlocked)
        }

        val text = if (isLocked) {
            "Timer locked"
        } else {
            "Tap to lock"
        }

        // Notification tap sends intent to service
        val lockIntent = Intent(this, SmokeTimerService::class.java).apply {
            action = "ACTION_LOCK_FROM_NOTIFICATION"
        }

        val contentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentPendingIntent = PendingIntent.getService(this, 1002, lockIntent, contentFlags)
        Log.d("SmokeTimerService", "Created SERVICE PendingIntent: $contentPendingIntent for isLocked=$isLocked")

        val smallIcon = if (isLocked) {
            R.drawable.ic_notification_leaf
        } else {
            R.drawable.ic_notification_cigarette
        }

        Log.d("SmokeTimerService", "Building notification with contentIntent: $contentPendingIntent")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(contentPendingIntent)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()
    }
}


