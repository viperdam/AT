package com.viperdam.kidsprayer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TimeChangeReceiver listens for system time changes and timezone changes.
 * When such an event occurs, it triggers an update in the prayer scheduling.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TimeChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "Received time change broadcast: $action")
        
        // Instantiate PrayerScheduler and trigger schedule update
        val scheduler = PrayerScheduler(context)
        
        // Use a coroutine to call the suspend function
        CoroutineScope(Dispatchers.IO).launch {
            scheduler.checkAndUpdateSchedule()
        }
    }
}
