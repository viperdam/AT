package com.viperdam.kidsprayer.prayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.viperdam.kidsprayer.service.PrayerReceiver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller class for handling volume button presses during adhan playback.
 * This class registers to receive volume change events only when adhan is playing
 * and stops the adhan playback when volume buttons are pressed.
 */
class AdhanVolumeController private constructor() {
    private val isActive = AtomicBoolean(false)
    private var volumeReceiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null

    companion object {
        private const val TAG = "AdhanVolumeController"
        private var instance: AdhanVolumeController? = null
        private const val AUTO_DEACTIVATE_TIME = 3 * 60 * 1000L // 3 minutes timeout

        fun getInstance(): AdhanVolumeController {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AdhanVolumeController()
                    }
                }
            }
            return instance!!
        }
    }

    /**
     * Activates the volume button detection for stopping adhan.
     * Should be called shortly before adhan is scheduled to play.
     */
    fun activate(context: Context, prayerName: String) {
        // Check if adhan is enabled for this prayer
        val settingsManager = com.viperdam.kidsprayer.util.PrayerSettingsManager.getInstance(context)
        if (!settingsManager.isAdhanEnabled(prayerName)) {
            Log.d(TAG, "Adhan is disabled for $prayerName, not activating volume control")
            return
        }
        
        if (isActive.get()) {
            Log.d(TAG, "AdhanVolumeController is already active")
            return
        }
        
        try {
            // Register volume button receiver
            if (volumeReceiver == null) {
                volumeReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                            if (isActive.get()) {
                                Log.d(TAG, "Volume button pressed, stopping adhan")
                                PrayerReceiver.stopAdhan(context, "volume_button")
                                
                                // Start a short vibration to provide feedback
                                try {
                                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                                        vibratorManager.defaultVibrator
                                    } else {
                                        @Suppress("DEPRECATION")
                                        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                    }
                                    
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(200)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error providing vibration feedback: ${e.message}")
                                }
                                
                                // Store last stop action information
                                val prefs = context.getSharedPreferences("adhan_prefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString("last_stopped_prayer", prayerName)
                                    .putLong("last_stopped_time", System.currentTimeMillis())
                                    .apply()
                                
                                // Deactivate after handling
                                deactivate(context)
                            }
                        }
                    }
                }
                
                // Register the receiver
                val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(volumeReceiver, filter)
                }
                
                registeredContext = context.applicationContext
                isActive.set(true)
                
                Log.d(TAG, "Volume button controller activated for $prayerName")
                
                // Auto-deactivate after timeout to prevent resource leaks
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isActive.get()) {
                        Log.d(TAG, "Auto-deactivating volume controller after timeout")
                        deactivate(context)
                    }
                }, AUTO_DEACTIVATE_TIME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error activating volume button controller: ${e.message}")
        }
    }

    /**
     * Deactivates the volume button detection.
     * Should be called when adhan playback finishes.
     */
    fun deactivate(context: Context) {
        if (isActive.compareAndSet(true, false)) {
            Log.d(TAG, "Deactivating volume control")
            unregisterVolumeReceiver(context)
        }
    }

    /**
     * Register a broadcast receiver to listen for volume change events.
     */
    private fun registerVolumeReceiver(context: Context) {
        try {
            if (volumeReceiver == null) {
                volumeReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                            Log.d(TAG, "Volume button pressed, stopping adhan")
                            stopAdhan(context)
                        }
                    }
                }
            }

            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.applicationContext.registerReceiver(volumeReceiver, filter)
            registeredContext = context.applicationContext
            Log.d(TAG, "Registered volume receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering volume receiver", e)
            isActive.set(false)
        }
    }

    /**
     * Unregister the volume change broadcast receiver.
     */
    private fun unregisterVolumeReceiver(context: Context) {
        try {
            volumeReceiver?.let {
                registeredContext?.unregisterReceiver(it)
                registeredContext = null
                Log.d(TAG, "Unregistered volume receiver")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering volume receiver", e)
        }
    }

    /**
     * Stop the adhan playback by calling the PrayerReceiver's stopAdhan method.
     */
    private fun stopAdhan(context: Context) {
        try {
            // Call PrayerReceiver's stopAdhan method
            PrayerReceiver.stopAdhan(context, "volume_button")
            
            // Deactivate volume control after stopping adhan
            deactivate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping adhan", e)
        }
    }

    /**
     * Check if volume control is currently active.
     */
    fun isActive(): Boolean {
        return isActive.get()
    }

    /**
     * Monitor settings changes for adhan.
     */
    fun handleSettingsChange(context: Context, sharedPreferences: SharedPreferences) {
        val isAdhanGloballyEnabled = sharedPreferences.getBoolean("enable_adhan", true)
        
        // If adhan is disabled globally and volume control is active, deactivate it
        if (!isAdhanGloballyEnabled && isActive.get()) {
            Log.d(TAG, "Adhan disabled in settings, deactivating volume control")
            deactivate(context)
        }
    }
} 