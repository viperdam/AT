package com.viperdam.kidsprayer.ml

enum class PrayerPosition {
    UNKNOWN,
    STANDING,
    STANDING_CONFIRMED, // Added for 5-second standing confirmation
    BOWING,
    PROSTRATION,
    SITTING
}
