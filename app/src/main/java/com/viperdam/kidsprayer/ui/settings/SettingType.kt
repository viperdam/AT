package com.viperdam.kidsprayer.ui.settings

enum class SettingType {
    // Basic prayer settings
    ENABLED,
    ADHAN,
    NOTIFICATION,
    LOCK,

    // Adhan settings
    ADHAN_VOLUME,
    ADHAN_VIBRATION,
    ADHAN_DND_OVERRIDE,

    // Notification settings
    NOTIFICATION_TIME,
    NOTIFICATION_VIBRATION,
    NOTIFICATION_DND_OVERRIDE,

    // Lock screen settings
    LOCK_SCREEN_CAMERA,
    LOCK_SCREEN_PIN
}
