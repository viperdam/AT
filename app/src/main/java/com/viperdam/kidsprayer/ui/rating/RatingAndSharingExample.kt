package com.viperdam.kidsprayer.ui.rating

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ui.sharing.AppSharingDialog

/**
 * Example class showing how to use the rating and sharing features.
 * This is not meant to be used directly, but rather as a reference for
 * implementing these features in your actual activities.
 */
class RatingAndSharingExample : AppCompatActivity() {
    
    private lateinit var ratingDialogManager: RatingDialogManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Use your actual layout
        
        // Initialize the rating dialog manager
        ratingDialogManager = RatingDialogManager(this)
        
        // Track app launch for rating prompt
        ratingDialogManager.incrementAppLaunchCount()
        
        // Example button click listeners
        setupExampleListeners()
    }
    
    private fun setupExampleListeners() {
        // Example: Show rating dialog when a button is clicked
        // findViewById<Button>(R.id.btn_rate_app).setOnClickListener {
        //     showRatingDialog()
        // }
        
        // Example: Show sharing dialog when a button is clicked
        // findViewById<Button>(R.id.btn_share_app).setOnClickListener {
        //     showSharingDialog()
        // }
    }
    
    /**
     * Example of how to show the rating dialog.
     * Call this method when appropriate in your app flow.
     */
    private fun showRatingDialog() {
        // Option 1: Show dialog immediately
        RatingDialog(this).show()
        
        // Option 2: Show dialog only if conditions are met
        ratingDialogManager.showRatingDialogIfNeeded()
    }
    
    /**
     * Example of how to show the sharing dialog.
     * Call this method when appropriate in your app flow.
     */
    private fun showSharingDialog() {
        // Option 1: Show custom sharing dialog
        AppSharingDialog.show(this)
        
        // Option 2: Use direct sharing methods
        // AppSharingUtil.shareAppViaText(this)
        // AppSharingUtil.shareAppWithImage(this)
    }
    
    /**
     * Example of tracking prayer completion for rating prompt.
     * Call this method when a user completes a prayer.
     */
    private fun trackPrayerCompletion() {
        // Increment prayer completion count
        ratingDialogManager.incrementPrayerCompletionCount()
        
        // Check if we should show rating dialog
        if (ratingDialogManager.shouldShowRatingPrompt()) {
            RatingDialog(this).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Example: Check if we should show rating dialog
        // This is a good place to check, as the user has just returned to your app
        ratingDialogManager.showRatingDialogIfNeeded()
    }
} 