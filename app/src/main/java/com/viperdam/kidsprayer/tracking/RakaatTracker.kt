package com.viperdam.kidsprayer.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RakaatState(
    val currentRakaat: Int = 0,
    val currentPosition: PrayerPosition = PrayerPosition.STANDING,
    val isComplete: Boolean = false,
    val errorMessage: String? = null
)

enum class PrayerPosition {
    STANDING,
    BOWING,
    PROSTRATING,
    SITTING;

    override fun toString(): String = when(this) {
        STANDING -> "Standing"
        BOWING -> "Bowing"
        PROSTRATING -> "Prostrating"
        SITTING -> "Sitting"
    }
}

class RakaatTracker(private val totalRakaats: Int) {
    private val _state = MutableStateFlow(RakaatState())
    val state: StateFlow<RakaatState> = _state.asStateFlow()

    private var positionSequence = mutableListOf<PrayerPosition>()
    private var currentSequenceIndex = 0

    init {
        resetSequence()
    }

    fun updatePosition(newPosition: PrayerPosition) {
        val currentState = _state.value
        
        if (currentState.isComplete) return

        if (currentSequenceIndex >= positionSequence.size) {
            _state.value = currentState.copy(
                errorMessage = "Unexpected prayer position"
            )
            return
        }

        if (newPosition == positionSequence[currentSequenceIndex]) {
            currentSequenceIndex++
            
            if (currentSequenceIndex >= positionSequence.size) {
                // Completed one rakaat
                val nextRakaat = currentState.currentRakaat + 1
                if (nextRakaat >= totalRakaats) {
                    _state.value = currentState.copy(
                        currentRakaat = nextRakaat,
                        currentPosition = newPosition,
                        isComplete = true
                    )
                } else {
                    _state.value = currentState.copy(
                        currentRakaat = nextRakaat,
                        currentPosition = newPosition
                    )
                    resetSequence()
                }
            } else {
                _state.value = currentState.copy(
                    currentPosition = newPosition,
                    errorMessage = null
                )
            }
        } else {
            _state.value = currentState.copy(
                errorMessage = "Incorrect prayer position. Expected ${positionSequence[currentSequenceIndex]}, got $newPosition"
            )
        }
    }

    fun reset() {
        _state.value = RakaatState()
        resetSequence()
    }

    private fun resetSequence() {
        positionSequence = mutableListOf(
            PrayerPosition.STANDING,    // Initial standing
            PrayerPosition.BOWING,      // Ruku
            PrayerPosition.STANDING,    // Stand from Ruku
            PrayerPosition.PROSTRATING, // First Sajdah
            PrayerPosition.SITTING,     // Sit between Sajdahs
            PrayerPosition.PROSTRATING, // Second Sajdah
            PrayerPosition.STANDING     // Stand for next Rakaat
        )
        currentSequenceIndex = 0
    }
}
