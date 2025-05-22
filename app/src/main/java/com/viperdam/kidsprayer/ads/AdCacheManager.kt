package com.viperdam.kidsprayer.ads

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

/**
 * Manages ad cache timing and forces resets when ads have been cached too long,
 * even within the same day.
 */
@Singleton
class AdCacheManager @Inject constructor(
    private val context: Context,
    private val adManager: AdManager
) {
    companion object {
        private const val TAG = "AdCacheManager"
        private const val PREFS_NAME = "ad_cache_prefs"
        private const val KEY_LAST_FULL_RESET = "last_full_reset"
        
        // Reduce cache timeout from 4 hours to 30 minutes
        private const val CACHE_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
        
        // More frequent periodic resets (every 2 hours instead of 6)
        private const val PERIODIC_RESET_HOURS = 2 // Every 2 hours
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    
    /**
     * Check if the ad cache has expired and needs a reset
     */
    fun checkCacheTimeout() {
        try {
            val currentTime = System.currentTimeMillis()
            val lastResetTime = prefs.getLong(KEY_LAST_FULL_RESET, 0)
            val timeSinceReset = currentTime - lastResetTime
            
            if (lastResetTime == 0L) {
                // First run, initialize the reset time
                updateLastResetTime()
                Log.d(TAG, "Initialized ad cache timestamp")
                scheduleNextCheck()
                return
            }
            
            if (timeSinceReset >= CACHE_TIMEOUT_MS) {
                Log.d(TAG, "Ad cache timeout exceeded (${timeSinceReset/1000/60} minutes), forcing reset")
                forceAdReset("cache_timeout")
            } else {
                // Check if we should do a periodic reset based on hour of day
                checkPeriodicReset()
                
                // Schedule next check - check every 15 minutes
                scheduleNextCheck()
                
                Log.d(TAG, "Ad cache still valid, next check in 15 minutes. " +
                        "Time since reset: ${timeSinceReset/1000/60} minutes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache timeout: ${e.message}")
        }
    }
    
    /**
     * Check if we need to do a periodic reset based on the current hour
     */
    private fun checkPeriodicReset() {
        try {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            
            // Reset at periodic intervals (every 6 hours)
            if (currentHour % PERIODIC_RESET_HOURS == 0) {
                // Get the last reset hour
                val lastResetTime = prefs.getLong(KEY_LAST_FULL_RESET, 0)
                val lastResetCalendar = Calendar.getInstance().apply { timeInMillis = lastResetTime }
                val lastResetHour = lastResetCalendar.get(Calendar.HOUR_OF_DAY)
                
                // Only reset if we haven't already reset during this hour
                if (lastResetHour != currentHour) {
                    Log.d(TAG, "Performing periodic reset at hour $currentHour")
                    forceAdReset("periodic_reset")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic reset check: ${e.message}")
        }
    }
    
    /**
     * Schedule the next cache timeout check
     */
    private fun scheduleNextCheck() {
        // Cancel any existing check
        checkRunnable?.let { handler.removeCallbacks(it) }
        
        // Create new check runnable
        checkRunnable = Runnable {
            checkCacheTimeout()
        }
        
        // Schedule to run in 5 minutes (reduced from 15 minutes)
        checkRunnable?.let {
            handler.postDelayed(it, 5 * 60 * 1000L)
        }
    }
    
    /**
     * Force an ad reset and update the timestamp
     */
    private fun forceAdReset(reason: String) {
        try {
            Log.d(TAG, "Forcing ad reset due to: $reason")
            adManager.forceResetAdState()
            updateLastResetTime()
            
            // After reset, ensure we preload a new ad
            adManager.preloadRewardedAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error during forced ad reset: ${e.message}")
        }
    }
    
    /**
     * Update the last reset timestamp
     */
    private fun updateLastResetTime() {
        prefs.edit()
            .putLong(KEY_LAST_FULL_RESET, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Start the cache monitoring process
     */
    fun start() {
        Log.d(TAG, "Starting ad cache manager")
        // Check immediately, then schedule regular checks
        checkCacheTimeout()
    }
    
    /**
     * Stop the cache monitoring process
     */
    fun stop() {
        Log.d(TAG, "Stopping ad cache manager")
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
    }
} 