package com.viperdam.kidsprayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.service.PrayerReceiver

class VolumeButtonService : Service() {
    private val volumeButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                Log.d(TAG, "Volume button pressed, checking if adhan is playing")
                
                // More aggressive check - assume adhan is playing if this service is running
                val shouldStopAdhan = PrayerReceiver.isAdhanPlaying() || isServiceRunningForAdhan
                
                if (shouldStopAdhan) {
                    Log.d(TAG, "Volume button pressed while adhan playing, stopping Adhan immediately")
                    // Create and send stop intent immediately
                    context?.let {
                        val stopIntent = Intent(it, PrayerReceiver::class.java).apply {
                            action = PrayerReceiver.STOP_ADHAN_ACTION
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        it.sendBroadcast(stopIntent)
                        
                        // Also call stopAdhan directly to ensure it stops
                        PrayerReceiver.stopAdhan(it, "volume_button_service")
                    }
                } else {
                    Log.d(TAG, "Volume button pressed but adhan is not playing")
                    // Check again after a short delay in case isAdhanPlaying() is not updated yet
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (PrayerReceiver.isAdhanPlaying()) {
                            Log.d(TAG, "Adhan is playing after delay check, stopping now")
                            context?.let {
                                PrayerReceiver.stopAdhan(it, "volume_button_delayed")
                            }
                        } else {
                            // Keep the service running a bit longer before stopping
                            checkIfShouldStopService()
                        }
                    }, 500) // Short delay to recheck
                }
            }
        }
    }
    
    private val serviceStopHandler = Handler(Looper.getMainLooper())
    private val serviceStopRunnable = Runnable {
        Log.d(TAG, "Auto-stopping service after timeout")
        stopSelf()
    }
    
    // Flag to indicate service is running for adhan
    private var isServiceRunningForAdhan = true

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeButtonReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeButtonReceiver, filter)
        }
        startForeground()
        
        // Auto-stop service after MAX_SERVICE_DURATION to prevent unnecessary battery drain
        serviceStopHandler.postDelayed(serviceStopRunnable, MAX_SERVICE_DURATION)
        
        Log.d(TAG, "VolumeButtonService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Get information about whether adhan is enabled and should be monitored
        intent?.let {
            val prayerName = it.getStringExtra("prayer_name") ?: ""
            // Check adhan settings
            checkAdhanSettings(prayerName)
        }
        
        // Cancel any pending auto-stop and set a new one
        serviceStopHandler.removeCallbacks(serviceStopRunnable)
        serviceStopHandler.postDelayed(serviceStopRunnable, MAX_SERVICE_DURATION)
        return START_STICKY
    }
    
    private fun checkAdhanSettings(prayerName: String) {
        val prefs = getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        val isAdhanGloballyEnabled = prefs.getBoolean("enable_adhan", true)
        val isPrayerAdhanEnabled = if (prayerName.isNotEmpty()) {
            prefs.getBoolean("${prayerName.lowercase()}_adhan", true)
        } else true
        
        // If adhan is disabled, stop the service
        if (!isAdhanGloballyEnabled || !isPrayerAdhanEnabled) {
            Log.d(TAG, "Adhan is disabled for $prayerName or globally, stopping service")
            isServiceRunningForAdhan = false
            stopSelf()
        } else {
            isServiceRunningForAdhan = true
        }
    }
    
    private fun checkIfShouldStopService() {
        // Only stop if we're sure adhan isn't playing
        if (!PrayerReceiver.isAdhanPlaying() && !isServiceRunningDuringAdhanWindow()) {
            Log.d(TAG, "Adhan is not playing after delay check, stopping service")
            stopSelf()
        }
    }
    
    private fun isServiceRunningDuringAdhanWindow(): Boolean {
        // Keep service running for at least 3 minutes after starting
        // This ensures volume buttons work even if isAdhanPlaying flag is wrong
        return System.currentTimeMillis() - startTime < 3 * 60 * 1000L
    }

    override fun onDestroy() {
        try {
            serviceStopHandler.removeCallbacks(serviceStopRunnable)
            unregisterReceiver(volumeButtonReceiver)
            Log.d(TAG, "VolumeButtonService destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering volume button receiver", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForeground() {
        val channelId = "volume_button_service"
        val channelName = "Volume Button Service"
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors volume button presses to stop Adhan"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Prayer Time")
            .setContentText("Press volume buttons to stop Adhan")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "VolumeButtonService"
        private const val NOTIFICATION_ID = 3000
        private const val MAX_SERVICE_DURATION = 5 * 60 * 1000L // 5 minutes max
        private val startTime = System.currentTimeMillis()
    }
}
