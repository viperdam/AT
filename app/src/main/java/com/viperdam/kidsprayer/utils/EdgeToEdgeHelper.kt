package com.viperdam.kidsprayer.utils

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Helper class for handling edge-to-edge implementation consistently across the app.
 * 
 * For Android 15 (API 35) and higher, edge-to-edge is automatically enforced for apps
 * targeting SDK 35+. This helper class ensures consistent implementation of
 * enableEdgeToEdge() and proper inset handling.
 */
object EdgeToEdgeHelper {
    private const val TAG = "EdgeToEdgeHelper"

    /**
     * Applies edge-to-edge display to an activity with standard inset handling
     * 
     * @param activity The activity to apply edge-to-edge to
     * @param rootView The root view of the activity layout for inset application
     * @param applyTopInsets Function to apply top insets to specific views (e.g., app bar)
     * @param applyBottomInsets Function to apply bottom insets to specific views (e.g., bottom nav)
     */
    fun setupEdgeToEdge(
        activity: ComponentActivity,
        rootView: View,
        applyTopInsets: ((insets: WindowInsetsCompat) -> Unit)? = null,
        applyBottomInsets: ((insets: WindowInsetsCompat) -> Unit)? = null
    ) {
        try {
            // Enable edge-to-edge using the standard Android function
            activity.enableEdgeToEdge()
            
            // Set up inset handling
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                
                // Apply custom insets to top UI elements if provided
                applyTopInsets?.invoke(windowInsets)
                
                // Apply custom insets to bottom UI elements if provided
                applyBottomInsets?.invoke(windowInsets)
                
                // Return the windowInsets to allow nested views to also handle insets
                windowInsets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying edge-to-edge to ${activity.javaClass.simpleName}: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    /**
     * Configure a RecyclerView for edge-to-edge display
     * Ensures last items aren't obscured by system bars
     */
    fun configureRecyclerViewForEdgeToEdge(recyclerView: RecyclerView) {
        // Prevent system bars from clipping scrollable content
        recyclerView.clipToPadding = false
    }
} 