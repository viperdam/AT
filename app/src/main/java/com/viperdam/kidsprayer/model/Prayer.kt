package com.viperdam.kidsprayer.model

import com.viperdam.kidsprayer.PrayerApp
import com.viperdam.kidsprayer.time.TimeSourceManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

data class Prayer(
    val name: String,
    val time: Long,
    val rakaatCount: Int
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
    private val zoneId = ZoneId.systemDefault()

    fun getFormattedTime(): String {
        val localDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(time),
            zoneId
        )
        return localDateTime.format(timeFormatter)
    }

    fun getRemainingTime(): String {
        val timeManager = TimeSourceManager.getInstance(PrayerApp.getInstance())
        val now = timeManager.getCurrentTime()
        val diff = time - now
        
        if (diff <= 0) return "Now"
        
        val hours = diff / (1000 * 60 * 60)
        val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "Now"
        }
    }

    fun isUpcoming(): Boolean {
        val timeManager = TimeSourceManager.getInstance(PrayerApp.getInstance())
        return time > timeManager.getCurrentTime()
    }

    fun isPast(): Boolean {
        val timeManager = TimeSourceManager.getInstance(PrayerApp.getInstance())
        return time < timeManager.getCurrentTime()
    }

    fun isCurrent(): Boolean {
        val timeManager = TimeSourceManager.getInstance(PrayerApp.getInstance())
        val now = timeManager.getCurrentTime()
        return time <= now && now <= time + (30 * 60 * 1000) // Within 30 minutes
    }
}
