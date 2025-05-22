package com.amors.kidsprayer.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages text rendering performance by caching layouts and warming up the TextLayoutCache
 * on background threads. This helps avoid ANRs caused by heavy text rendering operations.
 */
object TextCacheManager {
    // Cache for StaticLayout objects to avoid recreating them
    private val layoutCache = LruCache<String, Layout>(50)
    
    // Cache for SpannableString objects to avoid recreating them
    private val spannableCache = LruCache<String, SpannableString>(100)
    
    // Main thread handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Background scope for text processing
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    /**
     * Sets text on a TextView with optimized performance.
     * If the text is already cached, it will be used directly.
     * Otherwise, the text will be processed in the background.
     */
    fun setTextOptimized(textView: TextView, text: CharSequence) {
        val cacheKey = text.toString()
        
        // Check if we already have this text cached
        layoutCache.get(cacheKey)?.let {
            // We have it cached, set directly
            mainHandler.post {
                textView.text = text
            }
            return
        }
        
        // Process in background
        backgroundScope.launch {
            // Create a SpannableString if needed
            val spannable = if (text is SpannableString) {
                text
            } else {
                spannableCache.get(cacheKey) ?: SpannableString(text).also {
                    spannableCache.put(cacheKey, it)
                }
            }
            
            // Warm up the text rendering cache
            warmupTextCache(textView.context, spannable, textView.textSize, textView.width)
            
            // Update UI on main thread
            withContext(Dispatchers.Main) {
                textView.text = spannable
            }
        }
    }
    
    /**
     * Pre-renders text on a background thread to warm up the TextLayoutCache,
     * which significantly improves rendering performance when the text is 
     * actually displayed.
     */
    private fun warmupTextCache(context: Context, text: CharSequence, textSize: Float, width: Int) {
        val key = "${text}_${textSize}_${width}"
        
        // Check if we already warmed this up
        if (layoutCache.get(key) != null) return
        
        // Create TextPaint with the same properties as the TextView
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
        }
        
        // Calculate effective width for layout
        val effectiveWidth = if (width > 0) width else 1000
        
        // Create layout object (this warms up internal caches)
        val layout = StaticLayout.Builder.obtain(
            text, 0, text.length, paint, effectiveWidth
        ).build()
        
        // Draw on an offscreen canvas to warm up the glyph cache
        val canvas = Canvas()
        layout.draw(canvas)
        
        // Cache for future use
        layoutCache.put(key, layout)
    }
    
    /**
     * Preloads a list of text strings in the background.
     * Useful for warming up caches before they're needed, like during app startup
     * or when loading a new screen.
     */
    fun preloadTexts(context: Context, texts: List<CharSequence>, textSize: Float) {
        backgroundScope.launch {
            texts.forEach { text ->
                warmupTextCache(context, text, textSize, 0)
            }
        }
    }
    
    /**
     * Clears the cache when no longer needed to free up memory
     */
    fun clearCache() {
        layoutCache.evictAll()
        spannableCache.evictAll()
    }
} 