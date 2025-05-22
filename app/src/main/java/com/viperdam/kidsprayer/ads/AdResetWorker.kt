package com.viperdam.kidsprayer.ads

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.viperdam.kidsprayer.service.ChildWorkerFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Background worker that periodically resets the ad state to ensure
 * ads are always available and fresh.
 */
class AdResetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val adManager: AdManager
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AdResetWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing periodic ad reset")
            
            Log.d(TAG, "Force resetting ad state via worker.")
            adManager.forceResetAdState()
            
            // Clear any cached preferences that might be affecting ad display
            val prefs = applicationContext.getSharedPreferences("ad_manager_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                // Keep a record of when this reset happened
                putLong("last_worker_reset_time", System.currentTimeMillis())
                apply()
            }
            
            Log.d(TAG, "Ad reset completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during ad reset: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * Factory for creating the worker with Hilt dependency injection
     */
    @AssistedFactory
    interface Factory : ChildWorkerFactory {
        override fun create(appContext: Context, params: WorkerParameters): AdResetWorker
    }
}
