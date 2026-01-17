package com.nosmoke.timer.fragments

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
    private lateinit var lockButton: Button
    private lateinit var baseDurationDisplay: TextView
    private lateinit var incrementStepDisplay: TextView
    private lateinit var currentPlaceDisplay: TextView
    private lateinit var managePlacesButton: Button
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
        managePlacesButton = view.findViewById(R.id.managePlacesButton)
        
        // Load current configuration values
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            baseDurationDisplay.text = baseDuration.toString()
            incrementStepDisplay.text = incrementStep.toString()
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
        
        managePlacesButton.setOnClickListener {
            (activity as? ConfigFragmentCallback)?.showManagePlacesDialog()
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
            }.collect { (isLocked, _, placeId) ->
                val increment = stateManager.getIncrement(placeId)
                updateUI(isLocked, 0L, increment)  // lockEndTimestamp not used in updateUI
            }
        }
    }
    
    private fun updateUI(isLocked: Boolean, _lockEndTimestamp: Long, increment: Long) {
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
    
    fun updateCurrentPlace() {
        lifecycleScope.launch {
            val place = stateManager.locationConfig.getCurrentPlace()
            currentPlaceDisplay.text = place.name
            
            baseDurationDisplay.text = place.baseDurationMinutes.toString()
            incrementStepDisplay.text = place.incrementStepSeconds.toString()
            
            val increment = stateManager.getIncrement(place.name)
            counterText.text = "Cigarettes smoked: $increment"
        }
    }
    
    fun updateSettings() {
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            baseDurationDisplay.text = baseDuration.toString()
            incrementStepDisplay.text = incrementStep.toString()
        }
    }
    
    interface ConfigFragmentCallback {
        fun showBaseDurationPicker()
        fun showIncrementStepPicker()
        fun updateCurrentPlace()
        fun showManagePlacesDialog()
    }
}

