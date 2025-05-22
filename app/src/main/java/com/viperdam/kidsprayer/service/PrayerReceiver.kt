package com.viperdam.kidsprayer.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.prayer.LocationManager
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.ui.main.MainActivity
import com.viperdam.kidsprayer.utils.PrayerValidator
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.viperdam.kidsprayer.util.PrayerSettingsManager

class PrayerReceiver : BroadcastReceiver() {
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var retryCount = 0
    private var isLockScreenActivated = false
    private var lastPrayerActivation = 0L

    companion object {
        private const val TAG = "PrayerReceiver"
        private const val PREFS_NAME = "prayer_receiver_prefs"
        private const val KEY_PENDING_PRAYER = "pending_prayer"
        private const val KEY_PENDING_RAKAAT = "pending_rakaat"
        private const val KEY_PENDING_TIME = "pending_time"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_LAST_ACTIVATION = "last_activation"
        private const val KEY_LOCK_SCREEN_SHOWN = "lock_screen_shown"
        private const val KEY_PIN_VERIFIED = "pin_verified"
        private const val KEY_LOCK_SCREEN_ACTIVE = "lock_screen_active"
        private const val KEY_DEVICE_BOOT_TIME = "device_boot_time"
        private const val KEY_WAS_LOCKED = "was_locked"
        private const val PENDING_PRAYER_TIMEOUT = 24 * 60 * 60 * 1000L // 24 hours
        private const val LOCK_SCREEN_DELAY = 5000L // 5 seconds delay
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY = 1000L // 1 second between retries
        private const val MIN_ACTIVATION_INTERVAL = 10000L // 10 seconds
        private const val NOTIFICATION_CHANNEL_ID = "prayer_notification_channel"
        private const val NOTIFICATION_ID = 1000
        private const val ADHAN_NOTIFICATION_ID = 2000
        private const val WAKE_LOCK_TIMEOUT = 30000L // 30 seconds
        private const val NEXT_PRAYER_THRESHOLD_MS = 20 * 60 * 1000L // 20 minutes before next prayer
        private const val INITIAL_ACTIVATION_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val KEY_ACTIVE_PRAYER = "active_prayer"
        private const val KEY_ACTIVE_PRAYER_TIME = "active_prayer_time"
        private const val KEY_ACTIVE_RAKAAT = "active_rakaat"
        
        // New tracking keys for preventing duplicate events
        private const val KEY_ADHAN_PLAYED = "adhan_played_"
        private const val KEY_LOCKSCREEN_SHOWN = "lockscreen_shown_"
        private const val KEY_NOTIFICATION_SHOWN = "notification_shown_"
        
        const val STOP_ADHAN_ACTION = "com.viperdam.kidsprayer.STOP_ADHAN_ACTION"
        const val PRAYER_TIME_ACTION = "com.viperdam.kidsprayer.PRAYER_TIME"
        const val PRAYER_NOTIFICATION_ACTION = "com.viperdam.kidsprayer.PRAYER_NOTIFICATION"
        const val PRAYER_LOCK_ACTION = "com.viperdam.kidsprayer.PRAYER_LOCK"
        const val PRAYER_ADHAN_ACTION = "com.viperdam.kidsprayer.PRAYER_ADHAN"
        const val ACTION_RELAUNCH_LOCKSCREEN = "com.viperdam.kidsprayer.RELAUNCH_LOCKSCREEN"
        const val PRE_ADHAN_SETUP_ACTION = "com.viperdam.kidsprayer.PRE_ADHAN_SETUP_ACTION"

        @Volatile
        private var mediaPlayer: MediaPlayer? = null
        @Volatile
        private var isPlaying = false

        fun isAdhanPlaying(): Boolean = isPlaying
        
        // Add method to check if an event has already been handled
        fun hasEventOccurred(context: Context, eventType: String, prayerName: String, prayerTime: Long): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "${eventType}${prayerName}_${prayerTime}"
            return prefs.getBoolean(key, false)
        }
        
