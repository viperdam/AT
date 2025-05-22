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
 * Extension functions to provide modern replacements for deprecated MaterialDatePicker methods
 * specifically for Android 15+ (API 35+).
 */
object MaterialPickerExt {

    /**
     * Applies modern edge-to-edge handling to the MaterialDatePicker dialog.
     * This should be called within an `addOnPositiveButtonClickListener` or `addOnShowListener`.
     * Usage: `yourDatePicker.enableModernEdgeToEdge()`
     */
    fun MaterialDatePicker<*>.enableModernEdgeToEdge() {
        val dialog = this.dialog ?: return
        val window = dialog.window ?: return

        // Allow window to draw edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Find the root content view of the dialog
        val contentView = dialog.findViewById<View>(android.R.id.content) ?: return

        // Store initial padding of the content view
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
} 