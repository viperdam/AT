package com.viperdam.kidsprayer.utils

import android.app.Dialog
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.datepicker.MaterialDatePicker

/**
 * Extension function to apply edge-to-edge display to MaterialDatePicker in a modern way
 * that replaces the deprecated MaterialDatePicker.enableEdgeToEdgeIfNeeded method.
 *
 * This should be called after showing the dialog, typically in addOnShowListener.
 */
fun <S> MaterialDatePicker<S>.applyModernEdgeToEdge() {
    dialog?.let { dialog ->
        // Enable edge-to-edge for the dialog window using the general Dialog extension
        dialog.applyModernEdgeToEdge()
    }
}

/**
 * Extension function to apply edge-to-edge display to a Dialog in a modern way.
 * This is useful for any dialog, not just MaterialDatePicker.
 */
fun Dialog.applyModernEdgeToEdge() {
    val window = this.window ?: return
    // Allow drawing edge-to-edge
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Find the root content view
    val contentView = findViewById<View>(android.R.id.content) ?: return
    // Store initial padding
    val initialPadding = Rect(contentView.paddingLeft, contentView.paddingTop, contentView.paddingRight, contentView.paddingBottom)

    // Apply insets listener to the content view
    ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

        // Apply padding based on system bars and IME, preserving initial padding
        view.updatePadding(
            left = initialPadding.left + systemBars.left,
            top = initialPadding.top + systemBars.top,
            right = initialPadding.right + systemBars.right,
            // Adjust bottom padding for both system bars and IME
            bottom = initialPadding.bottom + maxOf(systemBars.bottom, ime.bottom)
        )
        // Consume the insets
        WindowInsetsCompat.CONSUMED
    }
} 