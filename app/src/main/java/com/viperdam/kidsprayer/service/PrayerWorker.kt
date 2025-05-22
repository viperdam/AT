package com.viperdam.kidsprayer.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.util.Log
import com.viperdam.kidsprayer.prayer.LocationManager
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PrayerWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val prayerTimeMillis = inputData.getLong("PRAYER_TIME", 0L)
        val currentTimeMillis = System.currentTimeMillis()
        
        // Check if this is a targeted run for a specific prayer time
        if (prayerTimeMillis > 0) {
            // Only allow a 20 second tolerance for triggering the lockscreen
            if (Math.abs(currentTimeMillis - prayerTimeMillis) > 20000L) {
                Log.d("PrayerWorker", "Skipping work - not within tolerance window for prayer time")
                return Result.success()
            }
        } else {
            // For periodic work, check if we've run recently and avoid unnecessary calculations
            val lastRunPref = applicationContext.getSharedPreferences("prayer_worker_prefs", Context.MODE_PRIVATE)
            val lastRunTime = lastRunPref.getLong("last_successful_run", 0L)
            val hoursSinceLastRun = (currentTimeMillis - lastRunTime) / (60 * 60 * 1000)
            
            // Skip work if we ran in the last 30 minutes, unless it's near prayer time
            if (lastRunTime > 0 && (currentTimeMillis - lastRunTime) < 30 * 60 * 1000) {
                // Check if we're close to a known prayer time
                val nearPrayerTime = isNearPrayerTime()
                if (!nearPrayerTime) {
                    Log.d("PrayerWorker", "Skipping work - last run was ${(currentTimeMillis - lastRunTime) / 60000} minutes ago")
                    return Result.success()
                }
            }
        }
        
        Log.d("PrayerWorker", "Work started")
        return try {
            Log.d("PrayerWorker", "Executing periodic prayer task")
            val scheduler = PrayerScheduler(applicationContext)
            
            // Use runBlocking since this is a Worker and we need to wait for the result
            runBlocking {
                scheduler.checkAndUpdateSchedule()
            }
            
            // Update last run time
            applicationContext.getSharedPreferences("prayer_worker_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_successful_run", currentTimeMillis)
                .apply()
                
            Log.d("PrayerWorker", "Work completed successfully")
            Result.success()
        } catch(e: Exception) {
            Log.e("PrayerWorker", "Error during work execution", e)
            Result.retry()
        }
    }
    
    /**
     * Check if the current time is near a standard prayer time
     * This is a lightweight check to decide if we should perform a full calculation
     */
    private fun isNearPrayerTime(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        
        // Array of common prayer time hours (approximate)
        val prayerHours = intArrayOf(5, 12, 15, 18, 20)
        
        // Check if we're within 20 minutes of a common prayer hour
        for (prayerHour in prayerHours) {
            if (hour == prayerHour && minute < 20) return true
            if (hour == prayerHour - 1 && minute > 40) return true
        }
        
        return false
    }
}
