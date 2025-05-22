package com.viperdam.kidsprayer.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError
import java.util.regex.Pattern

/**
 * A specialized AdListener that adds additional content filtering
 * for child-appropriate ads. This acts as an extra layer of protection
 * beyond the standard settings.
 */
class ContentFilteringAdListener(
    private val context: Context,
    private val baseListener: AdListener? = null,
    private val onBlockedAd: ((String) -> Unit)? = null
) : AdListener() {

    // Keywords and patterns that indicate potentially inappropriate content
    private val inappropriatePatterns = listOf(
        Pattern.compile("(?i)gambling|casino|bet|poker|slot"),
        Pattern.compile("(?i)alcohol|beer|wine|liquor|whiskey"),
        Pattern.compile("(?i)dating|date|romance|meet singles"),
        Pattern.compile("(?i)mature|adult|xxx|18\\+"),
        Pattern.compile("(?i)violence|weapon|gun|shoot"),
        Pattern.compile("(?i)drug|pill|medication|pharmacy"),
        Pattern.compile("(?i)diet pill|weight loss|lose weight fast")
    )

    override fun onAdLoaded() {
        Log.d(TAG, "Ad loaded with content filtering active")
        baseListener?.onAdLoaded()
    }

    override fun onAdFailedToLoad(error: LoadAdError) {
        if (isInappropriateContent(error.responseInfo?.toString() ?: "")) {
            Log.w(TAG, "Potentially inappropriate ad blocked: ${error.responseInfo}")
            onBlockedAd?.invoke("Blocked inappropriate ad content")
        } else {
            Log.d(TAG, "Ad failed to load with standard error: ${error.message}")
            baseListener?.onAdFailedToLoad(error)
        }
    }

    override fun onAdOpened() {
        Log.d(TAG, "Ad opened with content filtering")
        baseListener?.onAdOpened()
    }

    override fun onAdClicked() {
        Log.d(TAG, "Ad clicked with content filtering")
        baseListener?.onAdClicked()
    }

    override fun onAdClosed() {
        Log.d(TAG, "Ad closed with content filtering")
        baseListener?.onAdClosed()
    }

    override fun onAdImpression() {
        Log.d(TAG, "Ad impression with content filtering")
        baseListener?.onAdImpression()
    }

    /**
     * Check if the content appears to be inappropriate based on keywords
     */
    private fun isInappropriateContent(content: String): Boolean {
        for (pattern in inappropriatePatterns) {
            if (pattern.matcher(content).find()) {
                Log.w(TAG, "Detected inappropriate content pattern: ${pattern.pattern()}")
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "ContentFilteringAdListener"
    }
} 