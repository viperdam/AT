package com.viperdam.kidsprayer.ads

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import com.viperdam.kidsprayer.PrayerApp

/**
 * WorkManager worker for handling ad refreshes on newer Android versions
 * Used instead of AlarmManager on Android 12+ due to exact alarm restrictions
 */
class AdRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val adManager: AdManager
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AdRefreshWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting ad refresh check")
            val adManager = (applicationContext as? PrayerApp)?.adManager
            
            // Check if lock screen is active
            val prefs = applicationContext.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
            val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
            
            if (!isLockScreenActive) {
                Log.d(TAG, "Lock screen not active, skipping ad refresh")
                return Result.success()
            }
            
            if (adManager != null && !adManager.isRewardedAdAvailable()) {
                Log.d(TAG, "No ad available and lock screen active, requesting preload.")
                adManager.preloadRewardedAd()
            } else {
                Log.d(TAG, "Ad already available or AdManager not found, skipping preload.")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during ad refresh check", e)
            Result.retry()
        }
    }
    
    /**
     * Factory for creating the worker with Hilt dependency injection
     */
    @AssistedFactory
    interface Factory : com.viperdam.kidsprayer.service.ChildWorkerFactory {
        override fun create(appContext: Context, params: WorkerParameters): AdRefreshWorker
    }
} 