package com.viperdam.kidsprayer.prayer

import android.location.Location
import android.util.Log
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.time.TimeSourceManager
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class PrayerTimeCalculator {
    private val timeZone = TimeZone.getDefault()
    private var lastCalculationTime = 0L
    private val debugFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    
    // Cache for prayer times
    private var cachedPrayerTimes: List<Prayer> = emptyList()
    private var lastCalculationHash: String = ""
    
    // Threshold for cache validity (30 minutes)
    private val CACHE_VALIDITY_PERIOD = 30 * 60 * 1000L
    
    /**
     * Calculate prayer times for the current date and given location
     */
    fun calculatePrayerTimes(location: Location?): List<Prayer> {
        return calculatePrayerTimes(location, false)
    }
    
    /**
     * Calculate prayer times with option to force recalculation regardless of cache
     */
    fun calculatePrayerTimes(location: Location?, forceRecalculate: Boolean = false): List<Prayer> {
        if (location == null) {
            Log.e(TAG, "Location is null, cannot calculate prayer times")
            return emptyList()
        }

        // Get time from TimeSourceManager to ensure consistency
        val timeManager = TimeSourceManager.getInstance(com.viperdam.kidsprayer.PrayerApp.getInstance())
        val currentTime = timeManager.getCurrentTime()
        val today = Calendar.getInstance(timeZone)
        today.timeInMillis = currentTime
        
        // Generate a hash of the current date and location to check if recalculation is needed
        val dateForHash = "${today.get(Calendar.YEAR)}-${today.get(Calendar.MONTH)}-${today.get(Calendar.DAY_OF_MONTH)}"
        val locationForHash = "${location.latitude.toInt()},${location.longitude.toInt()}"
        val calculationHash = "$dateForHash|$locationForHash"
        
        // If we already calculated for this date and location, and it's recent enough, return cached result
        if (!forceRecalculate && 
            calculationHash == lastCalculationHash && 
            cachedPrayerTimes.isNotEmpty() &&
            currentTime - lastCalculationTime < CACHE_VALIDITY_PERIOD) {
            Log.d(TAG, "Using cached prayer times for $dateForHash at $locationForHash")
            return cachedPrayerTimes
        }
        
        // Generate new calculation
        Log.d(TAG, "Computing prayer times for ${debugFormatter.format(today.time)}")
        
        // Update last calculation time
        lastCalculationTime = currentTime
        
        val date = DateComponents(
            today.get(Calendar.YEAR), 
            today.get(Calendar.MONTH) + 1, 
            today.get(Calendar.DAY_OF_MONTH)
        )
        
        val coordinates = Coordinates(
            location.latitude,
            location.longitude
        )
        
        Log.d(TAG, "Calculating prayer times for date: ${date.year}-${date.month}-${date.day}")
        Log.d(TAG, "Location: lat=${coordinates.latitude}, lon=${coordinates.longitude}")
        
        // Use MUSLIM_WORLD_LEAGUE calculation method
        val params = CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters

        val prayerTimes = try {
            PrayerTimes(coordinates, date, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating prayer times", e)
            return emptyList()
        }

        // Convert epoch seconds to milliseconds and ensure timezone is correct
        fun convertToLocalTime(epochSeconds: Long): Long {
            return try {
                val calendar = Calendar.getInstance(timeZone)
                calendar.timeInMillis = epochSeconds * 1000
                calendar.timeInMillis
            } catch (e: Exception) {
                Log.e(TAG, "Error converting time", e)
                epochSeconds * 1000 // Fallback to simple conversion
            }
        }

        val prayers = try {
            listOf(
                Prayer(
                    name = "Fajr",
                    time = convertToLocalTime(prayerTimes.fajr.epochSeconds),
                    rakaatCount = 2
                ),
                Prayer(
                    name = "Dhuhr",
                    time = convertToLocalTime(prayerTimes.dhuhr.epochSeconds),
                    rakaatCount = 4
                ),
                Prayer(
                    name = "Asr",
                    time = convertToLocalTime(prayerTimes.asr.epochSeconds),
                    rakaatCount = 4
                ),
                Prayer(
                    name = "Maghrib",
                    time = convertToLocalTime(prayerTimes.maghrib.epochSeconds),
                    rakaatCount = 3
                ),
                Prayer(
                    name = "Isha",
                    time = convertToLocalTime(prayerTimes.isha.epochSeconds),
                    rakaatCount = 4
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating prayer list", e)
            return emptyList()
        }

        // Log calculated prayer times for debugging
        prayers.forEach { prayer ->
            Log.d(TAG, "${prayer.name}: ${debugFormatter.format(Date(prayer.time))}")
        }
        
        // Update cache
        cachedPrayerTimes = prayers
        lastCalculationHash = calculationHash

        return prayers
    }
    
    /**
     * Invalidate the prayer time cache
     * Should be called when time changes significantly
     */
    fun invalidateCache() {
        Log.d(TAG, "Invalidating prayer time cache")
        cachedPrayerTimes = emptyList()
        lastCalculationHash = ""
        lastCalculationTime = 0L
    }
    
    /**
     * Find the current prayer from a list of prayers
     */
    fun findCurrentPrayer(prayers: List<Prayer>, currentTime: Long): Prayer? {
        val timeManager = TimeSourceManager.getInstance(com.viperdam.kidsprayer.PrayerApp.getInstance())
        val now = timeManager.getCurrentTime()
        
        // First check if we're after the last prayer of the day
        val lastPrayer = prayers.maxByOrNull { it.time }
        if (lastPrayer != null && now > lastPrayer.time) {
            return lastPrayer
        }
        
        // Otherwise find the most recent prayer
        return prayers
            .filter { it.time <= now }
            .maxByOrNull { it.time }
    }
    
    /**
     * Find the next prayer from a list of prayers
     */
    fun findNextPrayer(prayers: List<Prayer>, currentTime: Long): Prayer? {
        val timeManager = TimeSourceManager.getInstance(com.viperdam.kidsprayer.PrayerApp.getInstance())
        val now = timeManager.getCurrentTime()
        
        return prayers
            .filter { it.time > now }
            .minByOrNull { it.time }
    }

    companion object {
        private const val TAG = "PrayerTimeCalculator"
    }
}
