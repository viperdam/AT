package com.viperdam.kidsprayer.ui.lock.base

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.viperdam.kidsprayer.R

abstract class LockScreenBase : AppCompatActivity() {
    protected var canFinish = false
    protected var isShowingAd = false
    protected var isUnlocked = false
    protected var lastFocusTime = 0L
    
    companion object {
        const val TAG = "LockScreenBase"
        const val FOCUS_CHECK_INTERVAL = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
    }

    private fun setupWindow() {
        // Reset states on create
        isShowingAd = false
        isUnlocked = false
        canFinish = false
        
        // Make activity single instance and exclude from recents
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(
                getString(R.string.app_name),
                R.mipmap.ic_launcher,
                Color.WHITE
            ))
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(
                getString(R.string.app_name),
                null,
                Color.WHITE
            ))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        
        // Set window flags using modern APIs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Keep screen on and secure
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Set up modern window insets handling
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply insets while maintaining edge-to-edge appearance
            WindowInsetsCompat.CONSUMED
        }
        
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Ensure proper window focus
        window.decorView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
        lastFocusTime = SystemClock.elapsedRealtime()
    }

    protected fun hideSystemUI() {
        // Use modern window insets API for edge-to-edge content
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply insets if needed while maintaining edge-to-edge appearance
            WindowInsetsCompat.CONSUMED
        }
        
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    protected fun moveTaskToFront() {
        if (!isUnlocked) {
            try {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastFocusTime >= FOCUS_CHECK_INTERVAL) {
                    lastFocusTime = currentTime
                    
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.moveTaskToFront(taskId, 
                        ActivityManager.MOVE_TASK_WITH_HOME or
                        ActivityManager.MOVE_TASK_NO_USER_ACTION
                    )
                    
                    // Ensure proper window focus
                    window.decorView.requestFocus()
                    
                    // Handle display cutouts using WindowInsetsCompat
                    val rootView = window.decorView.findViewById<View>(android.R.id.content)
                    ViewCompat.setOnApplyWindowInsetsListener(rootView) { v: View, windowInsets: WindowInsetsCompat ->
                        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                        // Handle cutout insets if needed
                        WindowInsetsCompat.CONSUMED
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error moving task to front: ${e.message}")
            }
        }
    }
}
