package com.viperdam.kidsprayer.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.viperdam.kidsprayer.util.PrayerSettingsManager

/**
 * Worker that periodically cleans up old event tracking data
 * to prevent memory bloat and improve performance.
 */
class EventCleanupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    companion object {
        private const val TAG = "EventCleanupWorker"
    }
    
    override fun doWork(): Result {
        try {
            Log.d(TAG, "Starting cleanup of old event tracking data")
            val settingsManager = PrayerSettingsManager.getInstance(applicationContext)
            
            // Clean up events
            settingsManager.cleanupOldEvents()
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up event tracking data", e)
            return Result.failure()
        }
    }
} 