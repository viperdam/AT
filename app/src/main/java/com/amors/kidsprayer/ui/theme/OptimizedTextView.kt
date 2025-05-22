package com.amors.kidsprayer.ui.theme

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A custom TextView implementation that automatically optimizes text rendering
 * to avoid ANRs when displaying large or complex text.
 */
class OptimizedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /**
     * Override setText to use our optimized version for long or complex text
     */
    override fun setText(text: CharSequence?, type: BufferType?) {
        text?.let {
            if (it.length > 100) {
                // For longer text, process in background
                TextCacheManager.setTextOptimized(this, it)
            } else {
                // For shorter text, use the standard method
                super.setText(text, type)
            }
        } ?: super.setText(text, type)
    }

    /**
     * Called when the view is detached from window.
     * Release resources to avoid memory leaks.
     */
    override fun onDetachedFromWindow() {
        // No need to clear global cache here, but if we had
        // view-specific resources, we would clear them
        super.onDetachedFromWindow()
    }
} 