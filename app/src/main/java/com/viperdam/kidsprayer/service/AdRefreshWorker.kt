package com.viperdam.kidsprayer.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viperdam.kidsprayer.PrayerApp

class AdRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AdRefreshWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting ad refresh check")
            val adManager = (applicationContext as? PrayerApp)?.adManager
            if (adManager != null && !adManager.isRewardedAdAvailable()) {
                Log.d(TAG, "No ad available, requesting preload.")
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
}
