package com.viperdam.kidsprayer.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * This is a compatibility class to handle instrumentation issues.
 * It provides a backup to ensure no references to the Hilt-generated class break the app.
 */
class Hilt_AdBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Delegate to the real implementation
        val realReceiver = AdBootReceiver()
        realReceiver.onReceive(context, intent)
    }
} 