package com.viperdam.kidsprayer.database.quran

import androidx.room.TypeConverter

/**
 * Type converters to allow Room to map complex types.
 */
object Converters {
    /**
     * Converts an Integer (0 or 1) from the database to a Boolean.
     * Handles the case where SQLite stores boolean as INTEGER.
     */
    @TypeConverter
    @JvmStatic
    fun fromIntToBoolean(value: Int?): Boolean? {
        // Consider 1 as true, anything else (including null or 0) as false.
        // Adjust logic if your DB uses different values or needs stricter null handling.
        return value == 1
    }

    /**
     * Converts a Boolean to an Integer (0 or 1) for database storage.
     */
    @TypeConverter
    @JvmStatic
    fun fromBooleanToInt(value: Boolean?): Int? {
        return if (value == true) 1 else 0
    }

    // Add other converters here if needed for other types (e.g., Date, List<String>, etc.)
} 