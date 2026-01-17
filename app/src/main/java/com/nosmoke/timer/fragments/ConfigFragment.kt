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
import com.nosmoke.timer.data.Place
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
    private lateinit var lockButton: Button
    private lateinit var baseDurationDisplay: TextView
    private lateinit var incrementStepDisplay: TextView
    private lateinit var currentPlaceDisplay: TextView
    private lateinit var resetButton: Button
    private lateinit var lockConfigButton: Button
    private lateinit var configLockStatusText: TextView
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
        lockButton = view.findViewById(R.id.lockButton)
        baseDurationDisplay = view.findViewById(R.id.baseDurationDisplay)
        incrementStepDisplay = view.findViewById(R.id.incrementStepDisplay)
        currentPlaceDisplay = view.findViewById(R.id.currentPlaceDisplay)
        resetButton = view.findViewById(R.id.resetButton)
        lockConfigButton = view.findViewById(R.id.lockConfigButton)
        configLockStatusText = view.findViewById(R.id.configLockStatusText)
        
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
            lifecycleScope.launch {
                if (stateManager.isConfigLocked()) {
                    val remaining = stateManager.getConfigLockEndTimestamp() - System.currentTimeMillis()
                    if (remaining > 0) {
                        val hours = (remaining / (60 * 60 * 1000)).toInt()
                        val minutes = ((remaining % (60 * 60 * 1000)) / (60 * 1000)).toInt()
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Config is locked for ${hours}h ${minutes}m more",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    (activity as? ConfigFragmentCallback)?.showBaseDurationPicker()
                }
            }
        }
        
        incrementStepDisplay.setOnClickListener {
            lifecycleScope.launch {
                if (stateManager.isConfigLocked()) {
                    val remaining = stateManager.getConfigLockEndTimestamp() - System.currentTimeMillis()
                    if (remaining > 0) {
                        val hours = (remaining / (60 * 60 * 1000)).toInt()
                        val minutes = ((remaining % (60 * 60 * 1000)) / (60 * 1000)).toInt()
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Config is locked for ${hours}h ${minutes}m more",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    (activity as? ConfigFragmentCallback)?.showIncrementStepPicker()
                }
            }
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
        
        lockConfigButton.setOnClickListener {
            showLockConfigDialog()
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
                stateManager.currentPlaceId,
                stateManager.configLockEndTimestamp
            ) { isLocked, lockEndTimestamp, placeId, configLockEndTimestamp ->
                Quadruple(isLocked, lockEndTimestamp, placeId, configLockEndTimestamp)
            }.collect { (isLocked, lockEndTimestamp, placeId, configLockEndTimestamp) ->
                val increment = stateManager.getIncrement(placeId)
                updateUI(isLocked, lockEndTimestamp, increment)
                updateConfigLockStatus(configLockEndTimestamp)
            }
        }
    }
    
    private fun updateUI(isLocked: Boolean, lockEndTimestamp: Long, increment: Long) {
        counterText.text = "Cigarettes smoked: $increment"
        
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
    
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    
    private fun updateConfigLockStatus(configLockEndTimestamp: Long) {
        lifecycleScope.launch {
            val isLocked = configLockEndTimestamp > 0 && configLockEndTimestamp > System.currentTimeMillis()
            
            if (isLocked) {
                val remainingMs = configLockEndTimestamp - System.currentTimeMillis()
                val hours = (remainingMs / (60 * 60 * 1000)).toInt()
                val minutes = ((remainingMs % (60 * 60 * 1000)) / (60 * 1000)).toInt()
                configLockStatusText.text = "🔒 Config locked for ${hours}h ${minutes}m"
                configLockStatusText.visibility = View.VISIBLE
                lockConfigButton.text = "Unlock Config"
                
                // Disable config controls
                baseDurationDisplay.alpha = 0.5f
                baseDurationDisplay.isEnabled = false
                incrementStepDisplay.alpha = 0.5f
                incrementStepDisplay.isEnabled = false
            } else {
                configLockStatusText.visibility = View.GONE
                lockConfigButton.text = "Lock Config"
                
                // Enable config controls
                baseDurationDisplay.alpha = 1.0f
                baseDurationDisplay.isEnabled = true
                incrementStepDisplay.alpha = 1.0f
                incrementStepDisplay.isEnabled = true
            }
        }
    }
    
    private fun showLockConfigDialog() {
        lifecycleScope.launch {
            val isLocked = stateManager.isConfigLocked()
            if (isLocked) {
                // Unlock
                AlertDialog.Builder(requireContext())
                    .setTitle("Unlock Config?")
                    .setMessage("This will allow you to change settings again.")
                    .setPositiveButton("Unlock") { _, _ ->
                        lifecycleScope.launch {
                            stateManager.unlockConfig()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // Lock - show time picker
                val hoursArray = arrayOf("1 hour", "6 hours", "12 hours", "24 hours", "3 days", "7 days")
                val hoursValues = intArrayOf(1, 6, 12, 24, 72, 168)
                
                AlertDialog.Builder(requireContext())
                    .setTitle("Lock Config For")
                    .setItems(hoursArray) { _, which ->
                        val hours = hoursValues[which]
                        lifecycleScope.launch {
                            stateManager.lockConfig(hours)
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Config locked for $hours hours",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
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

