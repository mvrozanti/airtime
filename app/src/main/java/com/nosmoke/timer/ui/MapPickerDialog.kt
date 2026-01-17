package com.nosmoke.timer.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import com.nosmoke.timer.R
import kotlin.math.cos
import kotlin.math.sin

class MapPickerDialog : DialogFragment() {
    
    interface OnLocationSelectedListener {
        fun onLocationSelected(latitude: Double, longitude: Double, radiusMeters: Double)
    }
    
    private var listener: OnLocationSelectedListener? = null
    private var initialLatitude: Double = 0.0
    private var initialLongitude: Double = 0.0
    private var initialRadiusMeters: Double = 100.0
    
    private var mapView: MapView? = null
    private var mapController: IMapController? = null
    private var selectedLocation: GeoPoint? = null
    private var selectedMarker: Marker? = null
    private var radiusCircle: Polygon? = null
    private var radiusSlider: SeekBar? = null
    private var radiusText: TextView? = null
    
    companion object {
        fun newInstance(
            latitude: Double = 0.0,
            longitude: Double = 0.0,
            radiusMeters: Double = 100.0
        ): MapPickerDialog {
            return MapPickerDialog().apply {
                arguments = Bundle().apply {
                    putDouble("latitude", latitude)
                    putDouble("longitude", longitude)
                    putDouble("radius", radiusMeters)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
        // Configure osmdroid
        Configuration.getInstance().load(context, context?.getSharedPreferences("osmdroid", 0))
        
        arguments?.let {
            initialLatitude = it.getDouble("latitude", 0.0)
            initialLongitude = it.getDouble("longitude", 0.0)
            initialRadiusMeters = it.getDouble("radius", 100.0)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_map_picker, container, false)
        
        // Setup map
        mapView = view.findViewById(R.id.map_view)
        mapView?.setTileSource(TileSourceFactory.MAPNIK)
        mapView?.setMultiTouchControls(true)
        
        mapController = mapView?.controller
        mapController?.setZoom(15.0)
        
        // Set initial location
        val location = if (initialLatitude != 0.0 || initialLongitude != 0.0) {
            GeoPoint(initialLatitude, initialLongitude)
        } else {
            // Default to a reasonable location (San Francisco)
            GeoPoint(37.7749, -122.4194)
        }
        
        selectedLocation = location
        mapController?.setCenter(location)
        updateMarker()
        updateRadiusCircle()
        
        // Setup click listener for map
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    selectedLocation = p
                    updateMarker()
                    updateRadiusCircle()
                    // Center map on tap location
                    mapController?.animateTo(p)
                }
                return true
            }
            
            override fun longPressHelper(p: GeoPoint?): Boolean {
                // Also allow long press
                if (p != null) {
                    selectedLocation = p
                    updateMarker()
                    updateRadiusCircle()
                    mapController?.animateTo(p)
                }
                return true
            }
        })
        mapView?.overlays?.add(0, mapEventsOverlay)
        
        // Setup radius slider
        radiusSlider = view.findViewById(R.id.radiusSlider)
        radiusText = view.findViewById(R.id.radiusText)
        
        val radiusValue = initialRadiusMeters.toInt().coerceIn(10, 5000)
        radiusSlider?.max = 4990  // 10 to 5000 meters
        radiusSlider?.progress = radiusValue - 10
        radiusText?.text = "${radiusValue}m"
        
        radiusSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress + 10
                radiusText?.text = "${radius}m"
                if (fromUser) {
                    updateRadiusCircle()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup buttons
        view.findViewById<Button>(R.id.selectButton).setOnClickListener {
            val finalLocation = selectedLocation ?: GeoPoint(initialLatitude, initialLongitude)
            val radius = (radiusSlider?.progress ?: 90) + 10.0
            listener?.onLocationSelected(finalLocation.latitude, finalLocation.longitude, radius)
            dismiss()
        }
        
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dismiss()
        }
        
        return view
    }
    
    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }
    
    private fun updateMarker() {
        val location = selectedLocation ?: return
        val map = mapView ?: return
        
        // Remove existing marker
        selectedMarker?.let { map.overlays.remove(it) }
        
        // Add new marker
        selectedMarker = Marker(map).apply {
            position = location
            title = "Selected Location"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(selectedMarker)
        map.invalidate()
        
        val radius = (radiusSlider?.progress ?: 90) + 10.0
        selectedMarker?.title = "Selected Location (${radius.toInt()}m radius)"
        selectedMarker?.snippet = "Lat: ${String.format("%.4f", location.latitude)}, Lon: ${String.format("%.4f", location.longitude)}"
    }
    
    private fun updateRadiusCircle() {
        val location = selectedLocation ?: return
        val map = mapView ?: return
        val radius = (radiusSlider?.progress ?: 90) + 10.0
        
        // Remove existing circle
        radiusCircle?.let { map.overlays.remove(it) }
        
        // Create circle using polygon with points around the circumference
        val circlePoints = mutableListOf<GeoPoint>()
        val numPoints = 60 // Number of points for smooth circle
        
        // Earth's radius in meters
        val earthRadius = 6371000.0
        
        for (i in 0 until numPoints) {
            val angle = 2.0 * Math.PI * i / numPoints
            // Convert radius in meters to degrees (approximate)
            val latOffset = radius * cos(angle) / earthRadius * (180.0 / Math.PI)
            val lonOffset = radius * sin(angle) / (earthRadius * cos(Math.toRadians(location.latitude))) * (180.0 / Math.PI)
            
            circlePoints.add(GeoPoint(
                location.latitude + latOffset,
                location.longitude + lonOffset
            ))
        }
        
        // Close the circle
        circlePoints.add(circlePoints[0])
        
        // Create and style the circle
        radiusCircle = Polygon().apply {
            points = circlePoints
            fillColor = 0x33FF0000.toInt() // Semi-transparent red
            setStrokeColor(0xFFFF0000.toInt()) // Solid red
            setStrokeWidth(3f)
        }
        
        map.overlays.add(radiusCircle)
        map.invalidate()
    }
    
    fun setOnLocationSelectedListener(listener: OnLocationSelectedListener) {
        this.listener = listener
    }
}
