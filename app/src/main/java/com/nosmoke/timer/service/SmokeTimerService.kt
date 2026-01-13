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
        private const val LOCK_ACTION_REQUEST_CODE = 1002

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
        stateManager = StateManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoSmoke::WakeLock")
        wakeLock.acquire(10 * 60 * 1000L) // 10 minutes

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(false, 0L))
        
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
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
                if (isLocked) {
                    val remaining = stateManager.getRemainingTimeMillis(lockEndTimestamp)
                    if (remaining <= 0) {
                        stateManager.unlock()
                    }
                }
            }
        }
    }


    private fun handleLockAction() {
        lifecycleScope.launch {
            val isLocked = stateManager.getIsLocked()
            if (!isLocked) {
                stateManager.lock()
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
        val notification = createNotification(isLocked, lockEndTimestamp)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(isLocked: Boolean, lockEndTimestamp: Long): Notification {
        val title = if (isLocked) {
            getString(R.string.notification_title_locked)
        } else {
            getString(R.string.notification_title_unlocked)
        }

        val text = if (isLocked) {
            "Tap to view timer"
        } else {
            getString(R.string.notification_text_unlocked)
        }

        val lockIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LOCK
        }
        
        val lockFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val lockPendingIntent = PendingIntent.getBroadcast(this, LOCK_ACTION_REQUEST_CODE, lockIntent, lockFlags)

        val contentIntent = Intent(this, MainActivity::class.java)
        val contentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 2, contentIntent, contentFlags)

        val smallIcon = if (isLocked) {
            R.drawable.ic_notification_leaf
        } else {
            R.drawable.ic_notification_cigarette
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .apply {
                if (!isLocked) {
                    addAction(
                        android.R.drawable.ic_lock_lock,
                        getString(R.string.action_lock),
                        lockPendingIntent
                    )
                }
            }
            .build()
    }
}


