package com.viperdam.kidsprayer.util

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import android.os.SystemClock

/**
 * Central manager for prayer settings that works across app processes.
 * Handles checking if features are enabled for specific prayers and
 * tracks events to prevent duplicates.
 */
class PrayerSettingsManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PrayerSettingsManager"
        private const val PREFS_NAME = "prayer_prefs"
        private const val EVENT_TRACKING_PREFS = "prayer_event_tracking"
        
        // Event types
        const val EVENT_ADHAN_PLAYED = "adhan_played"
        const val EVENT_LOCKSCREEN_SHOWN = "lockscreen_shown"
        const val EVENT_NOTIFICATION_SHOWN = "notification_shown"
        
        // Cache expiration time in milliseconds (5 seconds)
        private const val CACHE_EXPIRATION_TIME = 5000L
        
        // Shared instance for all processes
        @Volatile private var instance: PrayerSettingsManager? = null
        
        fun getInstance(context: Context): PrayerSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: PrayerSettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val eventTracking = context.getSharedPreferences(EVENT_TRACKING_PREFS, Context.MODE_PRIVATE)
    
    // Cache for settings to reduce disk I/O
    private val settingsCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    
    // Check if lock screen is enabled for specific prayer
    fun isLockEnabled(prayerName: String): Boolean {
        val cacheKey = "lock_${prayerName.lowercase()}"
        val cachedValue = checkCache(cacheKey)
        if (cachedValue != null) {
            Log.d(TAG, "Lock for $prayerName is ${if (cachedValue) "enabled" else "disabled"} (cached)")
            return cachedValue
        }
        
        val globalEnabled = settings.getBoolean("enable_lock", true)
        val prayerEnabled = settings.getBoolean("${prayerName.lowercase()}_lock", true)
        val enabled = globalEnabled && prayerEnabled
        
        // Cache the result
        updateCache(cacheKey, enabled)
        
        Log.d(TAG, "Lock for $prayerName is ${if (enabled) "enabled" else "disabled"}")
        return enabled
    }
    
    // Check if adhan is enabled for specific prayer
    fun isAdhanEnabled(prayerName: String): Boolean {
        val cacheKey = "adhan_${prayerName.lowercase()}"
        val cachedValue = checkCache(cacheKey)
        if (cachedValue != null) {
            Log.d(TAG, "Adhan for $prayerName is ${if (cachedValue) "enabled" else "disabled"} (cached)")
            return cachedValue
        }
        
        // Always read directly from SharedPreferences for consistency
        val globalEnabled = settings.getBoolean("enable_adhan", true)
        val prayerEnabled = settings.getBoolean("${prayerName.lowercase()}_adhan", false)
        val enabled = globalEnabled && prayerEnabled
        
        // Cache the result
        updateCache(cacheKey, enabled)
        
        Log.d(TAG, "Adhan for $prayerName is ${if (enabled) "enabled" else "disabled"}")
        return enabled
    }
    
    // Check if notifications are enabled for specific prayer
    fun isNotificationEnabled(prayerName: String): Boolean {
        val cacheKey = "notification_${prayerName.lowercase()}"
        val cachedValue = checkCache(cacheKey)
        if (cachedValue != null) {
            Log.d(TAG, "Notification for $prayerName is ${if (cachedValue) "enabled" else "disabled"} (cached)")
            return cachedValue
        }
        
        val globalEnabled = settings.getBoolean("notifications_enabled", true)
        val prayerEnabled = settings.getBoolean("${prayerName.lowercase()}_notification", false)
        val enabled = globalEnabled && prayerEnabled
        
        // Cache the result
        updateCache(cacheKey, enabled)
        
        Log.d(TAG, "Notification for $prayerName is ${if (enabled) "enabled" else "disabled"}")
        return enabled
    }
    
    // Check if advance notifications are enabled for specific prayer
    fun isAdvanceNotificationEnabled(prayerName: String): Boolean {
        val cacheKey = "advance_notification_${prayerName.lowercase()}"
        val cachedValue = checkCache(cacheKey)
        if (cachedValue != null) {
            return cachedValue
        }
        
        val globalEnabled = settings.getBoolean("enable_advance_notification", false)
        val prayerEnabled = settings.getBoolean("${prayerName.lowercase()}_advance_notification", false)
        val enabled = globalEnabled && prayerEnabled
        
        // Cache the result
        updateCache(cacheKey, enabled)
        
        return enabled
    }
    
    // Check if an event has already occurred (across processes)
    fun hasEventOccurred(eventType: String, prayerName: String, prayerTime: Long): Boolean {
        val key = "${eventType}_${prayerName}_${prayerTime}"
        val occurred = eventTracking.getBoolean(key, false)
        Log.d(TAG, "Checking if $eventType for $prayerName at $prayerTime has occurred: $occurred")
        return occurred
    }
    
    // Mark an event as having occurred (across processes)
    fun markEventOccurred(eventType: String, prayerName: String, prayerTime: Long) {
        val key = "${eventType}_${prayerName}_${prayerTime}"
        eventTracking.edit().putBoolean(key, true).apply()
        Log.d(TAG, "Marked $eventType for $prayerName at $prayerTime as occurred")
        
        // Schedule cleanup to prevent memory bloat
        cleanupOldEvents()
    }
    
    // Set a prayer-specific feature's enabled state
    fun setFeatureEnabled(prayerName: String, feature: String, enabled: Boolean) {
        settings.edit().putBoolean("${prayerName.lowercase()}_$feature", enabled).apply()
        
        // Invalidate cache for this setting
        val cacheKey = "${feature}_${prayerName.lowercase()}"
        settingsCache.remove(cacheKey)
        
        Log.d(TAG, "Set $feature for $prayerName to ${if (enabled) "enabled" else "disabled"}")
    }
    
    // Set a global feature's enabled state
    fun setGlobalFeatureEnabled(feature: String, enabled: Boolean) {
        val key = when (feature) {
            "adhan" -> "enable_adhan"
            "lock" -> "enable_lock"
            "notification" -> "notifications_enabled"
            "advance_notification" -> "enable_advance_notification"
            else -> feature
        }
        
        settings.edit().putBoolean(key, enabled).apply()
        
        // Invalidate all cache entries related to this feature
        clearCacheForFeature(feature)
        
        Log.d(TAG, "Set global $feature to ${if (enabled) "enabled" else "disabled"}")
    }
    
    // Clean up old event records to prevent memory bloat
    fun cleanupOldEvents() {
        // This is a potentially expensive operation, so we do it in a background thread
        Thread {
            try {
                val currentTime = System.currentTimeMillis()
                val cutoffTime = currentTime - (24 * 60 * 60 * 1000) // 24 hours ago
                
                val allEvents = eventTracking.all
                var removedCount = 0
                
                val editor = eventTracking.edit()
                
                for ((key, _) in allEvents) {
                    // Format is "eventType_prayerName_prayerTime"
                    val parts = key.split("_")
                    if (parts.size >= 3) {
                        val prayerTimeStr = parts.last()
                        try {
                            val prayerTime = prayerTimeStr.toLong()
                            if (prayerTime < cutoffTime) {
                                editor.remove(key)
                                removedCount++
                            }
                        } catch (e: NumberFormatException) {
                            // Not a valid timestamp, skip
                        }
                    }
                }
                
                if (removedCount > 0) {
                    editor.apply()
                    Log.d(TAG, "Cleaned up $removedCount old event records")
                } else {
                    Log.d(TAG, "No old event records to clean up")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up old events", e)
            }
        }.start()
    }
    
    // Clear all settings cache
    fun clearCache() {
        settingsCache.clear()
        Log.d(TAG, "Cleared settings cache")
    }
    
    // Clear cache for a specific feature
    private fun clearCacheForFeature(feature: String) {
        val keysToRemove = settingsCache.keys().toList().filter { it.startsWith("${feature}_") }
        keysToRemove.forEach { settingsCache.remove(it) }
        Log.d(TAG, "Cleared cache for feature: $feature (${keysToRemove.size} entries)")
    }
    
    // Check cache for a value, return null if expired or not found
    private fun checkCache(key: String): Boolean? {
        val cachedEntry = settingsCache[key] ?: return null
        val (value, timestamp) = cachedEntry
        
        val currentTime = SystemClock.elapsedRealtime()
        return if (currentTime - timestamp < CACHE_EXPIRATION_TIME) {
            value
        } else {
            // Expired
            settingsCache.remove(key)
            null
        }
    }
    
    // Update cache with a new value
    private fun updateCache(key: String, value: Boolean) {
        settingsCache[key] = Pair(value, SystemClock.elapsedRealtime())
    }
} 