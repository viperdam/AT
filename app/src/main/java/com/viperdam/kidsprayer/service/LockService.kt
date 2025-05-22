package com.viperdam.kidsprayer.service

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.PrayerApp
import com.viperdam.kidsprayer.state.LockScreenStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LockService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private lateinit var lockScreenManager: LockScreenStateManager
    private lateinit var unlockReceiver: BroadcastReceiver
    private lateinit var deviceStateResetReceiver: BroadcastReceiver
    private var currentActivity: Activity? = null

    companion object {
        private const val TAG = "LockService"
        private const val NOTIFICATION_CHANNEL_ID = "lock_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRY_ATTEMPTS = 3
        const val UNLOCK_COMPLETE_ACTION = "com.viperdam.kidsprayer.UNLOCK_COMPLETE"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, LockService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            currentActivity = activity
        }
        override fun onActivityStarted(activity: Activity) {
            currentActivity = activity
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        lockScreenManager = LockScreenStateManager.getInstance(this)
        
        // Register for activity lifecycle callbacks to track current activity
        (application as Application).registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        
        // Register for unlock ready broadcasts
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LockScreenStateManager.UNLOCK_READY_ACTION) {
                    val prayerName = intent.getStringExtra("prayer_name")
                    val unlockMethod = intent.getStringExtra("unlock_method")
                    if (prayerName != null && unlockMethod != null) {
                        handleUnlockReady(prayerName, unlockMethod)
                    }
                }
            }
        }
        
        // Use conditional registering based on SDK version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, IntentFilter(LockScreenStateManager.UNLOCK_READY_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, IntentFilter(LockScreenStateManager.UNLOCK_READY_ACTION))
        }

        // Register for device state reset broadcasts
        deviceStateResetReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.viperdam.kidsprayer.RESET_DEVICE_STATE") {
                    handleDeviceStateReset()
                }
            }
        }
        
        // Use conditional registering based on SDK version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceStateResetReceiver, IntentFilter("com.viperdam.kidsprayer.RESET_DEVICE_STATE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceStateResetReceiver, IntentFilter("com.viperdam.kidsprayer.RESET_DEVICE_STATE"))
        }
        
        try {
            createNotificationChannel()
            startForeground()
            Log.d(TAG, "Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        try {
            Log.d(TAG, "Service starting")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground()
            }
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            (application as Application).unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
            unregisterReceiver(unlockReceiver)
            unregisterReceiver(deviceStateResetReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
        Log.d(TAG, "Lock service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Prayer Lock Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for prayer lock service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        try {
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Prayer Protection Active")
                .setContentText("Monitoring prayer times")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            stopSelf()
        }
    }

    private fun handleUnlockReady(prayerName: String, unlockMethod: String) {
        val activity = currentActivity ?: return
        val app = application as PrayerApp
        
        // Check ad availability and ensure fresh ad
        ensureFreshAdAndUnlock(activity, prayerName)
    }
    
    private fun ensureFreshAdAndUnlock(activity: Activity, prayerName: String) {
        val app = application as PrayerApp

        // Check if ad is available and fresh
        if (!app.adManager.isRewardedAdAvailable()) {
            Log.d(TAG, "No fresh ad available for unlock, completing unlock without ad")

            // If ad is not available, just complete the unlock immediately.
            completeUnlock(prayerName)
        } else {
            // Ad is available and fresh, show it
            showAdForUnlock(activity, prayerName)
        }
    }
    
    private fun showAdForUnlock(activity: Activity, prayerName: String) {
        val app = application as PrayerApp
        
        // Set flag before showing ad to prevent reactivation during ad display
        getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_displaying_ad", true)
            .apply()
        
        // Update to use OnUserEarnedRewardListener -> NO, use internal callback
        // Set the callback within AdManager elsewhere if needed for LockService specific actions
        // For now, assume the ViewModel callback handles the finish logic.
        app.adManager.showRewardedAd(
            activity = activity,
            bypassRateLimit = false // Or true depending on desired logic for lock service unlocks
        )
    }

    private fun handleDeviceStateReset() {
        try {
            // Remove device admin
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
            
            if (devicePolicyManager.isAdminActive(componentName)) {
                devicePolicyManager.removeActiveAdmin(componentName)
            }

            // Stop lock task mode if active
            stopLockTask()

            // Request device admin privileges again
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for prayer lock screen functionality")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            Log.d(TAG, "Device state reset completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during device state reset", e)
        }
    }

    private fun completeUnlock(prayerName: String) {
        // Remove device admin
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
        
        try {
            if (devicePolicyManager.isAdminActive(componentName)) {
                devicePolicyManager.removeActiveAdmin(componentName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing device admin", e)
        }

        // Stop lock task mode if active
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock task", e)
        }

        // Notify completion
        sendBroadcast(Intent(UNLOCK_COMPLETE_ACTION).apply {
            putExtra("prayer_name", prayerName)
        })

        // Stop the service
        stopSelf()
    }

    private fun stopLockTask() {
        try {
            val activity = findActivity()
            if (activity != null) {
                activity.stopLockTask()
                Log.i(TAG, "Lock task mode stopped.")
            } else {
                Log.w(TAG, "Attempted to stop lock task, but activity was null.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock task mode", e)
        }
    }

    private fun findActivity(): Activity? {
        // First try to get the currently tracked activity
        currentActivity?.let { return it }
        
        // If that fails, try to find a running activity from the ActivityManager
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val appTasks = activityManager.appTasks
                for (task in appTasks) {
                    if (task.taskInfo != null) {
                        // This is just to find if there's any task, we can't directly get the Activity
                        Log.d(TAG, "Found app task: ${task.taskInfo.topActivity?.className}")
                        // Return the tracked activity if it exists
                        return currentActivity
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding activity", e)
        }
        
        // Last resort: if application is PrayerApp, try to get the top activity from it
        try {
            val app = application
            if (app is PrayerApp) {
                return app.getCurrentActivity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting activity from application", e)
        }
        
        return null
    }

    private fun startLockTask() {
        currentActivity?.let { activity ->
            try {
                if (!activity.isInMultiWindowMode) {
                    activity.startLockTask()
                } else {
                    // Add else branch since this might be used as an expression
                    Log.d(TAG, "In multi-window mode, skipping startLockTask")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting lock task", e)
            }
        }
    }

    private fun isLockTaskEnabled(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return activityManager.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
    }
}
