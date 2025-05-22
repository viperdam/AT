package com.viperdam.kidsprayer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ui.main.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class AdhanService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "AdhanService"
        private const val NOTIFICATION_CHANNEL_ID = "adhan_playback_channel"
        private const val NOTIFICATION_ID = 2000
        private const val ACTION_STOP = "com.viperdam.kidsprayer.STOP_ADHAN"
        private const val KEY_ADHAN_PLAYED = "adhan_played_"
        
        // Add a static flag to track if the service is running
        @Volatile private var isServiceRunning = AtomicBoolean(false)
        
        fun isRunning(): Boolean {
            return isServiceRunning.get()
        }

        fun startService(context: Context, adhanResId: Int, volume: Float, prayerName: String) {
            // Check if service is already running
            if (isServiceRunning.get()) {
                Log.d(TAG, "Adhan service is already running, not starting again")
                return
            }
            
            // Check if adhan is enabled for this prayer
            if (!isAdhanEnabled(context, prayerName)) {
                Log.d(TAG, "Adhan disabled for $prayerName in settings, not starting service")
                return
            }
            
            // Check if adhan was already played for this prayer
            val currentTime = System.currentTimeMillis()
            val key = "${KEY_ADHAN_PLAYED}${prayerName}_${currentTime / (60 * 1000)}" // Group by minute
            val prefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            
            if (prefs.getBoolean(key, false)) {
                Log.d(TAG, "Adhan already played for $prayerName in the last minute, not starting again")
                return
            }
            
            // Mark as played to prevent duplicates
            prefs.edit().putBoolean(key, true).apply()
            
            // Start the service
            val intent = Intent(context, AdhanService::class.java).apply {
                putExtra("adhan_res_id", adhanResId)
                putExtra("volume", volume)
                putExtra("prayer_name", prayerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            Log.d(TAG, "Started AdhanService for prayer: $prayerName")
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, AdhanService::class.java))
            isServiceRunning.set(false)
            Log.d(TAG, "Stopped AdhanService")
        }
        
        private fun isAdhanEnabled(context: Context, prayerName: String): Boolean {
            val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
            val globalAdhanEnabled = prefs.getBoolean("enable_adhan", true)
            val prayerAdhanEnabled = prefs.getBoolean("${prayerName.lowercase()}_adhan", true)
            return globalAdhanEnabled && prayerAdhanEnabled
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        isServiceRunning.set(true)
        Log.d(TAG, "AdhanService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Received stop action, stopping adhan playback")
            stopSelf()
            return START_NOT_STICKY
        }

        val adhanResId = intent?.getIntExtra("adhan_res_id", R.raw.adhan_normal) ?: R.raw.adhan_normal
        val volume = intent?.getFloatExtra("volume", 1.0f) ?: 1.0f
        val prayerName = intent?.getStringExtra("prayer_name") ?: "Unknown"

        try {
            // Check again in case settings changed
            if (!isAdhanEnabled(applicationContext, prayerName)) {
                Log.d(TAG, "Adhan disabled for $prayerName, stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Start foreground service with notification
            startForeground(
                NOTIFICATION_ID,
                createNotification(prayerName)
            )

            // Play the adhan
            startAdhanPlayback(adhanResId, volume)
            Log.d(TAG, "Started adhan playback for $prayerName with volume $volume")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting adhan service: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        releaseWakeLock()
        isServiceRunning.set(false)
        Log.d(TAG, "AdhanService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAdhanPlayback(adhanResId: Int, volume: Float) {
        try {
            // Release any existing media player
            mediaPlayer?.release()
            
            // Create a new media player
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(resources.openRawResourceFd(adhanResId))
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setVolume(volume, volume)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    stopSelf()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    mediaPlayer = null
                    stopSelf()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing Adhan: ${e.message}")
            mediaPlayer?.release()
            mediaPlayer = null
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.adhan_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.adhan_notification_description)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(prayerName: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AdhanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prayer)
            .setContentTitle(getString(R.string.adhan_playing))
            .setContentText(getString(R.string.adhan_for_prayer, prayerName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.stop_adhan),
                stopIntent
            )
            .setContentIntent(mainIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KidsPrayer:AdhanWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
