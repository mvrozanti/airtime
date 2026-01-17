package com.nosmoke.timer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import android.app.AlertDialog
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.nosmoke.timer.adapters.MainPagerAdapter
import com.nosmoke.timer.data.Place
import com.nosmoke.timer.data.StateManager
import com.nosmoke.timer.fragments.ConfigFragment
import com.nosmoke.timer.fragments.PlacesFragment
import com.nosmoke.timer.service.SmokeTimerService
import com.nosmoke.timer.ui.MapPickerDialog
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity(), ConfigFragment.ConfigFragmentCallback, PlacesFragment.PlacesFragmentCallback {

    private lateinit var stateManager: StateManager
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private var configFragment: ConfigFragment? = null

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
        
        // Setup ViewPager and Tabs
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        
        val adapter = MainPagerAdapter(this, stateManager)
        viewPager.adapter = adapter
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Config"
                1 -> "Places"
                2 -> "Analytics"
                else -> ""
            }
        }.attach()

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
        if (!stateManager.locationConfig.hasLocationPermission()) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startService()
        }
    }

    override fun onResume() {
        super.onResume()
        // Update config fragment when resuming
        getConfigFragment()?.updateCurrentPlace()
    }

    private fun startService() {
        SmokeTimerService.start(this)
    }
    
    private fun getConfigFragment(): ConfigFragment? {
        // Find ConfigFragment from ViewPager2
        val fragments = supportFragmentManager.fragments
        return fragments.filterIsInstance<ConfigFragment>().firstOrNull()
    }
    
    private fun getPlacesFragment(): PlacesFragment? {
        // Find PlacesFragment from ViewPager2
        val fragments = supportFragmentManager.fragments
        return fragments.filterIsInstance<PlacesFragment>().firstOrNull()
    }

    // ConfigFragmentCallback implementations
    override fun showBaseDurationPicker() {
        lifecycleScope.launch {
            val baseDuration = stateManager.getBaseDurationMinutes()
            if (baseDuration == null) {
                Toast.makeText(this@MainActivity, "Error: Cannot connect to Abacus", Toast.LENGTH_LONG).show()
                return@launch
            }
            val currentValue = baseDuration.toInt()
            
            val picker = NumberPicker(this@MainActivity).apply {
                minValue = 1
                maxValue = 600
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
                        // Delay to ensure API has processed the update and is readable
                        kotlinx.coroutines.delay(500)
                        getConfigFragment()?.updateSettings()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun showIncrementStepPicker() {
        lifecycleScope.launch {
            val incrementStep = stateManager.getIncrementStepSeconds()
            if (incrementStep == null) {
                Toast.makeText(this@MainActivity, "Error: Cannot connect to Abacus", Toast.LENGTH_LONG).show()
                return@launch
            }
            val currentValue = incrementStep.toInt()
            
            val picker = NumberPicker(this@MainActivity).apply {
                minValue = 1
                maxValue = 60
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
                        // Delay to ensure API has processed the update and is readable
                        kotlinx.coroutines.delay(500)
                        getConfigFragment()?.updateSettings()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    override fun updateCurrentPlace() {
        getConfigFragment()?.updateCurrentPlace()
    }
    
    // PlacesFragmentCallback implementations
    override fun showAddPlaceDialog() {
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
                        id = name,  // Use name as ID
                        name = name,
                        latitude = selectedLat,
                        longitude = selectedLon,
                        radiusMeters = selectedRadius,
                        baseDurationMinutes = baseDuration,
                        incrementStepSeconds = increment
                    )
                    
                    lifecycleScope.launch {
                        val (success, errorMessage) = stateManager.locationConfig.savePlace(place)
                        if (!success && errorMessage != null) {
                            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                        }
                        updateCurrentPlace()
                        getPlacesFragment()?.loadPlaces()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    override fun showEditPlaceDialog(place: Place) {
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
                    val (success, errorMessage) = stateManager.locationConfig.savePlace(updatedPlace)
                    if (!success && errorMessage != null) {
                        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                    }
                    updateCurrentPlace()
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Place?")
                    .setMessage("Are you sure you want to delete ${place.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    stateManager.locationConfig.deletePlace(place.name)
                    updateCurrentPlace()
                    getPlacesFragment()?.loadPlaces()
                }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}
