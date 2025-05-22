package com.viperdam.kidsprayer.ui.sharing

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.viperdam.kidsprayer.R

/**
 * A custom dialog for app sharing with multiple options
 * including social media platforms and email.
 */
class AppSharingDialog(private val activity: AppCompatActivity) : Dialog(activity) {
    
    private lateinit var whatsappButton: LinearLayout
    private lateinit var facebookButton: LinearLayout
    private lateinit var emailButton: LinearLayout
    private lateinit var moreButton: LinearLayout
    private lateinit var closeButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app_sharing)
        
        // Set dialog window properties
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        
        // Initialize views
        initViews()
        
        // Set click listeners
        whatsappButton.setOnClickListener {
            AppSharingUtil.shareToWhatsApp(context)
            dismiss()
        }
        
        facebookButton.setOnClickListener {
            AppSharingUtil.shareToFacebook(context)
            dismiss()
        }
        
        emailButton.setOnClickListener {
            AppSharingUtil.shareViaEmail(context)
            dismiss()
        }
        
        moreButton.setOnClickListener {
            AppSharingUtil.shareAppViaText(context)
            dismiss()
        }
        
        closeButton.setOnClickListener {
            dismiss()
        }
    }
    
    private fun initViews() {
        whatsappButton = findViewById(R.id.btn_share_whatsapp)
        facebookButton = findViewById(R.id.btn_share_facebook)
        emailButton = findViewById(R.id.btn_share_email)
        moreButton = findViewById(R.id.btn_share_more)
        closeButton = findViewById(R.id.btn_close)
    }
    
    companion object {
        /**
         * Shows the app sharing dialog
         */
        fun show(activity: AppCompatActivity) {
            AppSharingDialog(activity).show()
        }
    }
} 