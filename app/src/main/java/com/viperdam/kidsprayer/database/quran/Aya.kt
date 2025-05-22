package com.viperdam.kidsprayer.database.quran

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a single Ayah (verse) of the Quran in the database.
 * Schema based on the user's NEW quran.db file and provided documentation.
 */
@Entity(
    tableName = "quran", // Use the correct table name from the image
    // Add index for faster surah lookup (using the 'sora' column)
    indices = [Index(value = ["sora"])]
)
data class Aya(
    // Map columns based on the image and documentation
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "jozz")
    val jozz: Int,

    // Use 'sora' column for Surah number
    @ColumnInfo(name = "sora")
    val surahNumber: Int,

    @ColumnInfo(name = "sora_name_en")
    val soraNameEn: String,

    @ColumnInfo(name = "sora_name_ar")
    val soraNameAr: String,

    @ColumnInfo(name = "page")
    val page: Int,

    @ColumnInfo(name = "line_start")
    val lineStart: Int,

    @ColumnInfo(name = "line_end")
    val lineEnd: Int,

    // Use 'aya_no' column for Ayah number
    @ColumnInfo(name = "aya_no")
    val ayahNumber: Int,

    @ColumnInfo(name = "aya_text")
    val ayaText: String, // Text without diacritics?

    @ColumnInfo(name = "aya_text_emlaey")
    val ayaTextEmlaey: String,

    @ColumnInfo(name = "maany_aya")
    val maanyAya: String,

    @ColumnInfo(name = "earab_quran")
    val earabQuran: String,

    @ColumnInfo(name = "reasons_of_verses")
    val reasonsOfVerses: String,

    @ColumnInfo(name = "tafseer_saadi")
    val tafseerSaadi: String,

    @ColumnInfo(name = "tafseer_moysar")
    val tafseerMoysar: String,

    @ColumnInfo(name = "tafseer_bughiu")
    val tafseerBughiu: String,

    // Use 'aya_text_tashkil' for the display text
    @ColumnInfo(name = "aya_text_tashkil")
    val arabicText: String // Renamed for consistency with UI
) 