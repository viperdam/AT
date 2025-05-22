package com.viperdam.kidsprayer.state

sealed class LockScreenState {
    data class Locked(
        val prayerName: String,
        val rakaatCount: Int,
        val currentRakaat: Int = 0,
        val pinAttempts: Int = 0,
        val pinCooldownSeconds: Int = 0,
        val isLockedOut: Boolean = false,
        val errorMessage: String? = null
    ) : LockScreenState()

    data class PrayerInProgress(
        val prayerName: String,
        val totalRakaats: Int,
        val currentRakaat: Int,
        val currentPosition: String,
        val isCameraActive: Boolean,
        val errorMessage: String? = null
    ) : LockScreenState()

    data class PrayerComplete(
        val prayerName: String,
        val shouldShowAds: Boolean = true,
        val shouldAutoUnlock: Boolean = true
    ) : LockScreenState()

    data class Unlocked(
        val prayerName: String,
        val completionType: CompletionType
    ) : LockScreenState()

    enum class CompletionType {
        PIN_VERIFIED,
        PRAYER_PERFORMED,
        SYSTEM_UNLOCK
    }
}
