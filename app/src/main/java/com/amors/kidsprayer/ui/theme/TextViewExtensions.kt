package com.amors.kidsprayer.ui.theme

import android.widget.TextView

/**
 * Sets text on a TextView with optimized performance.
 * This helps prevent ANRs by processing text on a background thread
 * and warming up the text rendering cache before displaying.
 */
fun TextView.setTextOptimized(text: CharSequence) {
    TextCacheManager.setTextOptimized(this, text)
}

/**
 * A safer version of setText that doesn't cause ANRs with large text.
 * It essentially wraps setTextOptimized but maintains the standard TextView API.
 */
fun TextView.setTextSafely(text: CharSequence?) {
    text?.let {
        if (it.length > 100) {
            // For longer text, use our optimized method
            setTextOptimized(it)
        } else {
            // For short text, the standard method is fine
            this.text = it
        }
    } ?: run {
        // If null, just set empty text
        this.text = ""
    }
} 