        // Add method to mark an event as handled
        fun markEventOccurred(context: Context, eventType: String, prayerName: String, prayerTime: Long) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "${eventType}${prayerName}_${prayerTime}"
            prefs.edit().putBoolean(key, true).apply()
            Log.d(TAG, "Marked event as occurred: $key")
        }
        
        // Clear events older than a specific time (for cleanup)
        fun clearOldEvents(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val allKeys = prefs.all.keys
                val currentTime = System.currentTimeMillis()
                val editor = prefs.edit()
                
                for (key in allKeys) {
                    if (key.startsWith(KEY_ADHAN_PLAYED) || 
                        key.startsWith(KEY_LOCKSCREEN_SHOWN) || 
                        key.startsWith(KEY_NOTIFICATION_SHOWN)) {
                        
                        // Extract timestamp from key
                        val lastPartIndex = key.lastIndexOf('_')
                        if (lastPartIndex > 0 && lastPartIndex < key.length - 1) {
                            try {
                                val timestamp = key.substring(lastPartIndex + 1).toLong()
                                // If older than 24 hours, remove it
                                if (currentTime - timestamp > 24 * 60 * 60 * 1000L) {
                                    editor.remove(key)
                                }
                            } catch (e: NumberFormatException) {
                                // Ignore invalid keys
                            }
                        }
                    }
                }
                
                editor.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing old events", e)
            }
        }
        
        // Check if a feature is enabled for a prayer
        fun isFeatureEnabled(context: Context, prayerName: String, feature: String): Boolean {
            val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
            val globalEnabled = when(feature) {
                "lock" -> prefs.getBoolean("enable_lock", true)
                "adhan" -> prefs.getBoolean("enable_adhan", true)
                "notification" -> prefs.getBoolean("notifications_enabled", true)
                else -> true
            }
            
            val prayerEnabled = prefs.getBoolean("${prayerName.lowercase()}_$feature", true)
            return globalEnabled && prayerEnabled
        }

        fun stopAdhan() {
            stopAdhan(null, "internal")
        }

        fun stopAdhan(context: Context?, reason: String = "unknown") {
            synchronized(this) {
                try {
                    Log.d(TAG, "Stopping adhan, reason: $reason")
                    val player = mediaPlayer ?: return
                    try {
                        when {
                            player.isPlaying -> {
                                player.pause()
                                player.seekTo(0)
                                player.stop()
                            }
                            else -> {
                                // Player is not playing, just ensure it's stopped
                                player.seekTo(0)
                                player.stop()
                            }
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Error stopping MediaPlayer", e)
                    } finally {
                        try {
                            player.release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error releasing MediaPlayer", e)
                        }
                        mediaPlayer = null
                        isPlaying = false
                        
                        // Stop the VolumeButtonService if context is provided
                        context?.let {
                            try {
                                it.stopService(Intent(it, VolumeButtonService::class.java))
                                Log.d(TAG, "Stopped VolumeButtonService")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error stopping VolumeButtonService", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stopAdhan", e)
                }
            }
        }

        fun setMediaPlayer(player: MediaPlayer?) {
            synchronized(this) {
                mediaPlayer = player
                isPlaying = player != null
            }
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = context.getString(R.string.prayer_service_title)
                val descriptionText = context.getString(R.string.prayer_service_message)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                    enableVibration(true)
                    enableLights(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    setShowBadge(true)
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun showAdhanNotification(context: Context, prayer: Prayer) {
            val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.prayer_service_title))
                .setContentText(context.getString(R.string.prayer_notification_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .addAction(
                    R.drawable.ic_stop,
                    context.getString(R.string.volume_service_notification_text),
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, PrayerReceiver::class.java).apply {
                            action = STOP_ADHAN_ACTION
                        },
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                    )
                )

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(context)
            notificationManager.notify(ADHAN_NOTIFICATION_ID, notificationBuilder.build())
        }

        private fun getPrayerDisplayName(context: Context, prayerName: String): String {
            return when (prayerName.lowercase()) {
                "fajr" -> context.getString(R.string.prayer_fajr)
                "dhuhr" -> context.getString(R.string.prayer_dhuhr)
                "asr" -> context.getString(R.string.prayer_asr)
                "maghrib" -> context.getString(R.string.prayer_maghrib)
                "isha" -> context.getString(R.string.prayer_isha)
                else -> prayerName
            }
        }
    }

    private fun playAdhan(context: Context, prayer: Prayer) {
        try {
            val settingsManager = PrayerSettingsManager.getInstance(context)
            
            // Check if adhan is enabled for this prayer
            if (!settingsManager.isAdhanEnabled(prayer.name)) {
                Log.d(TAG, "Adhan disabled for ${prayer.name}, not playing")
                return
            }
            
            synchronized(PrayerReceiver::class.java) {
                // If an adhan is already playing, stop it first
                if (isPlaying) {
                    stopAdhan(context, "new_adhan_requested")
                }
                
                // Start the volume button service to listen for volume button presses
                try {
                    context.startService(Intent(context, VolumeButtonService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting VolumeButtonService", e)
                }
                
                // Play the adhan
                val adhanResId = getAdhanResourceForPrayer(prayer.name)
                val mediaPlayer = MediaPlayer.create(context, adhanResId)
                
                if (mediaPlayer != null) {
                    // Set volume based on current settings
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val volume = 1.0f  // Full volume
                    
                    mediaPlayer.setVolume(volume, volume)
                    
                    // Set completion listener
                    mediaPlayer.setOnCompletionListener {
                        stopAdhan(context, "completion")
                    }
                    
                    // Start playback
                    mediaPlayer.start()
                    setMediaPlayer(mediaPlayer)
                    isPlaying = true
                    
                    // Send a notification for the adhan
                    createNotificationChannel(context)
                    showAdhanNotification(context, prayer)
                    
                    Log.d(TAG, "Started playing adhan for ${prayer.name}")
                } else {
                    Log.e(TAG, "Failed to create MediaPlayer for adhan")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing adhan", e)
        }
    }

    /**
     * Get the appropriate adhan resource based on prayer name
     */
    private fun getAdhanResourceForPrayer(prayerName: String): Int {
        return when (prayerName.lowercase()) {
            "fajr" -> R.raw.adhan_fajr
            else -> R.raw.adhan_normal
        }
    }
    
    /**
     * Set up pre-adhan volume control to ensure volume is properly set before adhan playback
     */
    private fun setupPreAdhanVolumeControl(context: Context, prayerName: String) {
        try {
            Log.d(TAG, "Setting up pre-adhan volume control for $prayerName")
            
            // Check if adhan is enabled for this prayer
            val settingsManager = PrayerSettingsManager.getInstance(context)
            if (!settingsManager.isAdhanEnabled(prayerName)) {
                Log.d(TAG, "Adhan is disabled for $prayerName, skipping volume control setup")
                return
            }
            
            // Get audio manager to control volume
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Store current volume settings
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("prev_volume_level", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .putLong("volume_control_time", System.currentTimeMillis())
                .putString("volume_control_prayer", prayerName)
                .apply()
            
            // Set volume to the level specified in settings
            val prayerSettings = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
            val adhanVolume = prayerSettings.getFloat("adhan_volume", 0.7f)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumeIndex = (maxVolume * adhanVolume).toInt()
            
            // Set volume to desired level for adhan
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                volumeIndex,
                0 // No flags to prevent UI from showing
            )
            
            Log.d(TAG, "Pre-adhan volume set to $volumeIndex/$maxVolume for $prayerName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up pre-adhan volume control", e)
        }
    }
    
    private fun reschedulePrayers(context: Context) {
        // Launch a coroutine to reschedule prayers since this is a suspend function
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locationManager = LocationManager(context)
                val location = locationManager.getLastLocation()
                val calculator = PrayerTimeCalculator()
                val prayers = calculator.calculatePrayerTimes(location)
                
                val scheduler = PrayerScheduler(context)
                scheduler.cancelAllNotifications()
                scheduler.scheduleAllPrayers(prayers)
                Log.d(TAG, "Successfully rescheduled prayers after time change")
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling prayers: ${e.message}")
                handleError(context, e)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Initialize the settings manager
        val settingsManager = PrayerSettingsManager.getInstance(context)

        try {
            // Acquire wake lock to ensure we complete processing
            acquireWakeLock(context)
            
            when (action) {
                PRAYER_ADHAN_ACTION -> {
                    handlePrayerAdhan(context, intent)
                }
                
                PRAYER_LOCK_ACTION -> {
                    handleLockScreen(context, intent)
                }
                
                PRAYER_NOTIFICATION_ACTION -> {
                    handleNotification(context, intent)
                }
                
                // Add handler for PRAYER_UNLOCK_ACTION
                PrayerScheduler.PRAYER_UNLOCK_ACTION -> {
                    handlePrayerUnlock(context, intent)
                }
                
                // Handle relaunch action for bypass recovery
                ACTION_RELAUNCH_LOCKSCREEN -> {
                    val prayerName = intent.getStringExtra("prayer_name")
                    val rakaatCount = intent.getIntExtra("rakaat_count", 0)
                    
                    if (!prayerName.isNullOrEmpty() && rakaatCount > 0) {
                        Log.d(TAG, "Received relaunch request for: $prayerName with $rakaatCount rakaats")
                        
                        // Store in PrayerCompletionManager for consistency
                        val completionManager = PrayerCompletionManager.getInstance(context)
                        completionManager.setLockScreenActive(prayerName, rakaatCount)
                        completionManager.storeValidPrayerState(prayerName, rakaatCount)
                        
                        // Show the lock screen with the same prayer info
                        showLockScreen(context, prayerName, rakaatCount)
                    }
                }
                
                // Keep the rest of your cases
                PRE_ADHAN_SETUP_ACTION -> {
                    handlePreAdhanSetup(context, intent)
                }
                
                STOP_ADHAN_ACTION -> {
                    Log.d(TAG, "Received action: $action to stop adhan")
                    stopAdhan(context, "broadcast_receiver")
                }
                
                Intent.ACTION_BOOT_COMPLETED -> {
                    Log.d(TAG, "Received boot completed, rescheduling prayers")
                    // On boot, we should reschedule all prayer alarms
                    reschedulePrayers(context)
                }
                
                // Also check for bypass on screen on events
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    // Check for bypass when screen is turned on or unlocked
                    checkAndRecoverFromBypass(context)
                }
                
                // Handle other actions...
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onReceive", e)
        } finally {
            releaseWakeLock()
        }
    }

    private fun handlePrayerAdhan(context: Context, intent: Intent) {
        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: return
            val rakaatCount = intent.getIntExtra("rakaat_count", 0)
            val prayerTime = intent.getLongExtra("prayer_time", System.currentTimeMillis())
            
            Log.d(TAG, "Received PRAYER_ADHAN_ACTION for prayer: $prayerName")
            
            // Get the settings manager
            val settingsManager = PrayerSettingsManager.getInstance(context)
            
            // 1. Check if already played
            if (settingsManager.hasEventOccurred(PrayerSettingsManager.EVENT_ADHAN_PLAYED, prayerName, prayerTime)) {
                Log.d(TAG, "Adhan already played for $prayerName at ${java.util.Date(prayerTime)}")
                releaseWakeLock()
                return
            }
            
            // 2. Check if adhan is enabled for this prayer
            if (!settingsManager.isAdhanEnabled(prayerName)) {
                Log.d(TAG, "Adhan is disabled for prayer $prayerName")
                releaseWakeLock()
                return
            }
            
            // 3. Mark as played IMMEDIATELY (before playback starts)
            settingsManager.markEventOccurred(PrayerSettingsManager.EVENT_ADHAN_PLAYED, prayerName, prayerTime)
            
            // Create the prayer object
            val prayer = Prayer(prayerName, prayerTime, rakaatCount)
            
            // Play adhan
            playAdhan(context, prayer)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling prayer adhan", e)
            releaseWakeLock()
        }
    }

    private fun handlePreAdhanSetup(context: Context, intent: Intent) {
        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: "Unknown Prayer"
            Log.d(TAG, "Setting up pre-adhan volume control for $prayerName")
            
            // Check if adhan is enabled for this prayer using the settings manager
            val settingsManager = PrayerSettingsManager.getInstance(context)
            if (!settingsManager.isAdhanEnabled(prayerName)) {
                Log.d(TAG, "Adhan is disabled for $prayerName, not activating volume control")
                return
            }
            
            // Use the AdhanVolumeController to setup for this prayer
            val adhanVolumeController = com.viperdam.kidsprayer.prayer.AdhanVolumeController.getInstance()
            adhanVolumeController.activate(context, prayerName)
            
            Log.d(TAG, "Pre-adhan volume control setup complete for $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up pre-adhan volume control", e)
        }
    }

    private fun handleLockScreen(context: Context, intent: Intent) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: return
            val rakaatCount = intent.getIntExtra("rakaat_count", 0)
            val prayerTime = intent.getLongExtra("prayer_time", System.currentTimeMillis())
            
            Log.d(TAG, "Received PRAYER_LOCK_ACTION for prayer: $prayerName with $rakaatCount rakaats at time: ${java.util.Date(prayerTime)}")
            
            // 1. Check if already shown
            if (settingsManager.hasEventOccurred(PrayerSettingsManager.EVENT_LOCKSCREEN_SHOWN, prayerName, prayerTime)) {
                Log.d(TAG, "Lock screen already shown for $prayerName at ${java.util.Date(prayerTime)}")
                releaseWakeLock()
                return
            }
            
            // 2. Check if lock is enabled for this prayer
            if (!settingsManager.isLockEnabled(prayerName)) {
                Log.d(TAG, "Lock screen disabled for $prayerName, not showing")
                releaseWakeLock()
                return
            }
            
            // 3. Mark as shown IMMEDIATELY (before UI shows)
            settingsManager.markEventOccurred(PrayerSettingsManager.EVENT_LOCKSCREEN_SHOWN, prayerName, prayerTime)
            
            // Show lock screen with delay
            // Save the pending prayer
            prefs?.edit()
                ?.putString(KEY_PENDING_PRAYER, prayerName)
                ?.putInt(KEY_PENDING_RAKAAT, rakaatCount)
                ?.putLong(KEY_PENDING_TIME, prayerTime)
                ?.apply()
            
            showLockScreen(context, prayerName, rakaatCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling lock screen action", e)
            releaseWakeLock()
        }
    }

    private fun handleNotification(context: Context, intent: Intent) {
        val settingsManager = PrayerSettingsManager.getInstance(context)

        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: return
            val rakaatCount = intent.getIntExtra("rakaat_count", 0)
            val prayerTime = intent.getLongExtra("prayer_time", System.currentTimeMillis())
            val isAdvance = intent.getBooleanExtra("is_advance_notification", false)
            val advanceMinutes = intent.getIntExtra("advance_minutes", -1)

            Log.d(TAG, "Received PRAYER_NOTIFICATION_ACTION for prayer: $prayerName (isAdvance=$isAdvance, minutes=$advanceMinutes)")

            // 1. Check if already shown
            if (settingsManager.hasEventOccurred(PrayerSettingsManager.EVENT_NOTIFICATION_SHOWN, prayerName, prayerTime)) {
                Log.d(TAG, "Notification already shown for $prayerName at ${java.util.Date(prayerTime)}")
                return
            }

            // 2. Check if notification is enabled for this prayer
            if (!settingsManager.isNotificationEnabled(prayerName)) {
                Log.d(TAG, "Notification disabled for $prayerName, not showing")
                return
            }

            // 3. Mark as shown IMMEDIATELY (before notification shows)
            settingsManager.markEventOccurred(PrayerSettingsManager.EVENT_NOTIFICATION_SHOWN, prayerName, prayerTime)

            // Save pending prayer info
            prefs?.edit()
                ?.putString(KEY_PENDING_PRAYER, prayerName)
                ?.putInt(KEY_PENDING_RAKAAT, rakaatCount)
                ?.putLong(KEY_PENDING_TIME, prayerTime)
                ?.apply()

            // Show notification
            showPrayerNotification(context, prayerName, rakaatCount, isAdvance, advanceMinutes)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling prayer notification", e)
        } finally {
            releaseWakeLock()
        }
    }

    private fun showPrayerNotification(
        context: Context,
        prayerName: String,
        rakaatCount: Int,
        isAdvance: Boolean,
        advanceMinutes: Int
    ) {
        try {
            val settingsManager = PrayerSettingsManager.getInstance(context)
            
            // Check if notifications are enabled
            if (!settingsManager.isNotificationEnabled(prayerName)) {
                Log.d(TAG, "Notifications disabled for $prayerName, not showing")
                return
            }
            
            // Create notification channel
            createNotificationChannel(context)

            // Create intent for notification tap action
            val notificationIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("prayer_name", prayerName)
                putExtra("rakaat_count", rakaatCount)
            }

            val notificationId = NOTIFICATION_ID + prayerName.hashCode()

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                notificationIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            // Build notification content based on whether it's an advance notification
            val displayPrayerName = getPrayerDisplayName(context, prayerName)
            val title: String
            val text: String

            if (isAdvance && advanceMinutes > 0) {
                title = context.getString(R.string.prayer_advance_notification_title, advanceMinutes, displayPrayerName)
                text = context.getString(R.string.prayer_advance_notification_message)
                Log.d(TAG, "Showing ADVANCE notification for $prayerName ($advanceMinutes minutes)")
            } else {
                title = context.getString(R.string.prayer_notification_title, displayPrayerName)
                text = context.getString(R.string.prayer_notification_message)
                Log.d(TAG, "Showing STANDARD notification for $prayerName")
            }

            val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notificationBuilder.build())

        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }

    private fun handleTimeChanged(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                val prayerScheduler = PrayerScheduler(context)

                // Check and update schedule based on new time
                // This call is sufficient, it handles rescheduling internally
                prayerScheduler.checkAndUpdateSchedule(forceReschedule = true) // Force reschedule on time change

                Log.d(TAG, "checkAndUpdateSchedule called after time change.")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling time change: ${e.message}")
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                // Similar to time change, but with a delay to ensure system is ready
                delay(10000) // 10 second delay
                handleTimeChanged(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling boot completed: ${e.message}")
            }
        }
    }

    private fun handlePackageReplaced(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                // Handle like a time change to ensure all schedules are up to date
                handleTimeChanged(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling package replaced: ${e.message}")
            }
        }
    }

    private fun initializePrefs(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AdhanTime::AdhanWakeLock"
                )
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                wakeLock = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun getAdhanVolume(context: Context): Float {
        val settingsPrefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        return settingsPrefs.getFloat("adhan_volume", 0.7f)
    }

    private fun startLockScreenService(context: Context, prayerName: String, rakaatCount: Int) {
        try {
            LockScreenService.startService(context, prayerName, rakaatCount)
            Log.d(TAG, "Lock screen service started for $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen service: ${e.message}")
            if (retryCount < MAX_RETRIES) {
                retryCount++
                handler.postDelayed({
                    startLockScreenService(context, prayerName, rakaatCount)
                }, RETRY_DELAY)
            }
        }
    }

    private fun startLockScreenActivity(context: Context, prayerName: String, rakaatCount: Int) {
        try {
            Log.d(TAG, "Starting lock screen activity: prayer=$prayerName, rakaats=$rakaatCount")
            
            // Use PrayerValidator to validate the prayer name
            if (!PrayerValidator.isValidPrayerName(prayerName)) {
                Log.e(TAG, "Cannot start lock screen with invalid prayer name: $prayerName")
                PrayerValidator.markInvalidPrayerData(context, "Invalid name in startLockScreenActivity")
                return
            }
            
            // Ensure we have valid rakaat count
            val validRakaatCount = when {
                rakaatCount <= 0 -> {
                    Log.w(TAG, "Invalid rakaat count ($rakaatCount), using default")
                    PrayerValidator.getDefaultRakaatCount(prayerName) // Use helper method for default
                }
                else -> rakaatCount
            }
            
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("prayer_name", prayerName)
                putExtra("rakaat_count", validRakaatCount)
            }
            
            // Use PrayerValidator to enhance the intent with any missing data
            val enhancedIntent = PrayerValidator.enhanceLockScreenIntent(intent)
            
            // Save current prayer info to preferences for recovery
            prefs?.edit()?.apply {
                putString(KEY_ACTIVE_PRAYER, prayerName)
                putInt(KEY_ACTIVE_RAKAAT, validRakaatCount)
                putLong(KEY_ACTIVE_PRAYER_TIME, System.currentTimeMillis())
                apply()
            }
            
            context.startActivity(enhancedIntent)
            Log.d(TAG, "Lock screen activity started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen activity", e)
            handleError(context, e)
        }
    }

    private fun getPrayerDisplayName(context: Context, prayerName: String): String {
        return when (prayerName.lowercase()) {
            "fajr" -> context.getString(R.string.prayer_fajr)
            "dhuhr" -> context.getString(R.string.prayer_dhuhr)
            "asr" -> context.getString(R.string.prayer_asr)
            "maghrib" -> context.getString(R.string.prayer_maghrib)
            "isha" -> context.getString(R.string.prayer_isha)
            else -> prayerName
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            vibrator.vibrate(android.os.VibrationEffect.createOneShot(2000, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.e(TAG, "Error in vibrate: ${e.message}")
        }
    }

    private fun getPendingPrayer(): Triple<String, Int, Long>? {
        val name = prefs?.getString(KEY_PENDING_PRAYER, null) ?: return null
        val rakaat = prefs?.getInt(KEY_PENDING_RAKAAT, 0) ?: 0
        val time = prefs?.getLong(KEY_PENDING_TIME, 0) ?: 0
        return Triple(name, rakaat, time)
    }

    private fun isPrayerExpired(timestamp: Long?): Boolean {
        if (timestamp == null) return true
        return System.currentTimeMillis() - timestamp > PENDING_PRAYER_TIMEOUT
    }

    private fun isVibrationEnabled(context: Context): Boolean {
        val settingsPrefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        return settingsPrefs.getBoolean("vibration_enabled", true)
    }

    private fun handleError(context: Context, error: Exception) {
        try {
            // Log the error
            Log.e(TAG, "Error occurred: ${error.message}", error)
            
            // Increment retry count
            retryCount++
            prefs?.edit()?.putInt(KEY_RETRY_COUNT, retryCount)?.apply()
            
            if (retryCount < MAX_RETRIES) {
                // Schedule retry in a coroutine
                handler.postDelayed({
                    scope.launch {
                        try {
                            reschedulePrayers(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in retry: ${e.message}")
                        }
                    }
                }, RETRY_DELAY * retryCount)
            } else {
                // Reset retry count after max retries
                retryCount = 0
                prefs?.edit()?.putInt(KEY_RETRY_COUNT, 0)?.apply()
                
                // Clean up any pending states
                releaseWakeLock()
                scope.coroutineContext.cancelChildren()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in error handler: ${e.message}")
        }
    }

    private fun handleBootOrUpdate(context: Context) {
        try {
            Log.d(TAG, "Handling boot or update event")
            scope.launch {
                try {
                    // Store boot time for reference
                    prefs?.edit()?.putLong(KEY_DEVICE_BOOT_TIME, System.currentTimeMillis())?.apply()
                    
                    // Apply child-directed ad settings
                    enforceChildDirectedAdSettings(context)
                    
                    // Reschedule prayers
                    val scheduler = PrayerScheduler(context)
                    scheduler.handleDeviceBoot()
                    
                    // Delay a bit to avoid race conditions
                    delay(2000)
                    
                    // Check if we should restore a lock screen (e.g., if device rebooted during prayer time)
                    val wasLocked = prefs?.getBoolean(KEY_WAS_LOCKED, false) ?: false
                    val pendingPrayer = prefs?.getString(KEY_PENDING_PRAYER, null)
                    val pendingTime = prefs?.getLong(KEY_PENDING_TIME, 0) ?: 0
                    val currentTime = System.currentTimeMillis()
                    
                    if (wasLocked && pendingPrayer != null && pendingTime > 0) {
                        // If prayer time is still valid (within 1 hour window)
                        if (currentTime - pendingTime < 60 * 60 * 1000) {
                            val rakaatCount = prefs?.getInt(KEY_PENDING_RAKAAT, 0) ?: 0
                            Log.d(TAG, "Restoring lock screen for prayer: $pendingPrayer after boot")
                            showLockScreen(context, pendingPrayer, rakaatCount)
                        } else {
                            // Clear pending prayer since it's too old
                            prefs?.edit()?.remove(KEY_PENDING_PRAYER)?.apply()
                        }
                    }
                    
                    // Reset lock state
                    prefs?.edit()?.putBoolean(KEY_WAS_LOCKED, false)?.apply()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in handleBootOrUpdate", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling boot completed", e)
        }
    }

    /**
     * Apply strict child-directed ad settings to ensure appropriate ads for children
     */
    private fun enforceChildDirectedAdSettings(context: Context) {
        try {
            // Initialize Mobile Ads if needed
            MobileAds.initialize(context) {
                Log.d(TAG, "MobileAds initialized from PrayerReceiver")
            }
            
            // Apply strict child-directed settings globally
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
                
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            Log.d(TAG, "Applied ad settings from PrayerReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ad settings: ${e.message}")
        }
    }

    /**
     * Called when a lock screen is destroyed to handle cleanup and potential relaunch
     */
    fun onLockScreenDestroyed(context: Context) {
        // Get shared preferences
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if this is a completion path
        val isPrayerComplete = prefs?.getBoolean("is_prayer_complete", false) ?: false
        val isPinVerified = prefs?.getBoolean("pin_verified", false) ?: false
        val activePrayer = prefs?.getString(KEY_ACTIVE_PRAYER, null)
        val isTestPrayer = prefs?.getBoolean("is_test_prayer", false) ?: false
        
        // Get the definitive lock state from the manager we fixed earlier
        val completionManager = PrayerCompletionManager.getInstance(context)
        val isLockStillConsideredActive = completionManager.isLockScreenActive()

        // If prayer is completed with PIN verification, ensure we store the right completion type
        if (isPrayerComplete && isPinVerified && activePrayer != null) {
            try {
                val existingType = completionManager.getCompletionType(activePrayer)
                
                // Only update if not already tracked or if it's not a PIN verification
                if (existingType == null || existingType != PrayerCompletionManager.CompletionType.PIN_VERIFIED) {
                    Log.d(TAG, "Setting PIN_VERIFIED completion type for prayer: $activePrayer")
                    completionManager.markPrayerComplete(activePrayer, PrayerCompletionManager.CompletionType.PIN_VERIFIED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating prayer completion type", e)
            }
        }
        
        // Check if we should relaunch - applies to both test and regular prayers
        val activePrayerTime = prefs?.getLong(KEY_ACTIVE_PRAYER_TIME, 0)
        val activeRakaat = prefs?.getInt(KEY_ACTIVE_RAKAAT, 0)
        val isPrayerPinVerified = activePrayer?.let { prayer ->
            activePrayerTime?.let { time ->
                prefs?.getBoolean("${KEY_PIN_VERIFIED}_${prayer}_${time}", false)
            }
        } ?: true

        // Relaunch ONLY if the CompletionManager still considers the lock active
         // AND the receiver's own state indicates it wasn't properly completed/verified.
         // This prevents relaunch after a successful unlock where clearLockScreenActive() was called.
         if (isLockStillConsideredActive && activePrayer != null && !isPrayerComplete && !isPinVerified) {
            Log.d(TAG, "Lock screen destroyed, relaunching for prayer: $activePrayer (isTest: $isTestPrayer)")
            // Relaunch through broadcast to handle it in the main receiver
            val intent = Intent(context, PrayerReceiver::class.java).apply {
                action = ACTION_RELAUNCH_LOCKSCREEN
                putExtra("prayer_name", activePrayer)
                putExtra("prayer_time", activePrayerTime)
                putExtra("rakaat_count", activeRakaat)
            }
            context.sendBroadcast(intent)
        } else {
            Log.d(TAG, "Lock screen destroyed. CompletionManager active: $isLockStillConsideredActive, Receiver state: prayer_complete=$isPrayerComplete, pin_verified=$isPinVerified - Not relaunching.")
        }
    }

    private fun showLockScreen(context: Context, prayerName: String, rakaatCount: Int) {
        try {
            val settingsManager = PrayerSettingsManager.getInstance(context)
            
            // Check settings first
            if (!settingsManager.isLockEnabled(prayerName)) {
                Log.d(TAG, "Lock screen disabled for $prayerName, not showing")
                return
            }
            
            // Get prayer time
            val prayerTime = prefs?.getLong(KEY_PENDING_TIME, 0L) ?: 0L
            val currentTime = System.currentTimeMillis()
            
            // Also store in PrayerCompletionManager for consistent bypass recovery
            val completionManager = PrayerCompletionManager.getInstance(context)
            completionManager.setLockScreenActive(prayerName, rakaatCount)
            completionManager.storeValidPrayerState(prayerName, rakaatCount)
            
            // Save current prayer info to preferences for redundant bypass recovery
            prefs?.edit()?.apply {
                putString(KEY_ACTIVE_PRAYER, prayerName)
                putInt(KEY_ACTIVE_RAKAAT, rakaatCount)
                putLong(KEY_ACTIVE_PRAYER_TIME, currentTime)
                apply()
            }
            
            // Start lock screen activity
            val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("prayer_name", prayerName)
                putExtra("rakaat_count", rakaatCount)
                putExtra("start_time", currentTime)
                putExtra("prayer_time", prayerTime)
            }
            
            // Add small delay to make sure the intent is processed after we return from onReceive
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    context.startActivity(lockIntent)
                    Log.d(TAG, "Launched lock screen for prayer: $prayerName at time: ${java.util.Date(prayerTime)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching lock screen activity", e)
                }
            }, LOCK_SCREEN_DELAY)
        } catch (e: Exception) {
            Log.e(TAG, "Error in showLockScreen", e)
        }
    }

    /**
     * Checks for bypass attempts and recovers the lockscreen if needed
     */
    private fun checkAndRecoverFromBypass(context: Context) {
        try {
            Log.d(TAG, "Checking for lock screen bypass in PrayerReceiver")
            
            // Initialize preferences if needed
            if (prefs == null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            
            val currentTime = System.currentTimeMillis()
            
            // First check: if there was a very recent unlock, skip the bypass check entirely
            val veryRecentUnlock = prefs?.getLong("very_recent_unlock", 0L) ?: 0L
            if (currentTime - veryRecentUnlock < 30000) { // 30 seconds (increased from 3 seconds)
                Log.d(TAG, "Very recent unlock detected (${currentTime - veryRecentUnlock}ms ago), skipping recovery")
                return
            }
            
            // Get active prayer info from our own preferences
            val activePrayer = prefs?.getString(KEY_ACTIVE_PRAYER, null)
            val activeRakaat = prefs?.getInt(KEY_ACTIVE_RAKAAT, 0) ?: 0
            val activePrayerTime = prefs?.getLong(KEY_ACTIVE_PRAYER_TIME, 0L) ?: 0L
            val isLockActive = prefs?.getBoolean("lock_screen_active", false) ?: false
            val isPinVerified = prefs?.getBoolean(KEY_PIN_VERIFIED, false) ?: false
            
            // Get the last unlock time to prevent false re-activations
            val lastUnlockTime = prefs?.getLong("last_unlock_time", 0L) ?: 0L
            val timeSinceUnlock = currentTime - lastUnlockTime
            
            // If unlocked very recently (within 10 seconds), don't try to recover
            if (timeSinceUnlock < 10000) { // 10 seconds
                Log.d(TAG, "Skipping bypass check - lockscreen was unlocked ${timeSinceUnlock}ms ago")
                return
            }
            
            // Also check with our state manager if we have one
            val appContext = context.applicationContext
            var stateManagerSaysActive = false
            
            try {
                val lockStateManager = when {
                    appContext is com.viperdam.kidsprayer.PrayerApp -> {
                        try {
                            com.viperdam.kidsprayer.PrayerApp.getInstance().lockStateManager
                        } catch (e: Exception) {
                            null
                        }
                    }
                    else -> null
                }
                
                if (lockStateManager != null) {
                    stateManagerSaysActive = lockStateManager.isLockScreenActive()
                    
                    // If state manager says inactive but our prefs say active, update prefs
                    if (!stateManagerSaysActive && isLockActive) {
                        prefs?.edit()?.putBoolean("lock_screen_active", false)?.apply()
                        Log.d(TAG, "State manager says lock is inactive, updating prefs to match")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking lock state manager status", e)
            }
            
            // Check if we should attempt recovery - now with additional safety checks
            if ((isLockActive || stateManagerSaysActive) && activePrayer != null && 
                activeRakaat > 0 && !isPinVerified) {
                
                Log.d(TAG, "Lock should be active for $activePrayer, checking if it's visible")
                
                // Check if lock screen is currently visible
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                var lockScreenVisible = false
                
                try {
                    @Suppress("DEPRECATION")
                    val tasks = am.getRunningTasks(1)
                    if (!tasks.isNullOrEmpty()) {
                        val topActivity = tasks[0].topActivity
                        lockScreenVisible = topActivity?.className?.contains("LockScreenActivity") == true
                    }
                } catch (e: Exception) {
                    // Security exception possible on some devices
                    Log.e(TAG, "Error checking if lock screen is visible: ${e.message}")
                }
                
                if (!lockScreenVisible) {
                    Log.w(TAG, "Lock screen bypass detected, recovering with prayer: $activePrayer")
                    
                    // First, try to use the PrayerCompletionManager for recovery
                    val completionManager = PrayerCompletionManager.getInstance(context)
                    completionManager.setLockScreenActive(activePrayer, activeRakaat)
                    
                    // Then show the lock screen again
                    showLockScreen(context, activePrayer, activeRakaat)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for lock screen bypass", e)
        }
    }

    /**
     * Handle the PRAYER_UNLOCK_ACTION broadcast
     * This is sent when the prayer lock window ends and we need to automatically clear the lock
     */
    private fun handlePrayerUnlock(context: Context, intent: Intent) {
        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: return
            Log.d(TAG, "Received PRAYER_UNLOCK_ACTION for prayer: $prayerName")
            
            // Special handling for Isha prayer
            val isIsha = prayerName.equals("isha", ignoreCase = true)
            if (isIsha) {
                Log.i(TAG, "ISHA PRAYER UNLOCK: Received unlock action for Isha specifically")
            }
            
            // Use application context to get services
            val appContext = context.applicationContext
            
            // Get dependencies
            val completionManager = PrayerCompletionManager.getInstance(appContext)
            
            // Get the state manager through DI if app context is available
            val lockStateManager = when {
                appContext is com.viperdam.kidsprayer.PrayerApp -> {
                    try {
                        // Try to get it through the app's dependency container
                        com.viperdam.kidsprayer.PrayerApp.getInstance().lockStateManager
                    } catch (e: Exception) {
                        // Fall back to direct instantiation
                        com.viperdam.kidsprayer.state.PrayerLockStateManager(appContext, completionManager)
                    }
                }
                else -> {
                    // Direct instantiation as fallback
                    com.viperdam.kidsprayer.state.PrayerLockStateManager(appContext, completionManager)
                }
            }
            
            // Trigger the automatic unlock in state manager
            lockStateManager.triggerAutomaticUnlock(prayerName)
            
            // Also update our local preferences for compatibility
            prefs?.edit()?.apply {
                putBoolean("lock_screen_active", false)
                putBoolean("is_unlocked", true)
                putLong("last_unlock_time", System.currentTimeMillis())
                putBoolean("auto_missed", true)
                putString("last_missed_prayer", prayerName)
                apply()
            }
            
            // Clear any active lock screens through the scheduler as well
            PrayerScheduler.clearActiveLockScreen(context)
            
            // For Isha, add a small delay and then verify lock screen state is cleared
            if (isIsha) {
                // Double-check the lock state and force clear it if needed
                handler.postDelayed({
                    try {
                        if (lockStateManager.isLockScreenActive()) {
                            Log.w(TAG, "ISHA PRAYER UNLOCK: Lock screen still active after unlock attempt! Forcing clear...")
                            // Force-clear through both managers
                            lockStateManager.clearActiveLock(prayerName)
                            PrayerScheduler.clearActiveLockScreen(context)
                            
                            prefs?.edit()?.apply {
                                putBoolean("lock_screen_active", false)
                                putBoolean("is_unlocked", true)
                                putLong("very_recent_unlock", System.currentTimeMillis())
                                apply()
                            }
                            Log.i(TAG, "ISHA PRAYER UNLOCK: Forced clear completed.")
                        } else {
                            Log.i(TAG, "ISHA PRAYER UNLOCK: Verification passed - Isha lock screen properly cleared")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in Isha verification handler", e)
                    }
                }, 500) // Small delay to allow unlock process to complete
            }
            
            // Force an immediate check for next prayer to schedule
            val scheduler = PrayerScheduler(context)
            
            // Launch a coroutine to call the suspend function
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    scheduler.checkAndUpdateSchedule(true)
                    Log.d(TAG, "Successfully updated prayer schedule after unlock")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating prayer schedule after unlock", e)
                }
            }
            
            Log.d(TAG, "Successfully handled PRAYER_UNLOCK_ACTION for $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling prayer unlock action", e)
        }
    }
}
