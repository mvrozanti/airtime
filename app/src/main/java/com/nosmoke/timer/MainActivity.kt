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
import android.widget.NumberPicker
import android.widget.EditText
import android.widget.LinearLayout
import android.app.AlertDialog
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.data.Place
import com.nosmoke.timer.data.StateManager
import com.nosmoke.timer.service.SmokeTimerService
import com.nosmoke.timer.ui.MapPickerDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : FragmentActivity() {

    private lateinit var stateManager: StateManager
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

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkLocationPermissionAndStart()
        } else {
            finish()
        }
    }
    
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Location permission is optional - continue regardless
        startService()
        observeState()
        updateCurrentPlace()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateManager = StateManager(this)
        
        // Check if we were launched from notification
        val fromNotification = intent?.getBooleanExtra("from_notification", false) ?: false
        Log.e("MainActivity", "onCreate: from_notification = $fromNotification")

        if (fromNotification) {
            Log.e("MainActivity", "Launched from notification - locking timer")
            lifecycleScope.launch {
                stateManager.lock()
            }
        }
        statusText = findViewById(R.id.statusText)
        timeText = findViewById(R.id.timeText)
        titleText = findViewById(R.id.titleText)
        counterText = findViewById(R.id.counterText)
        lockButton = findViewById(R.id.lockButton)
        baseDurationDisplay = findViewById(R.id.baseDurationDisplay)
        incrementStepDisplay = findViewById(R.id.incrementStepDisplay)
        currentPlaceDisplay = findViewById(R.id.currentPlaceDisplay)
        managePlacesButton = findViewById(R.id.managePlacesButton)
        
        // Load current configuration values
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            val incrementStep = stateManager.getIncrementStepSeconds()
            baseDurationDisplay.text = baseDuration.toString()
            incrementStepDisplay.text = incrementStep.toString()
        }
        
        // Setup click listeners to show NumberPicker dialogs
        baseDurationDisplay.setOnClickListener {
            showBaseDurationPicker()
        }
        
        incrementStepDisplay.setOnClickListener {
            showIncrementStepPicker()
        }
        
        currentPlaceDisplay.setOnClickListener {
            updateCurrentPlace()
        }
        
        managePlacesButton.setOnClickListener {
            showManagePlacesDialog()
        }

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
                
                // Refresh settings display after lock (may have changed place)
                updateCurrentPlace()
            }
        }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkLocationPermissionAndStart()
    }
    
    private fun checkLocationPermissionAndStart() {
        // Request location permission if not granted
        if (!stateManager.locationConfig.hasLocationPermission()) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startService()
            observeState()
            updateCurrentPlace()
        }
    }
    
    private fun updateCurrentPlace() {
        lifecycleScope.launch {
            val place = stateManager.locationConfig.getCurrentPlace()
            currentPlaceDisplay.text = place.name
            
            // Also update the settings display for this place
            baseDurationDisplay.text = place.baseDurationMinutes.toString()
            incrementStepDisplay.text = place.incrementStepSeconds.toString()
            
            // Update increment counter for this place
            val increment = stateManager.getIncrement(place.id)
            counterText.text = "Cigarettes smoked: $increment"
        }
    }

    override fun onResume() {
        super.onResume()
        Log.e("MainActivity", "onResume called")
        updateCurrentPlace()
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
                stateManager.currentPlaceId
            ) { isLocked, lockEndTimestamp, placeId ->
                Triple(isLocked, lockEndTimestamp, placeId)
            }.collect { (isLocked, lockEndTimestamp, placeId) ->
                val increment = stateManager.getIncrement(placeId)
                updateUI(isLocked, lockEndTimestamp, increment)  // lockEndTimestamp passed but not used in updateUI
            }
        }
    }

    private fun updateUI(isLocked: Boolean, _lockEndTimestamp: Long, increment: Long) {
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

    private fun showBaseDurationPicker() {
        lifecycleScope.launch {
            val currentValue = stateManager.getBaseDurationMinutes().toInt()
            
            val picker = NumberPicker(this@MainActivity).apply {
                minValue = 1
                maxValue = 600  // 10 hours max
                value = currentValue.coerceIn(1, 600)
                wrapSelectorWheel = false
            }
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Base Duration (minutes)")
                .setView(picker)
                .setPositiveButton("OK") { _, _ ->
                    val selectedValue = picker.value.toLong()
                    lifecycleScope.launch {
                        stateManager.setBaseDurationMinutes(selectedValue)
                        baseDurationDisplay.text = selectedValue.toString()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showIncrementStepPicker() {
        lifecycleScope.launch {
            val currentValue = stateManager.getIncrementStepSeconds().toInt()
            
            val picker = NumberPicker(this@MainActivity).apply {
                minValue = 1
                maxValue = 60  // 1 minute max
                value = currentValue.coerceIn(1, 60)
                wrapSelectorWheel = false
            }
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Increment Step (seconds)")
                .setView(picker)
                .setPositiveButton("OK") { _, _ ->
                    val selectedValue = picker.value.toLong()
                    lifecycleScope.launch {
                        stateManager.setIncrementStepSeconds(selectedValue)
                        incrementStepDisplay.text = selectedValue.toString()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun showManagePlacesDialog() {
        lifecycleScope.launch {
            val places = stateManager.locationConfig.getPlaces()
            val placeNames = places.map { it.name }.toTypedArray()
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Manage Places")
                .setItems(placeNames) { _, which ->
                    showEditPlaceDialog(places[which])
                }
                .setPositiveButton("Add New Place") { _, _ ->
                    showAddPlaceDialog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun showAddPlaceDialog() {
        lifecycleScope.launch {
            val location = stateManager.locationConfig.getCurrentLocation()
            
            val layout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
            }
            
            val nameInput = EditText(this@MainActivity).apply {
                hint = "Place name"
            }
            layout.addView(nameInput)
            
            val baseDurationInput = EditText(this@MainActivity).apply {
                hint = "Base duration (minutes)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("40")
            }
            layout.addView(baseDurationInput)
            
            val incrementInput = EditText(this@MainActivity).apply {
                hint = "Increment step (seconds)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("1")
            }
            layout.addView(incrementInput)
            
            var selectedLat = location?.latitude ?: 0.0
            var selectedLon = location?.longitude ?: 0.0
            var selectedRadius = 100.0
            
            val locationButton = Button(this@MainActivity).apply {
                text = "Pick Location on Map"
                setOnClickListener {
                    val mapDialog = MapPickerDialog.newInstance(selectedLat, selectedLon, selectedRadius)
                    mapDialog.setOnLocationSelectedListener(object : MapPickerDialog.OnLocationSelectedListener {
                        override fun onLocationSelected(latitude: Double, longitude: Double, radiusMeters: Double) {
                            selectedLat = latitude
                            selectedLon = longitude
                            selectedRadius = radiusMeters
                            text = "Location: ${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)} (${radiusMeters.toInt()}m)"
                        }
                    })
                    mapDialog.show(supportFragmentManager, "mapPicker")
                }
            }
            layout.addView(locationButton)
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add New Place")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val name = nameInput.text.toString().ifEmpty { "Unnamed" }
                    val baseDuration = baseDurationInput.text.toString().toLongOrNull() ?: 40L
                    val increment = incrementInput.text.toString().toLongOrNull() ?: 1L
                    
                    val place = Place(
                        id = UUID.randomUUID().toString().take(8),
                        name = name,
                        latitude = selectedLat,
                        longitude = selectedLon,
                        radiusMeters = selectedRadius,
                        baseDurationMinutes = baseDuration,
                        incrementStepSeconds = increment
                    )
                    
                    lifecycleScope.launch {
                        stateManager.locationConfig.savePlace(place)
                        updateCurrentPlace()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun showEditPlaceDialog(place: Place) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        
        val nameInput = EditText(this).apply {
            hint = "Place name"
            setText(place.name)
        }
        layout.addView(nameInput)
        
        val baseDurationInput = EditText(this).apply {
            hint = "Base duration (minutes)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(place.baseDurationMinutes.toString())
        }
        layout.addView(baseDurationInput)
        
        val incrementInput = EditText(this).apply {
            hint = "Increment step (seconds)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(place.incrementStepSeconds.toString())
        }
        layout.addView(incrementInput)
        
        var selectedLat = place.latitude
        var selectedLon = place.longitude
        var selectedRadius = place.radiusMeters
        
        val locationButton = Button(this).apply {
            text = "Location: ${String.format("%.4f", place.latitude)}, ${String.format("%.4f", place.longitude)} (${place.radiusMeters.toInt()}m)"
            setOnClickListener {
                val mapDialog = MapPickerDialog.newInstance(selectedLat, selectedLon, selectedRadius)
                mapDialog.setOnLocationSelectedListener(object : MapPickerDialog.OnLocationSelectedListener {
                    override fun onLocationSelected(latitude: Double, longitude: Double, radiusMeters: Double) {
                        selectedLat = latitude
                        selectedLon = longitude
                        selectedRadius = radiusMeters
                        text = "Location: ${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)} (${radiusMeters.toInt()}m)"
                    }
                })
                mapDialog.show(supportFragmentManager, "mapPicker")
            }
        }
        layout.addView(locationButton)
        
        AlertDialog.Builder(this)
            .setTitle("Edit Place: ${place.name}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val updatedPlace = place.copy(
                    name = nameInput.text.toString().ifEmpty { place.name },
                    latitude = selectedLat,
                    longitude = selectedLon,
                    radiusMeters = selectedRadius,
                    baseDurationMinutes = baseDurationInput.text.toString().toLongOrNull() ?: place.baseDurationMinutes,
                    incrementStepSeconds = incrementInput.text.toString().toLongOrNull() ?: place.incrementStepSeconds
                )
                
                lifecycleScope.launch {
                    stateManager.locationConfig.savePlace(updatedPlace)
                    updateCurrentPlace()
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Place?")
                    .setMessage("Are you sure you want to delete ${place.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            stateManager.locationConfig.deletePlace(place.id)
                            updateCurrentPlace()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}