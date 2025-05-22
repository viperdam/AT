package com.amors.kidsprayer.ui.theme

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.Log

/**
 * Utility to apply text optimizations to existing TextViews in layouts.
 * This is useful for optimizing legacy code without having to modify every layout file.
 */
object TextOptimizationApplier {
    private const val TAG = "TextOptimizationApplier"
    
    /**
     * Apply optimizations to all TextViews in an activity
     */
    fun optimizeAllTextViewsInActivity(activity: Activity) {
        try {
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            optimizeTextViewsInViewGroup(rootView)
            Log.d(TAG, "Applied text optimizations to activity: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing TextViews in activity", e)
        }
    }
    
    /**
     * Apply optimizations to TextViews in a specific layout
     */
    fun optimizeTextViewsInLayout(rootView: View) {
        try {
            if (rootView is ViewGroup) {
                optimizeTextViewsInViewGroup(rootView)
            } else if (rootView is TextView) {
                optimizeTextView(rootView)
            }
            Log.d(TAG, "Applied text optimizations to layout")
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing TextViews in layout", e)
        }
    }
    
    /**
     * Apply optimizations to all TextViews in a ViewGroup recursively
     */
    private fun optimizeTextViewsInViewGroup(viewGroup: ViewGroup) {
        val childCount = viewGroup.childCount
        for (i in 0 until childCount) {
            val view = viewGroup.getChildAt(i)
            
            when (view) {
                is TextView -> optimizeTextView(view)
                is ViewGroup -> optimizeTextViewsInViewGroup(view)
            }
        }
    }
    
    /**
     * Apply optimizations to a single TextView
     */
    private fun optimizeTextView(textView: TextView) {
        // Get current text
        val currentText = textView.text
        
        // Only optimize if there's actually text to optimize
        if (!currentText.isNullOrEmpty() && currentText.length > 100) {
            // Save current text
            val text = currentText.toString()
            
            // Clear text to avoid it being rendered inefficiently
            textView.text = ""
            
            // Set text using our optimized method
            textView.setTextSafely(text)
        }
    }
} 