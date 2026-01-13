package com.nosmoke.timer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.data.StateManager
import com.nosmoke.timer.service.SmokeTimerService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var stateManager: StateManager
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var lockButton: Button
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startService()
            observeState()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check if we were launched from notification
        val fromNotification = intent?.getBooleanExtra("from_notification", false) ?: false
        Log.e("MainActivity", "onCreate: from_notification = $fromNotification")

        if (fromNotification) {
            Log.e("MainActivity", "Launched from notification - locking timer")
            lifecycleScope.launch {
                stateManager.lock()
            }
        }

        stateManager = StateManager(this)
        statusText = findViewById(R.id.statusText)
        timeText = findViewById(R.id.timeText)
        titleText = findViewById(R.id.titleText)
        counterText = findViewById(R.id.counterText)
        lockButton = findViewById(R.id.lockButton)

        lockButton.setOnClickListener {
            Log.e("MainActivity", "=== LOCK BUTTON CLICKED ===")
            lifecycleScope.launch {
                Log.e("MainActivity", "Lock button: Starting lock operation")
                val isLockedBefore = stateManager.getIsLocked()
                Log.e("MainActivity", "Lock button: State BEFORE lock: $isLockedBefore")

                stateManager.lock()

                val isLockedAfter = stateManager.getIsLocked()
                Log.e("MainActivity", "Lock button: State AFTER lock: $isLockedAfter")

                Log.e("MainActivity", "Lock button: Lock operation completed")
            }
        }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startService()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        Log.e("MainActivity", "onResume called")
        lifecycleScope.launch {
            val isLocked = stateManager.getIsLocked()
            if (isLocked) {
                startPeriodicUpdate()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicUpdate()
    }

    private fun startService() {
        SmokeTimerService.start(this)
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                stateManager.isLocked,
                stateManager.lockEndTimestamp,
                stateManager.increment
            ) { isLocked, lockEndTimestamp, increment ->
                Triple(isLocked, lockEndTimestamp, increment)
            }.collect { (isLocked, lockEndTimestamp, increment) ->
                updateUI(isLocked, lockEndTimestamp, increment)
            }
        }
    }

    private fun updateUI(isLocked: Boolean, lockEndTimestamp: Long, increment: Long) {
        Log.e("MainActivity", "UPDATE UI: isLocked=$isLocked, increment=$increment")

        // Update counter (increment represents number of cigarettes smoked)
        counterText.text = "Cigarettes smoked: $increment"

        if (isLocked) {
            Log.e("MainActivity", "UPDATE UI: Setting LOCKED state")
            titleText.text = "🌿"
            statusText.text = "Timer Locked"
            timeText.visibility = TextView.VISIBLE
            startPeriodicUpdate()
        } else {
            Log.e("MainActivity", "UPDATE UI: Setting UNLOCKED state")
            titleText.text = "🚬"
            statusText.text = "Timer Unlocked"
            timeText.visibility = View.GONE
            stopPeriodicUpdate()
        }

        Log.e("MainActivity", "UPDATE UI: Completed")
    }

    private fun startPeriodicUpdate() {
        stopPeriodicUpdate()
        var runnable: Runnable? = null
        runnable = Runnable {
            lifecycleScope.launch {
                val isLocked = stateManager.getIsLocked()
                val lockEndTimestamp = stateManager.getLockEndTimestamp()
                if (isLocked && lockEndTimestamp > 0) {
                    val remaining = stateManager.getRemainingTimeFormatted(lockEndTimestamp)
                    timeText.text = remaining
                    val remainingMs = stateManager.getRemainingTimeMillis(lockEndTimestamp)
                    if (remainingMs > 0) {
                        runnable?.let { updateHandler.postDelayed(it, 1000) }
                    } else {
                        stateManager.unlock()
                    }
                }
            }
        }
        updateRunnable = runnable
        updateHandler.post(runnable)
    }

    private fun stopPeriodicUpdate() {
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null
    }

}