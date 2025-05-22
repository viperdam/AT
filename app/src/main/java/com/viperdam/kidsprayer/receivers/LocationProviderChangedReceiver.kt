package com.viperdam.kidsprayer.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ui.LocationDialogActivity
import com.viperdam.kidsprayer.ui.main.MainActivity

class LocationProviderChangedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationReceiver"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "location_status_channel"
        private const val CHANNEL_NAME = "Location Status"
        const val ACTION_REQUEST_LOCATION_SETTINGS = "com.viperdam.kidsprayer.REQUEST_LOCATION_SETTINGS"
        private const val LAST_NOTIFICATION_KEY = "last_location_notification_time"
        private const val NOTIFICATION_INTERVAL = 30 * 60 * 1000L // 30 minutes between notifications
        
        // Dialog throttling
        private var lastDialogShownTime = 0L
        private const val DIALOG_THROTTLE_INTERVAL = 5000L // 5 seconds between dialogs
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            Log.d(TAG, "Location providers changed.")
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.w(TAG, "Location services are disabled.")
                
                // Check if we should show the dialog (throttle)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastDialogShownTime > DIALOG_THROTTLE_INTERVAL) {
                    Log.d(TAG, "Showing location dialog immediately")
                    showLocationDialog(context)
                    lastDialogShownTime = currentTime
                } else {
                    Log.d(TAG, "Skipping dialog - shown too recently (within ${DIALOG_THROTTLE_INTERVAL/1000} seconds)")
                }
                
                // Also show notification but check for frequency
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                createNotificationChannel(notificationManager)
                
                val sharedPrefs = context.getSharedPreferences("location_receiver_prefs", Context.MODE_PRIVATE)
                val lastNotificationTime = sharedPrefs.getLong(LAST_NOTIFICATION_KEY, 0L)
                
                if (currentTime - lastNotificationTime > NOTIFICATION_INTERVAL) {
                    showLocationDisabledNotification(context, notificationManager)
                    sharedPrefs.edit().putLong(LAST_NOTIFICATION_KEY, currentTime).apply()
                } else {
                    Log.d(TAG, "Skipping notification - shown recently")
                }
            } else {
                Log.d(TAG, "Location services are enabled.")
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }
    }

    private fun showLocationDialog(context: Context) {
        try {
            val dialogIntent = Intent(context, LocationDialogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(dialogIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing location dialog", e)
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH // High importance to alert user
                ).apply {
                    description = "Notifications about location service status"
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created.")
            }
        }
    }

    private fun showLocationDisabledNotification(context: Context, notificationManager: NotificationManager) {
        // Intent to open system location settings
        val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val settingsPendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, settingsIntent, pendingIntentFlags
        )

        // Intent to launch LocationDialogActivity for the system dialog
        val dialogIntent = Intent(context, LocationDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val dialogPendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 1, dialogIntent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.location_disabled_notification_title))
            .setContentText(context.getString(R.string.location_disabled_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(dialogPendingIntent) // Tap to show system dialog
            .addAction(android.R.drawable.ic_menu_preferences,
                       context.getString(R.string.enable_location_action),
                       settingsPendingIntent) // Settings button
            .setAutoCancel(true) // Cancel when tapped
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Location disabled notification shown.")
    }
}
