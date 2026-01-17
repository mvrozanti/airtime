package com.nosmoke.timer.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nosmoke.timer.R
import com.nosmoke.timer.data.AnalyticsManager
import com.nosmoke.timer.data.StateManager
import kotlinx.coroutines.launch
import java.util.Calendar

class AnalyticsFragment : Fragment() {
    
    private lateinit var stateManager: StateManager
    private lateinit var analyticsManager: AnalyticsManager
    
    private lateinit var totalCountValue: TextView
    private lateinit var timeSinceLastValue: TextView
    private lateinit var bestGapValue: TextView
    private lateinit var todayCount: TextView
    private lateinit var weekCount: TextView
    private lateinit var weekAvg: TextView
    private lateinit var monthCount: TextView
    private lateinit var monthAvg: TextView
    private lateinit var hourlyHistogramContainer: LinearLayout
    private lateinit var gapsPerHourContainer: LinearLayout
    private lateinit var countsPerDayContainer: LinearLayout
    private lateinit var gapsPerDayContainer: LinearLayout
    private lateinit var placesContainer: LinearLayout
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_analytics, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        stateManager = StateManager(requireContext())
        analyticsManager = AnalyticsManager(requireContext())
        
        totalCountValue = view.findViewById(R.id.totalCountValue)
        timeSinceLastValue = view.findViewById(R.id.timeSinceLastValue)
        bestGapValue = view.findViewById(R.id.bestGapValue)
        todayCount = view.findViewById(R.id.todayCount)
        weekCount = view.findViewById(R.id.weekCount)
        weekAvg = view.findViewById(R.id.weekAvg)
        monthCount = view.findViewById(R.id.monthCount)
        monthAvg = view.findViewById(R.id.monthAvg)
        hourlyHistogramContainer = view.findViewById(R.id.hourlyHistogramContainer)
        gapsPerHourContainer = view.findViewById(R.id.gapsPerHourContainer)
        countsPerDayContainer = view.findViewById(R.id.countsPerDayContainer)
        gapsPerDayContainer = view.findViewById(R.id.gapsPerDayContainer)
        placesContainer = view.findViewById(R.id.placesContainer)
        
