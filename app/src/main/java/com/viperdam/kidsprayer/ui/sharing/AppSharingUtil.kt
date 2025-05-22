package com.viperdam.kidsprayer.ui.sharing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.viperdam.kidsprayer.R
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class for sharing the app through various channels.
 * Provides multiple ways to share:
 * - Simple text sharing
 * - Rich media sharing with app icon
 * - Direct sharing to specific apps
 */
object AppSharingUtil {
    
    // Default share message
    private const val DEFAULT_SHARE_MESSAGE = "I'm using Adhan Time app to track prayer times accurately. Try it out!\n\n"
    private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id="
    
    /**
     * Simple method to share app via text only
     */
    fun shareAppViaText(context: Context) {
        val packageName = context.packageName
        val shareMessage = DEFAULT_SHARE_MESSAGE + PLAY_STORE_URL + packageName
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_app_subject))
            putExtra(Intent.EXTRA_TEXT, shareMessage)
        }
        
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
        } catch (e: Exception) {
            Log.e("AppSharingUtil", "Error sharing app", e)
        }
    }
    
    /**
     * Share app with app icon as an image (richer sharing experience)
     */
    fun shareAppWithImage(activity: AppCompatActivity) {
        val packageName = activity.packageName
        val shareMessage = DEFAULT_SHARE_MESSAGE + PLAY_STORE_URL + packageName
        
        try {
            // Get app icon as bitmap
            val bitmap = BitmapFactory.decodeResource(activity.resources, R.mipmap.ic_launcher)
            
            // Save bitmap to cache directory
            val cachePath = File(activity.cacheDir, "images")
            cachePath.mkdirs()
            val imagePath = File(cachePath, "app_icon.png")
            
            FileOutputStream(imagePath).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            // Get URI for the image file
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${packageName}.fileprovider",
                imagePath
            )
            
            // Create and launch sharing intent
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, shareMessage)
                putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.share_app_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_via)))
            
        } catch (e: Exception) {
            Log.e("AppSharingUtil", "Error sharing with image", e)
            // Fallback to simple text sharing
            shareAppViaText(activity)
        }
    }
    
    /**
     * Direct sharing to WhatsApp
     */
    fun shareToWhatsApp(context: Context) {
        val packageName = context.packageName
        val shareMessage = DEFAULT_SHARE_MESSAGE + PLAY_STORE_URL + packageName
        
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                `package` = "com.whatsapp"
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppSharingUtil", "WhatsApp not installed or error sharing", e)
            // Fallback to normal sharing
            shareAppViaText(context)
        }
    }
    
    /**
     * Direct sharing to Facebook
     */
    fun shareToFacebook(context: Context) {
        val packageName = context.packageName
        val shareUrl = PLAY_STORE_URL + packageName
        
        try {
            // Facebook sharing needs a different approach than WhatsApp
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                `package` = "com.facebook.katana"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppSharingUtil", "Facebook not installed or error sharing", e)
            // Fallback to normal sharing
            shareAppViaText(context)
        }
    }
    
    /**
     * Direct sharing via Email
     */
    fun shareViaEmail(context: Context) {
        val packageName = context.packageName
        val shareMessage = DEFAULT_SHARE_MESSAGE + PLAY_STORE_URL + packageName
        
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:") // only email apps should handle this
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_app_subject))
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // No email app, fallback to normal sharing
                shareAppViaText(context)
            }
        } catch (e: Exception) {
            Log.e("AppSharingUtil", "Error sharing via email", e)
            // Fallback to normal sharing
            shareAppViaText(context)
        }
    }
    
    /**
     * Full sharing dialog with multiple options
     */
    fun showCustomSharingDialog(activity: AppCompatActivity) {
        // This could be implemented with a custom dialog showing multiple
        // sharing options specific to your app's needs
        // For now, we'll just use the standard sharing
        shareAppWithImage(activity)
    }
} 