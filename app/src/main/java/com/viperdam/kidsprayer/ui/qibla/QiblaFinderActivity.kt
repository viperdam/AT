package com.viperdam.kidsprayer.ui.qibla

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.databinding.ActivityQiblaFinderBinding
import com.viperdam.kidsprayer.prayer.LocationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.acos
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView

@AndroidEntryPoint
class QiblaFinderActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityQiblaFinderBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var qiblaArrow: ImageView
    private lateinit var qiblaAngleText: TextView
    private lateinit var locationText: TextView
    private lateinit var sunIndicator: ImageView
    private lateinit var moonIndicator: ImageView
    private lateinit var kaabaIndicator: ImageView
    private lateinit var compassBaseAnimation: LottieAnimationView

    @Inject
    lateinit var locationManager: LocationManager

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // Low-pass filter variables for more stability
    private val alphaFilter = 0.05f  // Reduced from 0.15f for smoother motion
    private var filteredAzimuth = 0f
    private var lastStableAzimuth = 0f
    private var isFirstReading = true

    private var currentDegree = 0f
    private var qiblaDirection = 0f
    private var sunAzimuth = 0f
    private var moonAzimuth = 0f
    private var locationAvailable = false
    private var userLocation: Location? = null
    
    // Smoothing window for azimuth reading - increased size for more stability
    private val azimuthWindow = FloatArray(20) { 0f }  // Increased from 10 to 20
    private var azimuthWindowIndex = 0
    
    // North transition handling
    private var northCrossingCount = 0
    private var lastQuadrant = 0

    private val KAABA_LATITUDE = 21.4225
    private val KAABA_LONGITUDE = 39.8262

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQiblaFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add this new section for back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.qibla_finder)

        // Initialize views
        qiblaArrow = binding.qiblaArrow
        qiblaAngleText = binding.qiblaAngle
        locationText = binding.locationStatus
        sunIndicator = binding.sunIndicator
        moonIndicator = binding.moonIndicator
        kaabaIndicator = binding.kaabaIndicator
        compassBaseAnimation = binding.compassBaseAnimation
        
        // Apply visual enhancements
        applyVisualEnhancements()

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null || magnetometer == null) {
            Toast.makeText(this, R.string.sensors_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Get location and calculate Qibla direction
        getLocationAndCalculateQibla()

        // Setup refresh button with enhanced click animation
        binding.refreshButton.setOnClickListener {
            // Add subtle animation for button press
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                getLocationAndCalculateQibla()
            }.start()
        }
        
        // Setup calibrate button with enhanced feedback
        binding.calibrateButton.setOnClickListener {
            // Add subtle animation for button press
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                
                // Show pulsing animation on compass to indicate calibration
                binding.compassBackground.animate()
                    .alpha(0.7f).setDuration(300)
                    .withEndAction {
                        binding.compassBackground.animate().alpha(1f).setDuration(300).start()
                    }.start()
                
                Toast.makeText(this, R.string.calibrating_compass, Toast.LENGTH_SHORT).show()
                // Reset filters and window
                filteredAzimuth = 0f
                lastStableAzimuth = 0f
                isFirstReading = true
                for (i in azimuthWindow.indices) {
                    azimuthWindow[i] = 0f
                }
                azimuthWindowIndex = 0
            }.start()
        }
    }
    
    private fun applyVisualEnhancements() {
        try {
            // Add subtle shadow to compass for 3D effect
            binding.compassCard.elevation = 8f // Directly set 8dp elevation
            
            // Add subtle animations when the compass first appears
            binding.compassBackground.alpha = 0f
            binding.compassBackground.animate().alpha(1f).setDuration(500).start()
            
            // Configure the Lottie animation for the compass base
            compassBaseAnimation.speed = 0.5f
            compassBaseAnimation.alpha = 0.7f
            
            // Add subtle scale animation for Qibla arrow
            qiblaArrow.scaleX = 0.8f
            qiblaArrow.scaleY = 0.8f
            qiblaArrow.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
            
            // Add subtle entrance animation for celestial indicators
            sunIndicator.alpha = 0f
            moonIndicator.alpha = 0f
            kaabaIndicator.alpha = 0f
            
            sunIndicator.animate().alpha(1f).setStartDelay(300).setDuration(400).start()
            moonIndicator.animate().alpha(1f).setStartDelay(400).setDuration(400).start()
            kaabaIndicator.animate().alpha(1f).setStartDelay(500).setDuration(400).start()
            
            // Add subtle entrance animation for instruction text
            binding.compassInstructions.alpha = 0f
            binding.compassInstructions.animate().alpha(1f).setStartDelay(600).setDuration(500).start()
        } catch (e: Exception) {
            // Silently handle any animation errors
            e.printStackTrace()
        }
    }

    private fun getLocationAndCalculateQibla() {
        binding.progressBar.visibility = View.VISIBLE
        locationText.text = getString(R.string.fetching_location)
        qiblaAngleText.text = ""
        locationAvailable = false

        lifecycleScope.launch {
            try {
                // Try to get fresh location for more accuracy
                val location = locationManager.getLastLocation()
                if (location != null) {
                    userLocation = location
                    
                    // Calculate Qibla direction with the new location
                    calculateQiblaDirection(location)
                    
                    // Calculate celestial positions
                    calculateCelestialPositions(location)
                    
                    // Update UI with location info
                    locationText.text = getString(
                        R.string.location_found,
                        location.latitude.toString().take(7),
                        location.longitude.toString().take(7)
                    )
                    
                    // Show calibration instructions
                    binding.compassInstructions.visibility = View.VISIBLE
                    
                    // Mark location as available to enable sensor processing
                    locationAvailable = true
                    
                    // Debug Qibla direction
                    println("DEBUG: Qibla direction calculated: $qiblaDirection° for location (${location.latitude}, ${location.longitude})")
                    
                } else {
                    locationText.text = getString(R.string.location_not_available)
                    binding.compassInstructions.visibility = View.GONE
                    Toast.makeText(
                        this@QiblaFinderActivity,
                        R.string.enable_location_for_qibla,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                locationText.text = getString(R.string.location_error)
                binding.compassInstructions.visibility = View.GONE
                Toast.makeText(
                    this@QiblaFinderActivity,
                    R.string.location_error_message,
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace() // Log the exception for debugging
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun calculateQiblaDirection(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude

        // Convert to radians
        val latRad = Math.toRadians(latitude)
        val longRad = Math.toRadians(longitude)
        val kaabaLatRad = Math.toRadians(KAABA_LATITUDE)
        val kaabaLongRad = Math.toRadians(KAABA_LONGITUDE)

        // Calculate Qibla direction using the Spherical Law of Cosines formula
        // This formula is more accurate for long distances
        val y = sin(kaabaLongRad - longRad)
        val x = cos(latRad) * tan(kaabaLatRad) - sin(latRad) * cos(kaabaLongRad - longRad)
        var qiblaRad = atan2(y, x)

        // Convert to degrees
        qiblaDirection = Math.toDegrees(qiblaRad).toFloat()
        if (qiblaDirection < 0) qiblaDirection += 360

        // Update UI with Qibla angle
        qiblaAngleText.text = getString(R.string.qibla_angle, qiblaDirection.toInt().toString())
        
        // Debug output
        println("DEBUG: Qibla calculation - lat: $latitude, long: $longitude, qibla: $qiblaDirection")
    }

    private fun calculateCelestialPositions(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        
        // Calculate Sun Position
        sunAzimuth = calculateSunAzimuth(latitude, longitude, calendar).toFloat()
        
        // Calculate Moon Position
        moonAzimuth = calculateMoonAzimuth(latitude, longitude, calendar).toFloat()
        
        // Update UI with celestial positions
        binding.sunPositionText.text = getString(R.string.sun_position, sunAzimuth.toInt().toString())
        binding.moonPositionText.text = getString(R.string.moon_position, moonAzimuth.toInt().toString())
    }
    
    private fun calculateSunAzimuth(latitude: Double, longitude: Double, calendar: Calendar): Double {
        // Get the day of year (0-365)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        
        // Get the local time in hours (0-24)
        val hours = calendar.get(Calendar.HOUR_OF_DAY).toDouble() +
                    calendar.get(Calendar.MINUTE).toDouble() / 60.0
        
        // Convert latitude and longitude to radians
        val latRad = Math.toRadians(latitude)
        
        // Calculate fractional year
        val gamma = 2 * PI * (dayOfYear - 1 + (hours - 12) / 24) / 365
        
        // Calculate equation of time (in minutes)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                       0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        
        // Calculate solar declination (in radians)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                   0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                   0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        
        // Calculate true solar time (in minutes)
        val timeOffset = eqTime + 4 * longitude - 60 * calendar.get(Calendar.ZONE_OFFSET) / 60000
        val trueSolarTime = (hours * 60 + timeOffset) % 1440
        
        // Calculate hour angle (in radians)
        var hourAngle = PI * ((trueSolarTime / 4) - 180) / 180
        if (hourAngle < -PI) hourAngle += 2 * PI
        if (hourAngle > PI) hourAngle -= 2 * PI
        
        // Calculate solar zenith and azimuth
        val solarZenith = acos(sin(latRad) * sin(decl) + cos(latRad) * cos(decl) * cos(hourAngle))
        var solarAzimuth = atan2(
            -sin(hourAngle),
            tan(decl) * cos(latRad) - sin(latRad) * cos(hourAngle)
        )
        
        // Convert azimuth from radians to degrees, measured clockwise from north
        solarAzimuth = Math.toDegrees(solarAzimuth) + 180
        
        return solarAzimuth
    }
    
    private fun calculateMoonAzimuth(latitude: Double, longitude: Double, calendar: Calendar): Double {
        // This is a simplified calculation for moon position
        // A more accurate calculation would require more complex astronomical calculations
        
        // Get the day of year (0-365)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        
        // Get the local time in hours (0-24)
        val hours = calendar.get(Calendar.HOUR_OF_DAY).toDouble() +
                    calendar.get(Calendar.MINUTE).toDouble() / 60.0
        
        // Moon has approximately 29.5 day cycle
        val moonAge = (dayOfYear % 29.5) / 29.5
        
        // The moon rises approximately 50 minutes later each day
        // So we offset the sun position by 50*moonAge minutes
        val moonTimeOffset = (50 * moonAge) / 60.0
        
        // Calculate sun position with offset
        val offsetCalendar = (calendar.clone() as Calendar)
        offsetCalendar.add(Calendar.MINUTE, (moonTimeOffset * 60).toInt())
        
        // Get sun position and add offset for moon
        val moonAzimuth = (calculateSunAzimuth(latitude, longitude, offsetCalendar) + 180 * moonAge) % 360
        
        return moonAzimuth
    }
    
    private fun acos(value: Double): Double {
        return Math.acos(Math.max(-1.0, Math.min(1.0, value)))
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        
        // Recalculate celestial positions if location is available
        if (userLocation != null) {
            calculateCelestialPositions(userLocation!!)
        } else {
            // Try to get location if not available
            lifecycleScope.launch {
                try {
                    val location = locationManager.getLastLocation()
                    if (location != null) {
                        userLocation = location
                        calculateQiblaDirection(location)
                        calculateCelestialPositions(location)
                        locationText.text = getString(
                            R.string.location_found,
                            location.latitude.toString().take(7),
                            location.longitude.toString().take(7)
                        )
                        binding.compassInstructions.visibility = View.VISIBLE
                        locationAvailable = true
                    }
                } catch (e: Exception) {
                    // Log but don't show error in onResume
                }
            }
        }
        
        // Set up post-layout listener to position indicators once the compass view is measured
        binding.compassCard.post {
            binding.sunIndicator.visibility = View.VISIBLE
            binding.moonIndicator.visibility = View.VISIBLE
            binding.kaabaIndicator.visibility = View.VISIBLE
            
            userLocation?.let {
                calculateCelestialPositions(it)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun addAzimuthToWindow(azimuth: Float): Float {
        // Add to window and increment index
        azimuthWindow[azimuthWindowIndex] = azimuth
        azimuthWindowIndex = (azimuthWindowIndex + 1) % azimuthWindow.size
        
        // Handle the case of crossing north (0/360 degrees)
        // This is a special case because the values jump between 0 and 360
        var hasNorthCrossing = false
        var minValue = 360f
        var maxValue = 0f
        
        // Find min and max to detect if we're crossing north
        for (value in azimuthWindow) {
            if (value == 0f && !isFirstReading) continue // Skip initial zeros
            if (value < minValue) minValue = value
            if (value > maxValue) maxValue = value
        }
        
        // If the difference is large and includes values near 0 and 360,
        // we're likely dealing with a north crossing
        if (maxValue - minValue > 180 && 
            ((minValue < 45 && maxValue > 315) || (maxValue < 45 && minValue > 315))) {
            hasNorthCrossing = true
        }
        
        // Calculate average differently if we have a north crossing
        if (hasNorthCrossing) {
            // Use a normalized approach to handle the discontinuity
            var sinSum = 0f
            var cosSum = 0f
            var count = 0
            
            for (value in azimuthWindow) {
                if (value == 0f && !isFirstReading) continue // Skip initial zeros
                
                // Convert to radians and use trigonometry to handle the discontinuity
                val angleRad = Math.toRadians(value.toDouble())
                sinSum += sin(angleRad).toFloat()
                cosSum += cos(angleRad).toFloat()
                count++
            }
            
            if (count > 0) {
                // Convert back to degrees
                val resultRad = Math.atan2(sinSum.toDouble(), cosSum.toDouble())
                val resultDeg = Math.toDegrees(resultRad).toFloat()
                return (resultDeg + 360) % 360
            }
        }
        
        // If we're not crossing north, use the regular averaging approach
        // Use the sortedArray approach to exclude outliers
        val sortedValues = azimuthWindow.sortedArray()
        
        // Skip initial zeros and use middle 60% of values
        var startIndex = 0
        while (startIndex < sortedValues.size && sortedValues[startIndex] == 0f && !isFirstReading) {
            startIndex++
        }
        
        val effectiveSize = sortedValues.size - startIndex
        val lower = startIndex + (effectiveSize * 0.2).toInt()
        val upper = startIndex + (effectiveSize * 0.8).toInt()
        
        var sum = 0f
        var count = 0
        
        for (i in lower until upper) {
            if (i < sortedValues.size) {
                sum += sortedValues[i]
                count++
            }
        }
        
        isFirstReading = false
        return if (count > 0) sum / count else azimuth
    }
    
    private fun applyLowPassFilter(input: Float, output: Float): Float {
        // If this is the first valid reading, don't apply the filter
        if (output == 0f) return input
        
        // Handle the case where input and output are on opposite sides of the north
        if (Math.abs(input - output) > 180) {
            // We're crossing the north direction
            // Adjust values to avoid the discontinuity
            val adjustedInput = if (input > 180) input - 360 else input
            val adjustedOutput = if (output > 180) output - 360 else output
            
            // Apply filter on adjusted values
            val filteredValue = adjustedOutput + alphaFilter * (adjustedInput - adjustedOutput)
            
            // Convert back to 0-360 range
            return (filteredValue + 360) % 360
        }
        
        // Normal case
        return output + alphaFilter * (input - output)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!locationAvailable) return

        // Only handle relevant sensor events
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            else -> return
        }

        try {
            // Make sure we have both sensor readings before proceeding
            if (accelerometerReading[0] == 0f && accelerometerReading[1] == 0f && accelerometerReading[2] == 0f) return
            if (magnetometerReading[0] == 0f && magnetometerReading[1] == 0f && magnetometerReading[2] == 0f) return
            
            // Calculate rotation matrix
            if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                return
            }
            
            // Get orientation angles
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
    
            // Convert azimuth from radians to degrees
            val rawAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val azimuthInDegrees = (rawAzimuth + 360) % 360
            
            // Apply advanced smoothing
            val smoothedAzimuth = addAzimuthToWindow(azimuthInDegrees)
            
            // Apply low-pass filter with north crossing handling
            val currentFilteredAzimuth = applyLowPassFilter(smoothedAzimuth, filteredAzimuth)
            
            // Detect and handle rapid changes (potential sensor glitches)
            val isStableReading = if (filteredAzimuth != 0f) {
                val diff = Math.abs(currentFilteredAzimuth - lastStableAzimuth)
                val adjustedDiff = Math.min(diff, 360 - diff) // Handle 0/360 crossing
                adjustedDiff < 20 // Limit maximum change per reading
            } else true
            
            // Update the filtered azimuth value
            filteredAzimuth = currentFilteredAzimuth
            
            // If reading is stable or it's the first reading, update lastStableAzimuth
            if (isStableReading || lastStableAzimuth == 0f) {
                lastStableAzimuth = filteredAzimuth
            } else {
                // Use a more gradual approach to unstable readings
                filteredAzimuth = lastStableAzimuth + (filteredAzimuth - lastStableAzimuth) * 0.3f
                
                // Handle 0/360 crossing in the stability adjustment
                if (Math.abs(filteredAzimuth - lastStableAzimuth) > 180) {
                    if (filteredAzimuth > lastStableAzimuth) {
                        filteredAzimuth -= 360
                    } else {
                        filteredAzimuth += 360
                    }
                    filteredAzimuth = (filteredAzimuth + 360) % 360
                }
            }

            // Rotate compass - use negative azimuth to keep north at top
            rotateCompass(filteredAzimuth)
            
            // Rotate Qibla arrow to point to Qibla with enhanced stability
            rotateQiblaArrow(filteredAzimuth)
            
            // Update direction text
            updateDirectionText(filteredAzimuth)
            
            // Update other indicators with smooth transitions
            updateCelestialPositionIndicators(filteredAzimuth)
            updateKaabaIndicator(filteredAzimuth)
        } 
        catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun rotateCompass(azimuth: Float) {
        // Rotate compass in opposite direction of azimuth to keep north at top
        val rotation = -azimuth
        
        // Create rotation animation
        val anim = RotateAnimation(
            -currentDegree,    // fromDegrees - use negative of previous value
            rotation,          // toDegrees
            Animation.RELATIVE_TO_SELF, 0.5f,  // pivotX
            Animation.RELATIVE_TO_SELF, 0.5f   // pivotY
        )
        
        // Configure animation with enhanced smoothness
        anim.duration = 350  // Increased from 250ms for smoother motion
        anim.interpolator = LinearInterpolator()
        anim.fillAfter = true
        
        // Start animation
        binding.compassBackground.startAnimation(anim)
        
        // Save current rotation for next animation
        currentDegree = azimuth
    }
    
    private fun rotateQiblaArrow(azimuth: Float) {
        // Calculate Qibla direction relative to current orientation
        val angle = qiblaDirection - azimuth
        
        // Create rotation animation with enhanced parameters
        val anim = RotateAnimation(
            0f,
            angle,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        
        // Configure animation for smoother experience
        anim.duration = 300  // Use a slight delay for a more natural feel
        anim.interpolator = LinearInterpolator()
        anim.fillAfter = true
        
        // Start animation
        binding.qiblaArrow.startAnimation(anim)
    }
    
    private fun updateDirectionText(azimuth: Float) {
        // Determine cardinal direction
        val direction = when {
            azimuth >= 337.5 || azimuth < 22.5 -> "N"
            azimuth >= 22.5 && azimuth < 67.5 -> "NE"
            azimuth >= 67.5 && azimuth < 112.5 -> "E"
            azimuth >= 112.5 && azimuth < 157.5 -> "SE"
            azimuth >= 157.5 && azimuth < 202.5 -> "S"
            azimuth >= 202.5 && azimuth < 247.5 -> "SW"
            azimuth >= 247.5 && azimuth < 292.5 -> "W"
            else -> "NW"
        }
        
        // Update text view
        binding.northDegreeText.text = String.format("%s %.1f°", direction, azimuth)
    }
    
    private fun updateKaabaIndicator(azimuthInDegrees: Float) {
        if (binding.compassCard.width == 0) return
        
        try {
            val frameLayout = binding.compassCard.getChildAt(0)
            if (frameLayout !is FrameLayout) return
            
            val centerX = frameLayout.width / 2f
            val centerY = frameLayout.height / 2f
            val compassRadius = (Math.min(frameLayout.width, frameLayout.height) / 2f) * 0.65f
            
            // Adjust angle based on current device orientation
            val kaabaAngle = (qiblaDirection - azimuthInDegrees) * PI.toFloat() / 180f
            
            // Calculate position
            val kaabaX = centerX + compassRadius * sin(kaabaAngle)
            val kaabaY = centerY - compassRadius * cos(kaabaAngle)
            
            val halfWidth = binding.kaabaIndicator.width / 2f
            val halfHeight = binding.kaabaIndicator.height / 2f
            
            // Update position
            binding.kaabaIndicator.translationX = kaabaX - halfWidth
            binding.kaabaIndicator.translationY = kaabaY - halfHeight
            binding.kaabaIndicator.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateCelestialPositionIndicators(azimuthInDegrees: Float) {
        if (binding.compassCard.width == 0) return
        
        try {
            val frameLayout = binding.compassCard.getChildAt(0)
            if (frameLayout !is FrameLayout) return
            
            val centerX = frameLayout.width / 2f
            val centerY = frameLayout.height / 2f
            val compassRadius = (Math.min(frameLayout.width, frameLayout.height) / 2f) * 0.6f
            
            // Adjust angles based on current device orientation
            val sunAngle = (sunAzimuth - azimuthInDegrees) * PI.toFloat() / 180f
            val moonAngle = (moonAzimuth - azimuthInDegrees) * PI.toFloat() / 180f
            
            // Calculate positions
            val sunX = centerX + compassRadius * sin(sunAngle)
            val sunY = centerY - compassRadius * cos(sunAngle)
            
            val moonX = centerX + compassRadius * sin(moonAngle)
            val moonY = centerY - compassRadius * cos(moonAngle)
            
            // Update positions
            binding.sunIndicator.translationX = sunX - binding.sunIndicator.width / 2f
            binding.sunIndicator.translationY = sunY - binding.sunIndicator.height / 2f
            
            binding.moonIndicator.translationX = moonX - binding.moonIndicator.width / 2f
            binding.moonIndicator.translationY = moonY - binding.moonIndicator.height / 2f
            
            binding.sunIndicator.visibility = View.VISIBLE
            binding.moonIndicator.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            when (accuracy) {
                SensorManager.SENSOR_STATUS_UNRELIABLE, 
                SensorManager.SENSOR_STATUS_NO_CONTACT -> {
                    binding.accuracyText.text = getString(R.string.compass_accuracy_low)
                    binding.accuracyText.setTextColor(getColor(R.color.colorError))
                }
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                    binding.accuracyText.text = getString(R.string.compass_accuracy_medium)
                    binding.accuracyText.setTextColor(getColor(R.color.colorWarning))
                }
                else -> {
                    binding.accuracyText.text = getString(R.string.compass_accuracy_high)
                    binding.accuracyText.setTextColor(getColor(R.color.colorSuccess))
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
} 