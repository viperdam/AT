package com.viperdam.kidsprayer.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import android.app.Activity
import android.app.KeyguardManager

class LockWindowManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeViews = mutableMapOf<View, WindowManager.LayoutParams>()
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    
    companion object {
        private const val TAG = "LockWindowManager"
    }
    
    fun createLockScreenParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            
            // Use TYPE_APPLICATION_OVERLAY for all Android versions
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            
            // Set base flags that are common across all Android versions
            flags = (
                // Make window untouchable
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                
                // Prevent screenshots and recording
                WindowManager.LayoutParams.FLAG_SECURE or
                
                // Keep screen on
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                
                // Force window to top
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                
                // Handle hardware acceleration
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                
                // Prevent window animations
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )

            // Add version-specific flags
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                flags = flags or (
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            
            // Set rotation animation but make orientation more flexible
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            
            // Only force portrait on phones, not large screens
            val isLargeScreen = context.resources.configuration.screenWidthDp >= 600
            if (!isLargeScreen) {
                screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                // Allow orientation to follow device on large screens
                screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            
            // Set window properties
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
            
            // Set window token
            token = null
        }
    }
    
    fun addView(view: View, params: WindowManager.LayoutParams? = null) {
        try {
            val layoutParams = params ?: createLockScreenParams()
            windowManager.addView(view, layoutParams)
            activeViews[view] = layoutParams
            
            // Set up modern window insets handling
            setupWindowInsets(view)
            
            // Handle keyguard and screen state for Android O_MR1 and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && context is Activity) {
                context.apply {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                    keyguardManager.requestDismissKeyguard(this, null)
                }
            }
            
            Log.d(TAG, "View added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding view: ${e.message}")
            // Try to recover by removing and re-adding
            removeView(view)
            try {
                val layoutParams = params ?: createLockScreenParams()
                windowManager.addView(view, layoutParams)
                activeViews[view] = layoutParams
                Log.d(TAG, "View recovered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover view: ${e.message}")
            }
        }
    }

    private fun setupWindowInsets(view: View) {
        // Set up modern window insets handling
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Adjust view layout based on IME visibility
            if (imeInsets.bottom > 0) {
                v.translationY = -(imeInsets.bottom - systemBarInsets.bottom).toFloat()
            } else {
                v.translationY = 0f
            }
            
            WindowInsetsCompat.CONSUMED
        }
        
        // Handle system bars using modern APIs
        view.doOnAttach { attachedView ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowCompat.setDecorFitsSystemWindows(
                    (context as? Activity)?.window ?: return@doOnAttach,
                    false
                )
                
                (context as? Activity)?.window?.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                ViewCompat.getWindowInsetsController(attachedView)?.apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    
    fun updateView(view: View, params: WindowManager.LayoutParams) {
        try {
            if (activeViews.containsKey(view)) {
                windowManager.updateViewLayout(view, params)
                activeViews[view] = params
                Log.d(TAG, "View updated successfully")
            } else {
                Log.w(TAG, "Attempted to update non-existing view")
                addView(view, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view: ${e.message}")
            // Try to recover by removing and re-adding
            removeView(view)
            addView(view, params)
        }
    }
    
    fun removeView(view: View) {
        try {
            if (activeViews.containsKey(view)) {
                windowManager.removeView(view)
                activeViews.remove(view)
                Log.d(TAG, "View removed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing view: ${e.message}")
            // Force remove from our tracking
            activeViews.remove(view)
        }
    }
    
    fun removeAllViews() {
        activeViews.keys.toList().forEach { view ->
            removeView(view)
        }
        activeViews.clear()
        Log.d(TAG, "All views removed")
    }
    
    fun updateViewFlags(view: View, flagsToAdd: Int, flagsToRemove: Int = 0) {
        try {
            val params = activeViews[view] ?: createLockScreenParams()
            params.flags = params.flags and flagsToRemove.inv() or flagsToAdd
            updateView(view, params)
            Log.d(TAG, "View flags updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view flags: ${e.message}")
        }
    }
    
    fun bringToFront(view: View) {
        try {
            val params = activeViews[view] ?: return
            removeView(view)
            addView(view, params)
            Log.d(TAG, "View brought to front successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error bringing view to front: ${e.message}")
        }
    }
    
    fun isViewAttached(view: View): Boolean {
        return activeViews.containsKey(view)
    }
    
    fun getViewParams(view: View): WindowManager.LayoutParams? {
        return activeViews[view]
    }
    
    fun cleanup() {
        removeAllViews()
        Log.d(TAG, "Window manager cleaned up")
    }
}