        loadAnalytics()
    }
    
    override fun onResume() {
        super.onResume()
        loadAnalytics()
        startPeriodicUpdate()
    }
    
    override fun onPause() {
        super.onPause()
        stopPeriodicUpdate()
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            // Total count
            val total = analyticsManager.getTotalCount()
            totalCountValue.text = total.toString()
            
            // Time since last
            val timeSinceLast = analyticsManager.getTimeSinceLastSmoke()
            timeSinceLastValue.text = if (timeSinceLast != null) {
                analyticsManager.formatDuration(timeSinceLast)
            } else {
                "N/A"
            }
            
            // Best gap (longest streak)
            val bestGap = analyticsManager.getLongestStreak()
            bestGapValue.text = if (bestGap > 0) {
                analyticsManager.formatDuration(bestGap)
            } else {
                "N/A"
            }
            
            // Period stats
            val todayCountVal = analyticsManager.getCountForPeriod(1)
            todayCount.text = todayCountVal.toString()
            
            val weekCountVal = analyticsManager.getCountForPeriod(7)
            weekCount.text = weekCountVal.toString()
            val weekAvgVal = analyticsManager.getAveragePerDay(7)
            weekAvg.text = "(${String.format("%.1f", weekAvgVal)}/day)"
            
            val monthCountVal = analyticsManager.getCountForPeriod(30)
            monthCount.text = monthCountVal.toString()
            val monthAvgVal = analyticsManager.getAveragePerDay(30)
            monthAvg.text = "(${String.format("%.1f", monthAvgVal)}/day)"
            
            // By place
            loadPlaceStats()
            
            // Hourly histogram
            loadHourlyHistogram()
            
            // Gaps per hour
            loadGapsPerHour()
            
            // Counts per day of week
            loadCountsPerDayOfWeek()
            
            // Gaps per day of week
            loadGapsPerDayOfWeek()
        }
    }
    
    private fun loadHourlyHistogram() {
        lifecycleScope.launch {
            hourlyHistogramContainer.removeAllViews()
            val hourCounts = analyticsManager.getCountByHourOfDay()
            val maxCount = hourCounts.maxOrNull() ?: 1
            
            if (maxCount == 0) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No hourly data yet"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                    setPadding(0, 8, 0, 8)
                }
                hourlyHistogramContainer.addView(emptyText)
                return@launch
            }
            
            val density = resources.displayMetrics.density
            
            // Create histogram bars
            hourCounts.forEachIndexed { hour, count ->
                val hourRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                // Hour label
                val hourLabel = TextView(requireContext()).apply {
                    text = String.format("%02d:00", hour)
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        width = (60 * density).toInt()
                    }
                }
                
                // Bar container
                val barContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    }
                }
                
                // Bar - calculate width as percentage
                val barWidth = if (maxCount > 0 && count > 0) {
                    // Calculate percentage and apply to available width (estimated 200dp max)
                    val maxBarWidth = 200 * density
                    val width = (count.toFloat() / maxCount * maxBarWidth).coerceAtLeast(4f * density)
                    width.toInt()
                } else {
                    0
                }
                
                val bar = View(requireContext()).apply {
                    if (barWidth > 0) {
                        val drawable = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setColor(ContextCompat.getColor(requireContext(), R.color.primary))
                            cornerRadius = 4f * density
                        }
                        background = drawable
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        barWidth,
                        (20 * density).toInt()
                    )
                    visibility = if (barWidth > 0) View.VISIBLE else View.GONE
                }
                
                // Count label
                val countLabel = TextView(requireContext()).apply {
                    text = count.toString()
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        width = (30 * density).toInt()
                    }
                    gravity = android.view.Gravity.END
                }
                
                barContainer.addView(bar)
                hourRow.addView(hourLabel)
                hourRow.addView(barContainer)
                hourRow.addView(countLabel)
                hourlyHistogramContainer.addView(hourRow)
            }
        }
    }
    
    private fun loadPlaceStats() {
        lifecycleScope.launch {
            placesContainer.removeAllViews()
            val countByPlace = analyticsManager.getCountByPlace()
            
            if (countByPlace.isEmpty()) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No place data yet"
                    textSize = 14f
                    setTextColor(requireContext().getColor(R.color.text_tertiary))
                    setPadding(0, 8, 0, 8)
                }
                placesContainer.addView(emptyText)
                return@launch
            }
            
            countByPlace.toList().sortedByDescending { it.second.second }.forEach { (_, data) ->
                val placeName = data.first
                val count = data.second
                
                val placeRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 12, 0, 12)
                }
                
                val nameText = TextView(requireContext()).apply {
                    text = placeName
                    textSize = 14f
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                
                val countText = TextView(requireContext()).apply {
                    text = count.toString()
                    textSize = 16f
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                
                placeRow.addView(nameText)
                placeRow.addView(countText)
                placesContainer.addView(placeRow)
            }
        }
    }
    
    private fun loadGapsPerHour() {
        lifecycleScope.launch {
            gapsPerHourContainer.removeAllViews()
            val gapsPerHour = analyticsManager.getGapsPerHour()
            
            if (gapsPerHour.all { it == null }) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No gap data yet"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                    setPadding(0, 8, 0, 8)
                }
                gapsPerHourContainer.addView(emptyText)
                return@launch
            }
            
            val density = resources.displayMetrics.density
            
            gapsPerHour.forEachIndexed { hour, avgGap ->
                if (avgGap != null) {
                    val hourRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    
                    val hourLabel = TextView(requireContext()).apply {
                        text = String.format("%02d:00", hour)
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            width = (60 * density).toInt()
                        }
                    }
                    
                    val gapText = TextView(requireContext()).apply {
                        text = analyticsManager.formatDuration(avgGap)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        }
                    }
                    
                    hourRow.addView(hourLabel)
                    hourRow.addView(gapText)
                    gapsPerHourContainer.addView(hourRow)
                }
            }
        }
    }
    
    private fun loadCountsPerDayOfWeek() {
        lifecycleScope.launch {
            countsPerDayContainer.removeAllViews()
            val countsPerDay = analyticsManager.getCountByDayOfWeek()
            val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            
            if (countsPerDay.all { it == 0 }) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No daily data yet"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                    setPadding(0, 8, 0, 8)
                }
                countsPerDayContainer.addView(emptyText)
                return@launch
            }
            
            val maxCount = countsPerDay.maxOrNull() ?: 1
            val density = resources.displayMetrics.density
            
            countsPerDay.forEachIndexed { dayIndex, count ->
                val dayRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                val dayLabel = TextView(requireContext()).apply {
                    text = dayNames[dayIndex]
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        width = (60 * density).toInt()
                    }
                }
                
                val barContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    }
                }
                
                val barWidth = if (maxCount > 0 && count > 0) {
                    val maxBarWidth = 200 * density
                    val width = (count.toFloat() / maxCount * maxBarWidth).coerceAtLeast(4f * density)
                    width.toInt()
                } else {
                    0
                }
                
                val bar = View(requireContext()).apply {
                    if (barWidth > 0) {
                        val drawable = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setColor(ContextCompat.getColor(requireContext(), R.color.primary))
                            cornerRadius = 4f * density
                        }
                        background = drawable
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        barWidth,
                        (20 * density).toInt()
                    )
                    visibility = if (barWidth > 0) View.VISIBLE else View.GONE
                }
                
                val countLabel = TextView(requireContext()).apply {
                    text = count.toString()
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        width = (30 * density).toInt()
                    }
                    gravity = android.view.Gravity.END
                }
                
                barContainer.addView(bar)
                dayRow.addView(dayLabel)
                dayRow.addView(barContainer)
                dayRow.addView(countLabel)
                countsPerDayContainer.addView(dayRow)
            }
        }
    }
    
    private fun loadGapsPerDayOfWeek() {
        lifecycleScope.launch {
            gapsPerDayContainer.removeAllViews()
            val gapsPerDay = analyticsManager.getGapsPerDayOfWeek()
            val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            
            if (gapsPerDay.all { it == null }) {
                val emptyText = TextView(requireContext()).apply {
                    text = "No gap data yet"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                    setPadding(0, 8, 0, 8)
                }
                gapsPerDayContainer.addView(emptyText)
                return@launch
            }
            
            val density = resources.displayMetrics.density
            
            gapsPerDay.forEachIndexed { dayIndex, avgGap ->
                if (avgGap != null) {
                    val dayRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    
                    val dayLabel = TextView(requireContext()).apply {
                        text = dayNames[dayIndex]
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            width = (60 * density).toInt()
                        }
                    }
                    
                    val gapText = TextView(requireContext()).apply {
                        text = analyticsManager.formatDuration(avgGap)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        }
                    }
                    
                    dayRow.addView(dayLabel)
                    dayRow.addView(gapText)
                    gapsPerDayContainer.addView(dayRow)
                }
            }
        }
    }
    
    private fun startPeriodicUpdate() {
        stopPeriodicUpdate()
        var runnable: Runnable? = null
        runnable = Runnable {
            lifecycleScope.launch {
                val timeSinceLast = analyticsManager.getTimeSinceLastSmoke()
                timeSinceLastValue.text = if (timeSinceLast != null) {
                    analyticsManager.formatDuration(timeSinceLast)
                } else {
                    "N/A"
                }
                runnable?.let { updateHandler.postDelayed(it, 1000) }
            }
        }
        updateRunnable = runnable
        updateHandler.postDelayed(runnable, 1000)
    }
    
    private fun stopPeriodicUpdate() {
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null
    }
}
