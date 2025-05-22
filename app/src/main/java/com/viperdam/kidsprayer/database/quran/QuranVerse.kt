package com.viperdam.kidsprayer.database.quran

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a single Ayah (verse) of the Quran in the database.
 * Updated AGAIN to fully match the complex schema found in the user's quran.db 'verses' table.
 */
@Entity(
    tableName = "verses",
    indices = [Index(value = ["surah"])]
)
data class QuranVerse(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int, // INTEGER NOT NULL

    // Temporarily commented out for KSP debugging
    // /*
    @ColumnInfo(name = "verse_pk")
    val versePk: String, // TEXT NOT NULL

    @ColumnInfo(name = "page")
    val page: Int, // INTEGER NOT NULL

    @ColumnInfo(name = "hizbQuarter")
    val hizbQuarter: Int, // INTEGER NOT NULL

    @ColumnInfo(name = "juz")
    val juz: Int, // INTEGER NOT NULL
    // */

    @ColumnInfo(name = "surah")
    val surahNumber: Int, // INTEGER NOT NULL

    @ColumnInfo(name = "verse")
    val arabicText: String, // TEXT NOT NULL

    // Temporarily commented out for KSP debugging
    @ColumnInfo(name = "verseWithoutTaskeel")
    val verseWithoutTaskeel: String, // TEXT NOT NULL

    @ColumnInfo(name = "numberInSurah")
    val ayahNumber: Int, // INTEGER NOT NULL

    // Temporarily commented out for KSP debugging
    // /*
    @ColumnInfo(name = "numberInQuran")
    val numberInQuran: Int, // INTEGER NOT NULL

    @ColumnInfo(name = "audio")
    val audio: String, // TEXT NOT NULL

    // Based on schema log, audio1 and audio2 CAN be null
    @ColumnInfo(name = "audio1")
    val audio1: String?, // TEXT NULL

    @ColumnInfo(name = "audio2")
    val audio2: String?, // TEXT NULL

    // Let the TypeConverter handle the mapping from boolean/int in DB to Boolean in Kotlin
    // Revert: Try Int type WITH explicit INTEGER affinity again for schema validation.
    @ColumnInfo(name = "sajda", typeAffinity = ColumnInfo.INTEGER)
    val sajda: Int // Changed back to Int, added explicit INTEGER affinity
    // */
) 