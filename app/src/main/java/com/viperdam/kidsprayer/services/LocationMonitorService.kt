package com.viperdam.kidsprayer.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.receivers.LocationProviderChangedReceiver
import com.viperdam.kidsprayer.ui.LocationDialogActivity

class LocationMonitorService : Service() {
    private lateinit var locationManager: LocationManager
    private var isMonitoring = false
    private val locationProviderReceiver = LocationProviderChangedReceiver()
    private val handler = Handler(Looper.getMainLooper())
    private val sharedPrefs by lazy { getSharedPreferences("location_service_prefs", Context.MODE_PRIVATE) }

    // Runnable for periodic location checks
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkLocationStatus()
            // Schedule next check based on location status - hourly if disabled
            val nextCheckDelay = if (isLocationEnabled()) 5 * 60 * 1000L else 60 * 60 * 1000L // 5 min or 1 hour
            handler.postDelayed(this, nextCheckDelay)
        }
    }

    companion object {
        private const val TAG = "LocationMonitorService"
        private const val CHANNEL_ID = "location_monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val LAST_DIALOG_SHOWN_KEY = "last_dialog_shown_time"
        private const val DIALOG_INTERVAL = 60 * 60 * 1000L // 1 hour interval between dialogs
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        
        try {
            // Check for permissions before starting as foreground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 15+
                // On Android 15, we need to make sure we have both FOREGROUND_SERVICE_LOCATION
                // and one of the location permissions
                val hasForegroundPermission = ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                val hasLocationPermission = ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || 
                ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasForegroundPermission || !hasLocationPermission) {
                    Log.e(TAG, "Missing required permissions for foreground service with location type")
                    // We'll start in compatibility mode without location features
                    startForeground(NOTIFICATION_ID, createNotification())
                    // Notify user about permission issue
                    notifyPermissionIssue()
                    stopSelf() // Stop service since we can't function properly
                    return
                }
            }
            
            // Start as foreground service
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Register the receiver for location provider changes
            val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(locationProviderReceiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(locationProviderReceiver, filter)
                }
                Log.d(TAG, "LocationProviderChangedReceiver registered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering LocationProviderChangedReceiver", e)
            }

            // Start periodic location checking
            handler.post(checkRunnable)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}")
            stopSelf()
        }
    }

    private fun notifyPermissionIssue() {
        // This will be called when we have permission issues
        // We could show a notification to the user or broadcast an intent
        // that MainActivity can catch to show a proper UI for requesting permissions
        val intent = Intent("com.viperdam.kidsprayer.PERMISSION_REQUIRED").apply {
            putExtra("permission_type", "location_foreground_service")
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkLocationStatus() {
        if (!isLocationEnabled()) {
            val currentTime = System.currentTimeMillis()
            val lastDialogTime = sharedPrefs.getLong(LAST_DIALOG_SHOWN_KEY, 0L)

            // Always show dialog immediately when first detected
            Log.d(TAG, "Location disabled - showing dialog immediately")
            showLocationEnableDialog()
            sharedPrefs.edit().putLong(LAST_DIALOG_SHOWN_KEY, currentTime).apply()
        }
    }

    private fun showLocationEnableDialog() {
        val intent = Intent(this, LocationDialogActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors location services for prayer times"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                 notificationManager.createNotificationChannel(channel)
                 Log.d(TAG, "Service Notification channel created.")
            }
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.location_monitor_notification_text))
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Unregister the receiver
        try {
            unregisterReceiver(locationProviderReceiver)
            Log.d(TAG, "LocationProviderChangedReceiver unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering LocationProviderChangedReceiver", e)
        }
        
        // Remove callbacks to prevent leaks
        handler.removeCallbacks(checkRunnable)
        
        isMonitoring = false
    }
}
