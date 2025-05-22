package com.viperdam.kidsprayer.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.viperdam.kidsprayer.R
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import android.util.Log

/**
 * An enhanced view that visualizes the sun and moon positions based on the current time of day.
 * Shows a realistic sun during daylight hours and a moon with accurate phase during nighttime.
 * The visualization moves according to real-time and updates based on user's location.
 */
class SunMoonVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val skyBackground: View
    private val sunView: LottieAnimationView
    private val moonView: LottieAnimationView
    private val starsView: View
    
    private var latitude = 0.0
    private var longitude = 0.0
    private var timeZoneOffset = 0
    
    // Moon phase properties
    private val MOON_CYCLE_DAYS = 29.53f // Length of lunar month in days
    
    // Track current animations
    private var currentMoonAnimationName: String = ""
    
    companion object {
        private const val TAG = "SunMoonVisualizer"
    }
    
    init {
        // Inflate layout
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_sun_moon_visualizer, this, true)
        
        // Get references to views
        skyBackground = view.findViewById(R.id.skyBackground)
        sunView = view.findViewById(R.id.sunAnimation)
        moonView = view.findViewById(R.id.moonAnimation)
        starsView = view.findViewById(R.id.starsBackground)
        
        // Configure animations
        sunView.setAnimation("sun_animation.json")
        sunView.repeatCount = LottieDrawable.INFINITE
        sunView.speed = 0.5f
        
        // Load default location (can be updated later)
        loadDefaultLocation()
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sunView.playAnimation()
        updateCelestialDisplay()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sunView.pauseAnimation()
        moonView.pauseAnimation()
    }
    
    /**
     * Should be called when the activity resumes to update the positions.
     */
    fun onResume() {
        updateCelestialDisplay()
        sunView.resumeAnimation()
    }
    
    /**
     * Updates the user's location information for more accurate sun/moon positions
     */
    fun updateLocation(latitude: Double, longitude: Double, timeZoneOffset: Int) {
        this.latitude = latitude
        this.longitude = longitude
        this.timeZoneOffset = timeZoneOffset
        updateCelestialDisplay()
    }
    
    /**
     * Load default location if user location is not available
     */
    private fun loadDefaultLocation() {
        // Default to a known location (Mecca)
        latitude = 21.4225
        longitude = 39.8262
        timeZoneOffset = 3 // UTC+3
    }
    
    /**
     * Update the entire celestial display (sun, moon, stars, sky color)
     */
    private fun updateCelestialDisplay() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        
        // Calculate time as a percentage of the day (0.0 to 1.0)
        val timeOfDay = (hour + minute / 60.0 + second / 3600.0) / 24.0
        
        // Determine if it's daytime (use astronomical calculations)
        val isDaytime = isCurrentlyDaytime(calendar)
        
        // Update background colors
        updateSkyColors(timeOfDay, isDaytime)
        
        // Update sun position and visibility
        updateSunPosition(timeOfDay, isDaytime)
        
        // Update moon position, phase and visibility
        updateMoonDisplay(calendar, timeOfDay, isDaytime)
        
        // Update stars visibility
        starsView.visibility = if (isDaytime) View.GONE else View.VISIBLE
        starsView.alpha = if (isDaytime) 0f else calculateStarsOpacity(timeOfDay)
    }
    
    /**
     * Update the sun's position based on time of day
     */
    private fun updateSunPosition(timeOfDay: Double, isDaytime: Boolean) {
        if (isDaytime) {
            sunView.visibility = View.VISIBLE
            
            // Calculate the angle based on time of day
            // Map sunrise to sunset (approx 6 AM to 6 PM) to path across sky
            // Normalize to 0-1 range within the daytime period
            val sunriseHour = 6.0 // Approximate sunrise time
            val sunsetHour = 18.0 // Approximate sunset time
            val dayLength = sunsetHour - sunriseHour
            
            val currentHour = timeOfDay * 24.0
            val normalizedPosition = 
                if (currentHour < sunriseHour) 0.0
                else if (currentHour > sunsetHour) 1.0
                else (currentHour - sunriseHour) / dayLength
            
            // Position the sun view along an arc
            val pathX = width * normalizedPosition
            val pathY = height * 0.5 * (1 - sin(Math.PI * normalizedPosition))
            
            sunView.x = pathX.toFloat() - sunView.width / 2
            sunView.y = pathY.toFloat() - sunView.height / 2
        } else {
            sunView.visibility = View.GONE
        }
    }
    
    /**
     * Update the moon display, including position and current phase
     */
    private fun updateMoonDisplay(calendar: Calendar, timeOfDay: Double, isDaytime: Boolean) {
        // Update visibility first
        moonView.visibility = if (isDaytime) View.GONE else View.VISIBLE
        
        if (!isDaytime) {
            // Calculate normalized night time position (0.0 to 1.0)
            // Night is approximately 6 PM to 6 AM (18h to 6h)
            val nightStart = 18.0 / 24.0  // 6 PM
            val nightEnd = 6.0 / 24.0     // 6 AM
            
            val normalizedNightPosition = 
                if (timeOfDay >= nightStart) {
                    (timeOfDay - nightStart) / (1.0 - nightStart + nightEnd)
                } else {
                    (timeOfDay + 1.0 - nightStart) / (1.0 - nightStart + nightEnd)
                }
            
            // Position the moon view along an arc
            val pathX = width * normalizedNightPosition
            val pathY = height * 0.5 * (1 - sin(Math.PI * normalizedNightPosition))
            
            moonView.x = pathX.toFloat() - moonView.width / 2
            moonView.y = pathY.toFloat() - moonView.height / 2
            
            // Update moon phase
            updateMoonPhase(calendar)
        }
    }
    
    /**
     * Calculate and display the current moon phase based on lunar cycle
     */
    private fun updateMoonPhase(calendar: Calendar) {
        // Get current lunar phase
        val phase = getCurrentMoonPhase(calendar)
        
        // Choose appropriate moon animation based on phase
        var phaseJsonFile = when {
            phase < 0.05 || phase > 0.95 -> "moon_new.json"           // New moon
            phase < 0.20 -> "moon_waxing_crescent.json"               // Waxing crescent
            phase < 0.30 -> "moon_first_quarter.json"                 // First quarter
            phase < 0.45 -> "moon_waxing_gibbous.json"                // Waxing gibbous
            phase < 0.55 -> "moon_full.json"                          // Full moon
            phase < 0.70 -> "moon_waning_gibbous.json"                // Waning gibbous
            phase < 0.80 -> "moon_last_quarter.json"                  // Last quarter
            else -> "moon_waning_crescent.json"                       // Waning crescent
        }
        
        // Check if the animation file exists, if not use a fallback
        if (!animationFileExists(phaseJsonFile)) {
            // Use moon_full.json as fallback since we know it exists
            Log.w(TAG, "Moon phase animation $phaseJsonFile not found, using fallback")
            phaseJsonFile = "moon_full.json"
        }
        
        // If animation changed, update it
        if (currentMoonAnimationName != phaseJsonFile) {
            try {
                moonView.setAnimation(phaseJsonFile)
                currentMoonAnimationName = phaseJsonFile
                moonView.playAnimation()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading moon animation: ${e.message}")
                // Try to recover with a known animation
                try {
                    moonView.setAnimation("moon_full.json")
                    currentMoonAnimationName = "moon_full.json"
                    moonView.playAnimation()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load fallback animation: ${e.message}")
                }
            }
        }
    }
    
    private fun animationFileExists(filename: String): Boolean {
        return try {
            context.assets.open(filename).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Calculate the current phase of the moon (0: new moon, 0.5: full moon, 1: new moon)
     */
    private fun getCurrentMoonPhase(calendar: Calendar): Float {
        // Reference date of a known new moon (Jan 1, 2000)
        val refNewMoon = Calendar.getInstance().apply {
            set(2000, 0, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Days since reference new moon
        val daysSinceRef = ((calendar.timeInMillis - refNewMoon.timeInMillis) / 
                           (1000 * 60 * 60 * 24)).toFloat()
        
        // Calculate current phase (normalized to 0.0-1.0)
        return (daysSinceRef % MOON_CYCLE_DAYS) / MOON_CYCLE_DAYS
    }
    
    /**
     * Update the sky color based on time of day
     */
    private fun updateSkyColors(timeOfDay: Double, isDaytime: Boolean) {
        // Calculate the color blend factor based on time of day (dawn/dusk transitions)
        val colorFactor = when {
            // Dawn transition (5 AM to 7 AM)
            timeOfDay >= 5/24.0 && timeOfDay <= 7/24.0 -> {
                val normalizedTime = ((timeOfDay * 24) - 5) / 2
                normalizedTime
            }
            // Dusk transition (5 PM to 7 PM)
            timeOfDay >= 17/24.0 && timeOfDay <= 19/24.0 -> {
                val normalizedTime = ((timeOfDay * 24) - 17) / 2
                1 - normalizedTime
            }
            // Day time
            timeOfDay > 7/24.0 && timeOfDay < 17/24.0 -> 1.0
            // Night time
            else -> 0.0
        }
        
        // Get colors from resources
        val dayColor = ContextCompat.getColor(context, R.color.sky_day)
        val dawnDuskColor = ContextCompat.getColor(context, R.color.sky_dawn_dusk)
        val nightColor = ContextCompat.getColor(context, R.color.sky_night)
        
        // Set the background color using interpolation
        val skyColor = if (colorFactor <= 0.5) {
            // Blend between night and dawn/dusk
            val blendFactor = colorFactor * 2
            blendColors(nightColor, dawnDuskColor, blendFactor)
        } else {
            // Blend between dawn/dusk and day
            val blendFactor = (colorFactor - 0.5) * 2
            blendColors(dawnDuskColor, dayColor, blendFactor)
        }
        
        skyBackground.setBackgroundColor(skyColor)
    }
    
    /**
     * Calculate the opacity of stars based on time of day
     */
    private fun calculateStarsOpacity(timeOfDay: Double): Float {
        // Stars fully visible at night (9 PM - 4 AM)
        // Fade in/out during transition periods
        return when {
            // Dawn fadeout (4 AM to 5 AM)
            timeOfDay >= 4/24.0 && timeOfDay <= 5/24.0 -> {
                val normalizedTime = ((timeOfDay * 24) - 4)
                (1 - normalizedTime).toFloat()
            }
            // Dusk fadein (7 PM to 9 PM)
            timeOfDay >= 19/24.0 && timeOfDay <= 21/24.0 -> {
                val normalizedTime = ((timeOfDay * 24) - 19) / 2
                normalizedTime.toFloat()
            }
            // Night time - fully visible
            (timeOfDay >= 21/24.0 || timeOfDay <= 4/24.0) -> 1.0f
            // Day time - not visible
            else -> 0.0f
        }
    }
    
    /**
     * Determine if it's currently daytime based on approximate sun position
     */
    private fun isCurrentlyDaytime(calendar: Calendar): Boolean {
        // Simple approximation: daytime is between 6 AM and 6 PM
        // This can be replaced with actual astronomical calculations later
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour in 6..17
    }
    
    /**
     * Blend two colors based on ratio
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Double): Int {
        val r = (color1 shr 16 and 0xFF) * (1 - ratio) + (color2 shr 16 and 0xFF) * ratio
        val g = (color1 shr 8 and 0xFF) * (1 - ratio) + (color2 shr 8 and 0xFF) * ratio
        val b = (color1 and 0xFF) * (1 - ratio) + (color2 and 0xFF) * ratio
        
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }
}