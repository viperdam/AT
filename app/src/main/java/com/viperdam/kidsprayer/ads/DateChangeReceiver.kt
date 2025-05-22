package com.viperdam.kidsprayer.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.viperdam.kidsprayer.PrayerApp
import java.util.Calendar

/**
 * BroadcastReceiver that listens for time-related changes to detect date changes
 * and reset the ad state accordingly.
 */
class DateChangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "DateChangeReceiver"
        private var lastDayOfYear = -1
        
        private fun getCurrentDay(): Int {
            return Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        }
        
        fun initialize(context: Context) {
            lastDayOfYear = getCurrentDay()
            Log.d(TAG, "DateChangeReceiver initialized with day: $lastDayOfYear")
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            Log.d(TAG, "Received action: $action")
            
            // Check if this is a time change related action
            if (action == Intent.ACTION_TIME_CHANGED || 
                action == Intent.ACTION_DATE_CHANGED || 
                action == Intent.ACTION_TIMEZONE_CHANGED ||
                action == Intent.ACTION_TIME_TICK) {
                
                val currentDay = getCurrentDay()
                
                if (lastDayOfYear != currentDay && lastDayOfYear != -1) {
                    Log.d(TAG, "Day changed from $lastDayOfYear to $currentDay, triggering ad reset")
                    
                    // Get AdManager from the application context and reset ads
                    val app = context.applicationContext as? PrayerApp
                    app?.adManager?.let { adManager ->
                        Log.d(TAG, "Date change detected, triggering ad state reset.")
                        // Reset ad state through AdManager
                        adManager.forceResetAdState()
                        
                        // Optional: Reschedule alarms or other date-sensitive tasks
                    }
                }
                
                // Always update the last day
                lastDayOfYear = currentDay
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling broadcast: ${e.message}", e)
        }
    }
} 