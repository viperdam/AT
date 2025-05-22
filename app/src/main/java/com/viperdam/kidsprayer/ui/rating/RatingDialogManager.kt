package com.viperdam.kidsprayer.ui.rating

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.util.Date

/**
 * Manages the app rating dialog logic including:
 * - When to show the rating prompt based on app usage
 * - Tracking when users have rated or dismissed the prompt
 * - Storing user feedback for low ratings
 */
class RatingDialogManager(private val activity: AppCompatActivity) {
    
    companion object {
        private const val PREFS_NAME = "app_rating_prefs"
        private const val KEY_APP_LAUNCH_COUNT = "app_launch_count"
        private const val KEY_PRAYER_COMPLETION_COUNT = "prayer_completion_count"
        private const val KEY_HAS_RATED_APP = "has_rated_app"
        private const val KEY_LAST_RATING_PROMPT = "last_rating_prompt"
        private const val KEY_DISMISSED_COUNT = "dismissed_count"
        private const val KEY_RATED_VERSION = "rated_version"
        
        // Configuration constants
        private const val LAUNCHES_UNTIL_PROMPT = 5
        private const val PRAYER_COMPLETIONS_UNTIL_PROMPT = 3
        private const val MIN_DAYS_BETWEEN_PROMPTS = 7L
        private const val MAX_DISMISS_COUNT = 3
    }
    
    private val preferences: SharedPreferences by lazy {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Tracks each app launch
     */
    fun incrementAppLaunchCount() {
        val currentCount = preferences.getInt(KEY_APP_LAUNCH_COUNT, 0)
        preferences.edit().putInt(KEY_APP_LAUNCH_COUNT, currentCount + 1).apply()
        Log.d("RatingManager", "App launch count: ${currentCount + 1}")
    }
    
    /**
     * Tracks prayer completions (call after user completes a prayer)
     */
    fun incrementPrayerCompletionCount() {
        val currentCount = preferences.getInt(KEY_PRAYER_COMPLETION_COUNT, 0)
        preferences.edit().putInt(KEY_PRAYER_COMPLETION_COUNT, currentCount + 1).apply()
        Log.d("RatingManager", "Prayer completion count: ${currentCount + 1}")
    }
    
    /**
     * Records that the user has dismissed the rating prompt
     */
    fun recordPromptDismissed() {
        val currentCount = preferences.getInt(KEY_DISMISSED_COUNT, 0)
        preferences.edit().putInt(KEY_DISMISSED_COUNT, currentCount + 1).apply()
        preferences.edit().putLong(KEY_LAST_RATING_PROMPT, Date().time).apply()
        Log.d("RatingManager", "Prompt dismissed (count: ${currentCount + 1})")
    }
    
    /**
     * Records that the user has rated the app
     */
    fun markAsRated() {
        preferences.edit()
            .putBoolean(KEY_HAS_RATED_APP, true)
            .putString(KEY_RATED_VERSION, getAppVersionName())
            .apply()
        Log.d("RatingManager", "App rated")
    }
    
    /**
     * Records user feedback for low ratings
     */
    fun recordFeedback(feedback: String, rating: Int) {
        // We don't store feedback in SharedPreferences as it could be long
        // Instead, send it to a server or analytics platform
        // This method can be expanded based on your feedback storage needs
        Log.d("RatingManager", "User feedback received: $rating stars - $feedback")
    }
    
    /**
     * Determines if it's a good time to show the rating prompt
     * based on app usage and rating history
     */
    fun shouldShowRatingPrompt(): Boolean {
        // Don't show if user has already rated the current version
        if (hasRatedCurrentVersion()) {
            Log.d("RatingManager", "Not showing: user already rated this version")
            return false
        }
        
        // Don't show if user has dismissed too many times
        val dismissCount = preferences.getInt(KEY_DISMISSED_COUNT, 0)
        if (dismissCount >= MAX_DISMISS_COUNT) {
            Log.d("RatingManager", "Not showing: user dismissed $dismissCount times")
            return false
        }
        
        // Don't show too frequently
        val lastPromptTime = preferences.getLong(KEY_LAST_RATING_PROMPT, 0)
        val daysSinceLastPrompt = daysBetween(lastPromptTime, Date().time)
        if (lastPromptTime > 0 && daysSinceLastPrompt < MIN_DAYS_BETWEEN_PROMPTS) {
            Log.d("RatingManager", "Not showing: last shown $daysSinceLastPrompt days ago")
            return false
        }
        
        // Check if we have enough launches or prayer completions
        val launchCount = preferences.getInt(KEY_APP_LAUNCH_COUNT, 0)
        val prayerCount = preferences.getInt(KEY_PRAYER_COMPLETION_COUNT, 0)
        
        val shouldShow = launchCount >= LAUNCHES_UNTIL_PROMPT || 
                        prayerCount >= PRAYER_COMPLETIONS_UNTIL_PROMPT
        
        Log.d("RatingManager", "Rating prompt should show: $shouldShow " +
                "(launches: $launchCount, prayers: $prayerCount)")
        
        return shouldShow
    }
    
    /**
     * Show the rating dialog if conditions are right
     */
    fun showRatingDialogIfNeeded() {
        if (shouldShowRatingPrompt()) {
            RatingDialog(activity).show()
            // Record that we showed the dialog
            preferences.edit().putLong(KEY_LAST_RATING_PROMPT, Date().time).apply()
        }
    }
    
    /**
     * Reset all rating preferences (primarily for testing)
     */
    fun resetRatingPreferences() {
        preferences.edit().clear().apply()
        Log.d("RatingManager", "Rating preferences reset")
    }
    
    /**
     * Check if the user has rated the current version of the app
     */
    private fun hasRatedCurrentVersion(): Boolean {
        val hasRated = preferences.getBoolean(KEY_HAS_RATED_APP, false)
        if (!hasRated) return false
        
        val ratedVersion = preferences.getString(KEY_RATED_VERSION, "")
        val currentVersion = getAppVersionName()
        
        // If the app version has changed, we can ask for a new rating
        return ratedVersion == currentVersion
    }
    
    /**
     * Get the current app version name
     */
    private fun getAppVersionName(): String {
        return try {
            val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e("RatingManager", "Error getting app version", e)
            "unknown"
        }
    }
    
    /**
     * Calculate days between two timestamps
     */
    private fun daysBetween(first: Long, second: Long): Long {
        return (second - first) / (1000 * 60 * 60 * 24)
    }
} 