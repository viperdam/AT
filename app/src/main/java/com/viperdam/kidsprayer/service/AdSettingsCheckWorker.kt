package com.viperdam.kidsprayer.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.ui.lock.ads.LockScreenAds
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Worker that periodically checks and enforces child-directed ad settings
 * to ensure appropriate ads for children are displayed 24/7.
 */
class AdSettingsCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val adManager: AdManager
) : Worker(appContext, workerParams) {

    companion object {
        private const val TAG = "AdSettingsCheckWorker"
    }

    override fun doWork(): Result {
        Log.d(TAG, "Starting ad content rating check")
        
        try {
            // Apply G-rated content settings at global level
            enforceAdContentRatingG()
            
            // Trigger a refresh of ad state to ensure settings are applied to new ads
            refreshAdState()
            
            // Store timestamp of last successful enforcement
            applicationContext.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("child_directed_enforced", true)
                .putLong("last_enforced_time", System.currentTimeMillis())
                .apply()
                
            // Send broadcast to notify components about settings enforcement
            val intent = android.content.Intent("com.viperdam.kidsprayer.ENFORCE_CHILD_DIRECTED")
            applicationContext.sendBroadcast(intent)
            
            Log.d(TAG, "Successfully enforced ad content rating settings")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing ad content rating settings: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            return Result.retry()
        }
    }
    
    /**
     * Apply ad content rating settings at global configuration level
     */
    private fun enforceAdContentRatingG() {
        try {
            // Apply G-rated content settings
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
                
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            Log.d(TAG, "Ad settings enforced with MAX_AD_CONTENT_RATING_G")
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing ad settings: ${e.message}")
            throw e
        }
    }
    
    /**
     * Reset ad state to force new ad loads with the updated settings
     */
    private fun refreshAdState() {
        try {
            // Force reload ads with new settings
            adManager.forceResetAdState()
            
            // Preload a new rewarded ad with the enforced settings
            adManager.preloadRewardedAd()
            
            Log.d(TAG, "Ad state refreshed with child-directed settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing ad state: ${e.message}")
            throw e
        }
    }

    /**
     * Factory for creating the worker with Hilt dependency injection
     */
    @AssistedFactory
    interface Factory : ChildWorkerFactory {
        override fun create(appContext: Context, params: WorkerParameters): AdSettingsCheckWorker
    }
} 