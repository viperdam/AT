package com.amors.kidsprayer.ui.theme

import android.content.Context
import android.util.Log

/**
 * Utility class to preload frequently used texts to avoid rendering delays
 * when they're actually displayed.
 */
object TextPreloader {
    private const val TAG = "TextPreloader"
    
    /**
     * Preloads common texts used throughout the app
     */
    fun preloadCommonTexts(context: Context) {
        try {
            // Common texts that appear frequently in the app
            val textsToPreload = listOf(
                "Welcome",
                "Muslim Prayer Times",
                "Next Prayer at",
                "Daily Athkar & Islamic Hijri Calendar",
                "Tasbih",
                "Count the",
                "99 Name of",
                "Allah",
                "Setting",
                "Notifications",
                "Hajj Journey",
                "Prayer Times"
            )
            
            // Arabic texts that might be complex to render
            val arabicTexts = listOf(
                "اللَّهُمَّ افْتَحْ لِي أَبْوَابَ رَحْمَتِكَ",
                "سُبْحَانَ اللَّهِ",
                "الْحَمْدُ لِلَّهِ",
                "اللَّهُ أَكْبَرُ"
            )
            
            // Common text sizes used in the app
            val textSizes = listOf(12f, 14f, 16f, 18f, 20f, 24f, 28f)
            
            // Preload regular texts at different sizes
            textSizes.forEach { size ->
                TextCacheManager.preloadTexts(context, textsToPreload, size)
            }
            
            // Preload Arabic texts at common sizes for Arabic text
            TextCacheManager.preloadTexts(context, arabicTexts, 18f)
            TextCacheManager.preloadTexts(context, arabicTexts, 20f)
            
            Log.d(TAG, "Preloaded ${textsToPreload.size + arabicTexts.size} common texts")
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading texts", e)
        }
    }
    
    /**
     * Preloads specific texts for a particular screen
     */
    fun preloadTextsForScreen(context: Context, texts: List<String>, textSize: Float = 16f) {
        try {
            TextCacheManager.preloadTexts(context, texts, textSize)
            Log.d(TAG, "Preloaded ${texts.size} texts for screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading texts for screen", e)
        }
    }
} 