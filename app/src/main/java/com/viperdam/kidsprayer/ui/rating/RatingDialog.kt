package com.viperdam.kidsprayer.ui.rating

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.RenderMode
import com.google.android.play.core.review.ReviewManagerFactory
import com.viperdam.kidsprayer.R

/**
 * A custom dialog for showing app rating prompt with star ratings and animated characters.
 * Features different states for different rating levels and collects feedback for low ratings.
 */
class RatingDialog(private val activity: AppCompatActivity) : Dialog(activity) {
    
    private lateinit var animationView: LottieAnimationView
    private lateinit var ratingTitle: TextView
    private lateinit var ratingSubtitle: TextView
    private lateinit var starsContainer: LinearLayout
    private lateinit var feedbackText: EditText
    private lateinit var submitButton: Button
    private lateinit var notNowButton: Button
    
    private var currentRating = 0
    private val ratingDialogManager = RatingDialogManager(activity)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app_rating)
        
        // Set dialog window properties
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        
        // Initialize views
        initViews()
        setupStarRating()
        setInitialAnimation()
        
        // Set click listeners
        notNowButton.setOnClickListener {
            ratingDialogManager.recordPromptDismissed()
            dismiss()
        }
        
        submitButton.setOnClickListener {
            // Initial state - button is disabled until rating is selected
            if (currentRating == 0) return@setOnClickListener
            
            if (currentRating >= 4) {
                // High rating - launch store review
                launchPlayStoreReview()
            } else {
                // Low rating - send feedback
                val feedback = feedbackText.text.toString()
                sendFeedback(feedback, currentRating)
            }
            dismiss()
        }
    }
    
    private fun initViews() {
        animationView = findViewById(R.id.animation_view)
        ratingTitle = findViewById(R.id.tv_rating_title)
        ratingSubtitle = findViewById(R.id.tv_rating_subtitle)
        starsContainer = findViewById(R.id.rating_stars)
        feedbackText = findViewById(R.id.feedback_text)
        submitButton = findViewById(R.id.btn_submit)
        notNowButton = findViewById(R.id.btn_not_now)
        
        // Initially disable submit button
        submitButton.isEnabled = false
    }
    
    private fun setInitialAnimation() {
        animationView.setAnimation("animations/initial_rating.json")
        animationView.setRenderMode(RenderMode.AUTOMATIC)
        animationView.setSafeMode(true)
        animationView.setFailureListener { throwable ->
            Log.e("LottieError", "Error loading animation", throwable)
        }
        animationView.playAnimation()
    }
    
    private fun setupStarRating() {
        // Clear any existing stars
        starsContainer.removeAllViews()
        
        // Configuration
        val starSize = context.resources.getDimensionPixelSize(R.dimen.star_size)
        val starMargin = context.resources.getDimensionPixelSize(R.dimen.star_margin)
        
        // Add 5 stars
        for (i in 1..5) {
            val starView = ImageView(context).apply {
                id = View.generateViewId()
                setImageResource(R.drawable.ic_star_outline)
                layoutParams = LinearLayout.LayoutParams(starSize, starSize).apply {
                    marginEnd = starMargin
                }
                tag = i // Store star position
                
                // Handle star clicks
                setOnClickListener { view ->
                    val position = view.tag as Int
                    currentRating = position
                    updateStars(position)
                    updateDialogForRating(position)
                }
                
                // Add padding for better touch target
                setPadding(context.resources.getDimensionPixelSize(R.dimen.min_touch_padding))
            }
            starsContainer.addView(starView)
        }
    }
    
    private fun updateStars(selectedPosition: Int) {
        // Update all stars based on selected position
        for (i in 0 until starsContainer.childCount) {
            val starView = starsContainer.getChildAt(i) as ImageView
            val position = starView.tag as Int
            
            // Fill stars up to selected position
            if (position <= selectedPosition) {
                starView.setImageResource(R.drawable.ic_star_filled)
                // Add a small animation
                starView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(200)
                    .withEndAction {
                        starView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()
            } else {
                starView.setImageResource(R.drawable.ic_star_outline)
            }
        }
    }
    
    private fun updateDialogForRating(rating: Int) {
        // Enable submit button once rating is selected
        submitButton.isEnabled = true
        
        if (rating >= 4) {
            // High rating - show Play Store option
            feedbackText.visibility = View.GONE
            submitButton.text = context.getString(R.string.rate_on_play_store)
            ratingTitle.text = context.getString(R.string.rating_title_positive)
            ratingSubtitle.text = context.getString(R.string.rating_subtitle_positive)
            
            // Happy animation
            animationView.setAnimation("animations/happy_rating.json")
            animationView.playAnimation()
        } else {
            // Low rating - show feedback form
            feedbackText.visibility = View.VISIBLE
            submitButton.text = context.getString(R.string.send_feedback)
            ratingTitle.text = context.getString(R.string.rating_title_negative)
            ratingSubtitle.text = context.getString(R.string.rating_subtitle_negative)
            
            // Sad animation
            animationView.setAnimation("animations/sad_rating.json")
            animationView.playAnimation()
        }
    }
    
    private fun launchPlayStoreReview() {
        // Mark that user has rated the app
        ratingDialogManager.markAsRated()
        
        try {
            // Try to use the new in-app review API
            val manager = ReviewManagerFactory.create(context)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // We got the ReviewInfo object
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity, reviewInfo)
                    flow.addOnCompleteListener {
                        // The flow has finished - but the dialog may not have been shown
                        Log.d("RatingDialog", "Review flow completed")
                        // Force fallback to direct Play Store method to ensure user can leave review
                        openPlayStoreForReview()
                    }
                } else {
                    // There was some problem, use the default method
                    openPlayStoreForReview()
                }
            }
        } catch (e: Exception) {
            // Fallback to the default method
            Log.e("RatingDialog", "Error using review API", e)
            openPlayStoreForReview()
        }
    }
    
    private fun openPlayStoreForReview() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=${context.packageName}")
                // Simplify flags - the multiple task flag might be causing issues
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("RatingDialog", "Error opening Play Store app", e)
                // Fallback to browser if first attempt fails
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
        } catch (e: ActivityNotFoundException) {
            // If Play Store app is not installed, open browser
            try {
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e: Exception) {
                Log.e("RatingDialog", "Failed to open Play Store in browser", e)
            }
        } catch (e: Exception) {
            Log.e("RatingDialog", "Unexpected error opening Play Store", e)
        }
    }
    
    private fun sendFeedback(feedback: String, rating: Int) {
        // Store the feedback
        ratingDialogManager.recordFeedback(feedback, rating)
        
        // Here you would implement your own feedback handling:
        // - Send to a server
        // - Save to Firebase
        // - Email to support
        // - etc.
        
        // Example: Email feedback to support
        val subject = "Adhan Time App Feedback (rating: $rating stars)"
        
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:support@yourdomain.com")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, feedback)
        }
        
        try {
            context.startActivity(Intent.createChooser(intent, "Send Feedback"))
        } catch (e: Exception) {
            Log.e("RatingDialog", "Could not send feedback email", e)
        }
    }
} 