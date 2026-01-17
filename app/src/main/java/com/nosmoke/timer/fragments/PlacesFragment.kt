package com.nosmoke.timer.fragments

import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.R
import com.nosmoke.timer.data.Place
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PlacesFragment : Fragment() {
    
    private lateinit var stateManager: StateManager
    private lateinit var placesContainer: LinearLayout
    private lateinit var addPlaceButton: Button
    
    fun setStateManager(stateManager: StateManager) {
        this.stateManager = stateManager
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_places, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        placesContainer = view.findViewById(R.id.placesContainer)
        addPlaceButton = view.findViewById(R.id.addPlaceButton)
        
        addPlaceButton.setOnClickListener {
            (activity as? PlacesFragmentCallback)?.showAddPlaceDialog()
        }
        
        loadPlaces()
    }
    
    override fun onResume() {
        super.onResume()
        loadPlaces()
    }
    
    fun loadPlaces() {
        lifecycleScope.launch {
            placesContainer.removeAllViews()
            val places = stateManager.locationConfig.getPlaces()
            
            if (places.isEmpty()) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No places configured yet.\nAdd a place to get started!"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                    setPadding(0, 32, 0, 32)
                    gravity = android.view.Gravity.CENTER
                }
                placesContainer.addView(emptyText)
                return@launch
            }
            
            val density = resources.displayMetrics.density
            
            places.forEach { place ->
                val placeCard = androidx.cardview.widget.CardView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (16 * density).toInt())
                    }
                    cardElevation = 4f * density
                    radius = 12f * density
                    setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
                }
                
                val cardContent = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                }
                
                val nameText = TextView(requireContext()).apply {
                    text = place.name
                    textSize = 18f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                }
                
                val detailsText = TextView(requireContext()).apply {
                    text = buildString {
                        append("Base Duration: ${place.baseDurationMinutes} min")
                        append("\nIncrement Step: ${place.incrementStepSeconds} sec")
                        append("\nBuffer: ${place.buffer} cigarette${if (place.buffer != 1) "s" else ""}")
                        append("\nLocation: Loading...")
                        append("\nRadius: ${place.radiusMeters.toInt()}m")
                    }
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                
                // Load address asynchronously
                lifecycleScope.launch {
                    val address = getAddressFromCoordinates(place.latitude, place.longitude)
                    val addressText = if (address != null) {
                        address
                    } else {
                        "${String.format("%.4f", place.latitude)}, ${String.format("%.4f", place.longitude)}"
                    }
                    detailsText.text = buildString {
                        append("Base Duration: ${place.baseDurationMinutes} min")
                        append("\nIncrement Step: ${place.incrementStepSeconds} sec")
                        append("\nBuffer: ${place.buffer} cigarette${if (place.buffer != 1) "s" else ""}")
                        append("\nLocation: $addressText")
                        append("\nRadius: ${place.radiusMeters.toInt()}m")
                    }
                }
                
                cardContent.addView(nameText)
                cardContent.addView(detailsText)
                placeCard.addView(cardContent)
                
                placeCard.setOnClickListener {
                    (activity as? PlacesFragmentCallback)?.showEditPlaceDialog(place)
                }
                
                placesContainer.addView(placeCard)
            }
        }
    }
    
    private suspend fun getAddressFromCoordinates(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            
            @Suppress("DEPRECATION", "BlockingMethodInNonBlockingContext")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                // Try to get street address, fall back to feature name or formatted address
                val streetAddress = address.getAddressLine(0) ?: address.featureName
                streetAddress
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    interface PlacesFragmentCallback {
        fun showAddPlaceDialog()
        fun showEditPlaceDialog(place: Place)
    }
}

