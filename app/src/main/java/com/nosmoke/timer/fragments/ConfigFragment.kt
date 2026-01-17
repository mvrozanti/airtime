package com.nosmoke.timer.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.R
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {
    
    private lateinit var stateManager: StateManager
    
    companion object {
        private const val ARG_STATE_MANAGER = "stateManager"
        
        fun newInstance(stateManager: StateManager): ConfigFragment {
            return ConfigFragment().apply {
                // Store stateManager reference - will be set via callback
            }
        }
    }
    
    fun setStateManager(stateManager: StateManager) {
        this.stateManager = stateManager
    }
    
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var bufferText: TextView
    private lateinit var bufferTimeText: TextView
    private lateinit var lockButton: Button
    private lateinit var baseDurationDisplay: TextView
    private lateinit var incrementStepDisplay: TextView
    private lateinit var currentPlaceDisplay: TextView
    private lateinit var resetButton: Button
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        statusText = view.findViewById(R.id.statusText)
        timeText = view.findViewById(R.id.timeText)
        titleText = view.findViewById(R.id.titleText)
        counterText = view.findViewById(R.id.counterText)
        bufferText = view.findViewById(R.id.bufferText)
        bufferTimeText = view.findViewById(R.id.bufferTimeText)
        lockButton = view.findViewById(R.id.lockButton)
        baseDurationDisplay = view.findViewById(R.id.baseDurationDisplay)
        incrementStepDisplay = view.findViewById(R.id.incrementStepDisplay)
        currentPlaceDisplay = view.findViewById(R.id.currentPlaceDisplay)
        resetButton = view.findViewById(R.id.resetButton)
        
        // Load current configuration values
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            if (baseDuration != null) {
                baseDurationDisplay.text = baseDuration.toString()
            } else {
                baseDurationDisplay.text = "?"
            }
            if (incrementStep != null) {
                incrementStepDisplay.text = incrementStep.toString()
            } else {
                incrementStepDisplay.text = "?"
            }
        }
        
        // Setup click listeners
        baseDurationDisplay.setOnClickListener {
            (activity as? ConfigFragmentCallback)?.showBaseDurationPicker()
        }
        
        incrementStepDisplay.setOnClickListener {
            (activity as? ConfigFragmentCallback)?.showIncrementStepPicker()
        }
        
        currentPlaceDisplay.setOnClickListener {
            (activity as? ConfigFragmentCallback)?.updateCurrentPlace()
        }
        
        resetButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset Everything?")
                .setMessage("This will reset all timer state, clear all places, and clear all analytics. This action cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    lifecycleScope.launch {
                        stateManager.resetEverything()
                        updateCurrentPlace()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        lockButton.setOnClickListener {
            lifecycleScope.launch {
                stateManager.lock()
                updateCurrentPlace()
            }
        }
        
        observeState()
        updateCurrentPlace()
    }
    
    override fun onResume() {
        super.onResume()
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
    
    private fun observeState() {
        lifecycleScope.launch {
            combine(
                stateManager.isLocked,
                stateManager.lockEndTimestamp,
                stateManager.currentPlaceId
            ) { isLocked, lockEndTimestamp, placeId ->
                Triple(isLocked, lockEndTimestamp, placeId)
            }.collect { (isLocked, lockEndTimestamp, placeId) ->
                val increment = stateManager.getIncrement(placeId)
                updateUI(isLocked, lockEndTimestamp, increment)
            }
        }
    }
    
    private fun updateUI(isLocked: Boolean, lockEndTimestamp: Long, increment: Long) {
        counterText.text = "Cigarettes smoked: $increment"
        
        // Update buffer display
        lifecycleScope.launch {
            val place = stateManager.locationConfig.getCurrentPlace()
            val buffer = place.buffer
            
            // Calculate buffer remaining: buffer - (increment % buffer)
            // Shows how many more cigarettes can be smoked in current buffer cycle
            // increment 0: 3/3 (3 remaining), increment 1: 2/3 (2 remaining), increment 3: 0/3 (0 remaining - need to lock)
            val bufferUsed = (increment % buffer).toInt()
            val bufferRemaining = buffer - bufferUsed
            bufferText.text = "Buffer: ($bufferRemaining/$buffer)"
            
            // Calculate time until next buffer
            if (isLocked && lockEndTimestamp > 0) {
                val remainingMs = lockEndTimestamp - System.currentTimeMillis()
                if (remainingMs > 0) {
                    val formatted = stateManager.getRemainingTimeFormatted(lockEndTimestamp)
                    bufferTimeText.text = "Time until next buffer: $formatted"
                } else {
                    bufferTimeText.text = "Time until next buffer: Ready"
                }
            } else {
                // Check if buffer is full (need to lock to reset)
                val needsLock = (increment > 0 && (increment % buffer == 0L))
                if (needsLock) {
                    bufferTimeText.text = "Time until next buffer: Lock required"
                } else {
                    bufferTimeText.text = "Time until next buffer: Ready"
                }
            }
        }
        
        if (isLocked) {
            titleText.text = "🌿"
            statusText.text = "Timer Locked"
            timeText.visibility = TextView.VISIBLE
            lockButton.visibility = View.GONE
            startPeriodicUpdate()
        } else {
            titleText.text = "🚬"
            statusText.text = "Timer Unlocked"
            timeText.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            stopPeriodicUpdate()
        }
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
                    
                    // Update buffer time text
                    val place = stateManager.locationConfig.getCurrentPlace()
                    val buffer = place.buffer
                    val remainingMs = lockEndTimestamp - System.currentTimeMillis()
                    if (remainingMs > 0) {
                        bufferTimeText.text = "Time until next buffer: $remaining"
                    } else {
                        bufferTimeText.text = "Time until next buffer: Ready"
                    }
                    
                    val remainingMsForCheck = stateManager.getRemainingTimeMillis(lockEndTimestamp)
                    if (remainingMsForCheck > 0) {
                        runnable?.let { updateHandler.postDelayed(it, 1000) }
                    } else {
                        // Timer expired, unlock
                        stateManager.unlock()
                    }
                } else if (isLocked && lockEndTimestamp == 0L) {
                    // Locked but no valid timestamp - this shouldn't happen, unlock it
                    timeText.text = "--:--"
                    stateManager.unlock()
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
    
    fun updateCurrentPlace() {
        lifecycleScope.launch {
            // Show actual location-based current place (could be Home, Work, or Unknown)
            val place = stateManager.locationConfig.getCurrentPlace()
            currentPlaceDisplay.text = place.name
            
            // Get settings from Abacus for the current place
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            baseDurationDisplay.text = baseDuration?.toString() ?: place.baseDurationMinutes.toString()
            incrementStepDisplay.text = incrementStep?.toString() ?: place.incrementStepSeconds.toString()
            
            val increment = stateManager.getCurrentIncrement()
            counterText.text = "Cigarettes smoked: $increment"
        }
    }
    
    fun updateSettings() {
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            if (baseDuration != null) {
                baseDurationDisplay.text = baseDuration.toString()
            } else {
                baseDurationDisplay.text = "?"
            }
            if (incrementStep != null) {
                incrementStepDisplay.text = incrementStep.toString()
            } else {
                incrementStepDisplay.text = "?"
            }
        }
    }
    
    interface ConfigFragmentCallback {
        fun showBaseDurationPicker()
        fun showIncrementStepPicker()
        fun updateCurrentPlace()
    }
}

