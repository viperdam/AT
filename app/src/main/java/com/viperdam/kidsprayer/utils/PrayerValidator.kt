package com.viperdam.kidsprayer.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * Utility class for validating and managing prayer data consistently across the app.
 * This provides a central place for all prayer data validation logic.
 */
class PrayerValidator {
    companion object {
        private const val TAG = "PrayerValidator"
        private const val PRAYER_RECEIVER_PREFS = "prayer_receiver_prefs"
        private val VALID_PRAYER_NAMES = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha", "Test Prayer")
        
        // Standard prayer names for consistency
        const val PRAYER_FAJR = "Fajr"
        const val PRAYER_DHUHR = "Dhuhr"
        const val PRAYER_ASR = "Asr"
        const val PRAYER_MAGHRIB = "Maghrib"
        const val PRAYER_ISHA = "Isha"
        const val PRAYER_TEST = "Test Prayer"
        const val PRAYER_UNKNOWN = "Unknown Prayer" // Replacement for "Prayer Time"
        
        /**
         * Validates if a prayer name is valid and appropriate for display/processing
         * @param prayerName The prayer name to validate
         * @return true if the prayer name is valid, false otherwise
         */
        fun isValidPrayerName(prayerName: String?): Boolean {
            if (prayerName.isNullOrEmpty()) return false
            if (prayerName == PRAYER_UNKNOWN) return false
            
            // Instead of rejecting "Prayer Time", map it to a valid prayer
            if (prayerName == "Prayer Time") {
                return true // Consider it valid to prevent constant recovery attempts
            }
            
            // Check against known valid prayer names
            return VALID_PRAYER_NAMES.contains(prayerName)
        }
        
        /**
         * Gets a standardized prayer name (fixes casing, etc.)
         * @param prayerName The input prayer name
         * @return A standardized prayer name or PRAYER_UNKNOWN if invalid
         */
        fun getStandardPrayerName(prayerName: String?): String {
            if (prayerName.isNullOrEmpty()) return PRAYER_UNKNOWN
            
            // Normalize known prayer names
            return when (prayerName.lowercase()) {
                "fajr" -> PRAYER_FAJR
                "dhuhr" -> PRAYER_DHUHR
                "asr" -> PRAYER_ASR
                "maghrib" -> PRAYER_MAGHRIB
                "isha" -> PRAYER_ISHA
                "test prayer" -> PRAYER_TEST
                "prayer time" -> PRAYER_ISHA // Map "prayer time" to a valid prayer name (Isha)
                else -> prayerName
            }
        }
        
        /**
         * Validates an intent that will be used to launch the LockScreenActivity
         * @param intent The intent to validate
         * @return true if the intent contains valid prayer data, false otherwise
         */
        fun validateLockScreenIntent(intent: Intent): Boolean {
            val prayerName = intent.getStringExtra("prayer_name")
            return isValidPrayerName(prayerName)
        }
        
        /**
         * Enhance an intent with proper prayer data validation
         * @param intent The intent to enhance
         * @return The enhanced intent with validated prayer data
         */
        fun enhanceLockScreenIntent(intent: Intent): Intent {
            val prayerName = intent.getStringExtra("prayer_name")
            val standardName = getStandardPrayerName(prayerName)
            
            // Replace the prayer name with a standardized version
            if (standardName != prayerName) {
                intent.putExtra("prayer_name", standardName)
            }
            
            // Ensure we have a valid rakaat count
            if (!intent.hasExtra("rakaat_count") || intent.getIntExtra("rakaat_count", 0) <= 0) {
                val rakaatCount = getDefaultRakaatCount(standardName)
                intent.putExtra("rakaat_count", rakaatCount)
            }
            
            return intent
        }
        
        /**
         * Get the default rakaat count for a prayer
         * @param prayerName The name of the prayer
         * @return The default rakaat count for the prayer
         */
        fun getDefaultRakaatCount(prayerName: String): Int {
            return when (prayerName) {
                PRAYER_FAJR -> 2
                PRAYER_DHUHR -> 4
                PRAYER_ASR -> 4
                PRAYER_MAGHRIB -> 3
                PRAYER_ISHA -> 4
                PRAYER_TEST -> 4
                else -> 4 // Default to 4 for unknown prayers
            }
        }
        
        /**
         * Marks invalid prayer data for tracking and debugging purposes
         * @param reason The reason why the prayer data is invalid
         */
        fun markInvalidPrayerData(reason: String) {
            Log.w(TAG, "Invalid prayer data marked: $reason")
            // You could store this in Firebase Analytics or your own tracking system
            // for debugging and monitoring purposes
        }
        
        /**
         * Mark invalid prayer data with a custom cooldown duration
         */
        fun markInvalidPrayerData(context: Context, reason: String, cooldownDuration: Long = 60000L) {
            val prefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("invalid_prayer_data", true)
                .putLong("last_invalid_prayer_time", System.currentTimeMillis())
                .putLong("invalid_prayer_cooldown", cooldownDuration)
                .putString("last_invalid_reason", reason)
                .apply()
            
            Log.w(TAG, "Invalid prayer data marked: $reason")
        }
        
        /**
         * Check if we're in cooldown period for invalid prayer data
         */
        fun isInInvalidPrayerCooldown(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            val lastInvalidPrayerTime = prefs.getLong("last_invalid_prayer_time", 0)
            val cooldownDuration = prefs.getLong("invalid_prayer_cooldown", 60000L) // Default to 1 minute
            
            return prefs.getBoolean("invalid_prayer_data", false) && 
                   System.currentTimeMillis() - lastInvalidPrayerTime < cooldownDuration
        }
    }
} 