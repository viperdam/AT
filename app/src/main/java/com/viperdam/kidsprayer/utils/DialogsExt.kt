package com.viperdam.kidsprayer.utils

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialDialogs

/**
 * Utility functions for dialogs in Android 15+ to replace deprecated methods.
 * 
 * This provides modern replacements for:
 * - Window.setStatusBarColor
 * - Window.setNavigationBarColor
 * - Using other deprecated methods in dialogs
 */
object DialogsExt {
    
    /**
     * Apply modern edge-to-edge display to any dialog window
     * 
     * @param window The dialog window
     */
    fun applyEdgeToEdgeToDialog(window: Window) {
        // Make the dialog draw edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Apply proper insets via listener
        val decorView = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { view, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Apply insets to the dialog content
            val contentView = view.findViewById<View>(android.R.id.content)
            contentView?.setPadding(
                contentView.paddingLeft,
                systemBarsInsets.top + contentView.paddingTop,
                contentView.paddingRight,
                systemBarsInsets.bottom + contentView.paddingBottom
            )
            
            // Return insets for nested views
            windowInsets
        }
    }
    
    /**
     * Extension function to apply edge-to-edge to a DialogFragment
     */
    fun DialogFragment.applyEdgeToEdge() {
        dialog?.window?.let { window ->
            applyEdgeToEdgeToDialog(window)
        }
    }
    
    /**
     * Extension function to apply a background color to the status bar area
     * instead of using window.setStatusBarColor (which is deprecated)
     * 
     * @param color The color to apply to status bar area
     */
    fun Dialog.applyStatusBarBackgroundColor(color: Int) {
        window?.decorView?.let { decorView ->
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { view, windowInsets ->
                val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                
                // Find or create a view at the top for status bar background
                val statusBarBg = view.findViewWithTag<View>("status_bar_bg") ?: View(context).apply {
                    tag = "status_bar_bg"
                    (decorView as? ViewGroup)?.addView(this)
                }
                
                // Position and color the view
                val params = statusBarBg.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    statusBarInsets.top
                )
                params.height = statusBarInsets.top
                statusBarBg.layoutParams = params
                statusBarBg.setBackgroundColor(color)
                
                windowInsets
            }
        }
    }
    
    /**
     * Extension function to apply a background color to the navigation bar area
     * instead of using window.setNavigationBarColor (which is deprecated)
     * 
     * @param color The color to apply to navigation bar area
     */
    fun Dialog.applyNavigationBarBackgroundColor(color: Int) {
        window?.decorView?.let { decorView ->
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { view, windowInsets ->
                val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                
                // Find or create a view at the bottom for nav bar background
                val navBarBg = view.findViewWithTag<View>("nav_bar_bg") ?: View(context).apply {
                    tag = "nav_bar_bg"
                    (decorView as? ViewGroup)?.addView(this)
                }
                
                // Position and color the view
                val params = navBarBg.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    navBarInsets.bottom
                )
                params.height = navBarInsets.bottom
                navBarBg.layoutParams = params
                navBarBg.setBackgroundColor(color)
                
                windowInsets
            }
        }
    }
} 