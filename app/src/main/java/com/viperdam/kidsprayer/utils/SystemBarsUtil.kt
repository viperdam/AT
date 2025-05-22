package com.viperdam.kidsprayer.utils

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Utility class for handling system bars in a modern way for Android 15+.
 * 
 * This class provides a better alternative to the deprecated methods:
 * - Window.setStatusBarColor
 * - Window.setNavigationBarColor
 * - MaterialDatePicker.enableEdgeToEdgeIfNeeded
 */
object SystemBarsUtil {
    private const val TAG = "SystemBarsUtil"

    /**
     * Sets up edge-to-edge display for an activity in a modern way.
     * This is the recommended approach for Android 15+ (API 35+).
     * 
     * @param activity The activity where edge-to-edge should be applied
     * @param onApplyInsets Optional callback to handle specific insets application
     */
    fun setupEdgeToEdge(
        activity: ComponentActivity,
        onApplyInsets: ((View, WindowInsetsCompat) -> Unit)? = null
    ) {
        try {
            // Enable edge-to-edge using the standard library function
            activity.enableEdgeToEdge()
            
            // Set up window insets listener for the root view
            val rootView = activity.findViewById<View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                
                // If custom insets handling is provided, use it
                if (onApplyInsets != null) {
                    onApplyInsets(view, windowInsets)
                } else {
                    // Default implementation: apply padding to the content view
                    view.setPadding(
                        systemBarsInsets.left,
                        systemBarsInsets.top,
                        systemBarsInsets.right,
                        systemBarsInsets.bottom
                    )
                }
                
                // Return windowInsets so nested views can also consume them
                windowInsets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying edge-to-edge: ${e.message}")
        }
    }
    
    /**
     * Apply insets to a specific view considering system bars
     * 
     * @param view The view to apply insets to
     * @param applyTopInset Whether to apply top inset
     * @param applyBottomInset Whether to apply bottom inset
     * @param applyHorizontalInsets Whether to apply left/right insets
     */
    fun applyInsetsToView(
        view: View,
        applyTopInset: Boolean = true,
        applyBottomInset: Boolean = true,
        applyHorizontalInsets: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            v.setPadding(
                if (applyHorizontalInsets) systemBarsInsets.left else v.paddingLeft,
                if (applyTopInset) systemBarsInsets.top else v.paddingTop,
                if (applyHorizontalInsets) systemBarsInsets.right else v.paddingRight,
                if (applyBottomInset) systemBarsInsets.bottom else v.paddingBottom
            )
            
            windowInsets
        }
    }
    
    /**
     * Configure a scrollable view (like RecyclerView or ScrollView) for edge-to-edge
     * 
     * @param view The scrollable view to configure
     * @param applyTopPadding Whether to apply top system bar padding
     * @param applyBottomPadding Whether to apply bottom system bar padding
     */
    fun configureScrollableForEdgeToEdge(
        view: ViewGroup,
        applyTopPadding: Boolean = false,
        applyBottomPadding: Boolean = true
    ) {
        try {
            // Ensure content can scroll behind system bars
            view.clipToPadding = false
            
            // Apply necessary padding
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                
                v.setPadding(
                    v.paddingLeft,
                    if (applyTopPadding) systemBarsInsets.top else v.paddingTop,
                    v.paddingRight,
                    if (applyBottomPadding) systemBarsInsets.bottom else v.paddingBottom
                )
                
                windowInsets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring scrollable view: ${e.message}")
        }
    }
} 