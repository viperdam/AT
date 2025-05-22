package com.viperdam.kidsprayer.model

data class PrayerSetting(
    // Basic prayer settings
    var enabled: Boolean = true,
    var adhanEnabled: Boolean = true,
    var notificationEnabled: Boolean = true,
    var lockEnabled: Boolean = true,

    // Adhan settings
    var adhanVolume: Float = 0.7f,
    var adhanVibration: Boolean = true,
    var adhanDndOverride: Boolean = false,

    // Notification settings
    var notificationTime: Int = 15, // minutes before prayer time
    var notificationVibration: Boolean = true,
    var notificationDndOverride: Boolean = false,

    // Lock screen settings
    var lockScreenCamera: Boolean = false,
    var lockScreenPin: Boolean = false
)
