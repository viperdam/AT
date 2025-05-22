package com.viperdam.kidsprayer.ml

import android.util.Log
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.viperdam.kidsprayer.ml.PrayerPosition
import com.viperdam.kidsprayer.ml.PrayerPositionClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RakaatTracker(private val totalRakaats: Int) {
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    // Buffer for position smoothing
    private val positionBuffer = ArrayDeque<PrayerPosition>(BUFFER_SIZE)
    private var confidenceThreshold = 0.7f // Balanced threshold for stability and responsiveness
    private var stablePositionCount = 0 // Counter for stable position detection
    private var lastStablePosition = PrayerPosition.UNKNOWN // Track last stable position

    data class TrackerState(
        val currentRakaat: Int = 0,
        val currentPosition: PrayerPosition = PrayerPosition.UNKNOWN,
        val isComplete: Boolean = false,
        val shouldAutoUnlock: Boolean = false,
        val errorMessage: String? = null
    )

    companion object {
        private const val TAG = "RakaatTracker"
        private const val STANDING_MIN_TIME = 3000L // 3 seconds for standing
        private const val BOWING_MIN_TIME = 1000L // 1 seconds for bowing
        private const val SITTING_MIN_TIME = 2000L // 2 seconds for sitting
        private const val OTHER_MIN_TIME = 2000L // 2 seconds for other positions
        private const val MAX_POSITION_TIME = 180000L // 3 minutes max
        private const val MAX_TRANSITION_TIME = 120000L // 2 minutes
        private const val MIN_POSITION_TIME = 300L // Minimum time in a position
        private const val BUFFER_SIZE = 6
        private const val STABLE_POSITION_THRESHOLD = 2
        private const val FINAL_UNLOCK_DELAY = 25000L // 25 seconds delay for final unlock
    }

    private var currentRakaat = 0
    private var currentPosition = PrayerPosition.UNKNOWN
    private var positionStartTime = 0L
    
    // Track completion status for each required position
    private var standingCompleted = false
    private var bowingCompleted = false
    private var sittingCompleted = false
    
    // Track prayer sequence and positions
    private val simplifiedRakaatSequence = listOf(
        PrayerPosition.STANDING,
        PrayerPosition.BOWING,
        PrayerPosition.SITTING
    )
    private val positionsInCurrentRakaat = mutableListOf<PrayerPosition>()
    private val positionDurations = mutableMapOf<PrayerPosition, Long>()
    
    // Constants for required durations
    private val STANDING_REQUIRED_TIME = 3000L // 3 seconds
    private val BOWING_REQUIRED_TIME = 1000L // 1 seconds
    private val SITTING_REQUIRED_TIME = 2000L // 2 seconds

    init {
        confidenceThreshold = 0.7f // Balanced threshold for stability and responsiveness
        reset()
    }

    private var expectedPosition = PrayerPosition.STANDING
    private var lastValidPosition = PrayerPosition.UNKNOWN
    private var lastValidPositionTime = 0L
    private var finalUnlockTimer: Long = 0L

    fun processPosition(landmarks: List<NormalizedLandmark>) {
        val newPosition = PrayerPositionClassifier.classifyPose(landmarks)
        try {
            val currentTime = System.currentTimeMillis()
            
            // Only process if we have a valid position and it matches the expected position
            if (newPosition != PrayerPosition.UNKNOWN && newPosition == expectedPosition) {
                // If this is a new valid position or we're starting a new timing
                if (newPosition != lastValidPosition) {
                    lastValidPosition = newPosition
                    currentPosition = newPosition  // Update current position
                    lastValidPositionTime = currentTime
                    positionStartTime = currentTime
                }
                
                val positionDuration = currentTime - lastValidPositionTime
                
                // Check for position completions based on strict sequence
                when (expectedPosition) {
                    PrayerPosition.STANDING -> {
                        if (positionDuration >= STANDING_REQUIRED_TIME && !standingCompleted) {
                            standingCompleted = true
                            expectedPosition = PrayerPosition.BOWING
                            Log.d(TAG, "Standing position completed")
                        }
                    }
                    PrayerPosition.BOWING -> {
                        if (positionDuration >= BOWING_REQUIRED_TIME && !bowingCompleted) {
                            bowingCompleted = true
                            expectedPosition = PrayerPosition.SITTING
                            Log.d(TAG, "Bowing position completed")
                        }
                    }
                    PrayerPosition.SITTING -> {
                        if (positionDuration >= SITTING_REQUIRED_TIME && !sittingCompleted) {
                            sittingCompleted = true
                            Log.d(TAG, "Sitting position completed")
                            
                            // Complete rakaat only when all positions are done in sequence
                            if (standingCompleted && bowingCompleted) {
                                if (currentRakaat + 1 >= totalRakaats) {
                                    // In the final rakaat, start the unlock timer when sitting is completed
                                    finalUnlockTimer = System.currentTimeMillis()
                                    Log.d(TAG, "Starting final unlock timer for last rakaat")
                                } else {
                                    // For non-final rakaats, increment immediately
                                    currentRakaat++
                                    Log.d(TAG, "Completed rakaat $currentRakaat of $totalRakaats")
                                    // Reset for next rakaat
                                    standingCompleted = false
                                    bowingCompleted = false
                                    sittingCompleted = false
                                    expectedPosition = PrayerPosition.STANDING
                                }                                
                            }
                        }
                    }
                    else -> {} // Ignore other positions
                }
                
            }
            
            // Update state (moved outside position validation block)
            val shouldUnlock = if (currentRakaat + 1 >= totalRakaats && finalUnlockTimer > 0) {
                currentTime - finalUnlockTimer >= FINAL_UNLOCK_DELAY
            } else {
                false
            }
            
            // Increment rakaat counter when unlock timer expires
            if (shouldUnlock && currentRakaat + 1 >= totalRakaats) {
                currentRakaat = totalRakaats
            }
            
            _state.value = _state.value.copy(
                currentRakaat = currentRakaat,
                currentPosition = currentPosition,
                isComplete = currentRakaat >= totalRakaats,
                shouldAutoUnlock = shouldUnlock,
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing position: ${e.message}")
            _state.value = _state.value.copy(
                errorMessage = "Error tracking prayer: ${e.message}"
            )
        }
    }

    private fun isValidTransition(from: PrayerPosition, to: PrayerPosition): Boolean {
        val fromIndex = simplifiedRakaatSequence.indexOf(from)
        val toIndex = simplifiedRakaatSequence.indexOf(to)

        // Check if transition follows the sequence
        return when {
            fromIndex == -1 || toIndex == -1 -> false
            toIndex == fromIndex + 1 -> true // Next position
            toIndex == fromIndex - 1 -> true // Previous position (allow correction)
            else -> false
        }
    }

    private fun checkRakaatCompletion() {
        try {
            // Check if current sequence matches simplified sequence
            if (isSequenceComplete(positionsInCurrentRakaat, simplifiedRakaatSequence)) {
                // Validate position durations
                if (validatePositionDurations()) {
                    currentRakaat++
                    positionsInCurrentRakaat.clear()
                    positionDurations.clear()
                    Log.d(TAG, "Completed rakaat $currentRakaat of $totalRakaats")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking rakaat completion: ${e.message}")
        }
    }

    private fun validatePositionDurations(): Boolean {
        return positionDurations.all { (position, duration) ->
            val minTime = when (position) {
                PrayerPosition.STANDING -> STANDING_MIN_TIME
                PrayerPosition.BOWING -> BOWING_MIN_TIME
                PrayerPosition.SITTING -> SITTING_MIN_TIME
                else -> MIN_POSITION_TIME
            }
            duration in minTime..MAX_POSITION_TIME
        }
    }

    private fun isSequenceComplete(
        current: List<PrayerPosition>,
        expected: List<PrayerPosition>
    ): Boolean {
        if (current.isEmpty()) return false

        // Find the last occurrence of each expected position
        val lastIndices = mutableMapOf<PrayerPosition, Int>()
        for (i in current.indices) {
            if (current[i] in expected) {
                lastIndices[current[i]] = i
            }
        }

        // Check if we have all expected positions
        if (lastIndices.size != expected.size) {
            Log.d(TAG, "Missing some positions. Found: ${lastIndices.keys}, Expected: $expected")
            return false
        }

        // Verify the order of positions
        var lastIndex = -1
        for (position in expected) {
            val currentIndex = lastIndices[position] ?: return false
            if (currentIndex < lastIndex) {
                Log.d(TAG, "Positions out of order at $position")
                return false
            }
            lastIndex = currentIndex
        }

        // Additional logging for debugging
        Log.d(TAG, "Sequence complete. Current positions: $current")
        Log.d(TAG, "Position durations: $positionDurations")

        return true
    }

    fun getCurrentRakaat(): Int = currentRakaat

    fun isComplete(): Boolean = currentRakaat >= totalRakaats

    fun reset() {
        currentRakaat = 0
        currentPosition = PrayerPosition.UNKNOWN
        positionStartTime = 0L
        
        // Reset completion flags
        standingCompleted = false
        bowingCompleted = false
        sittingCompleted = false
        
        _state.value = TrackerState()
    }

    fun getProgress(): Float {
        if (totalRakaats == 0) return 0f
        return currentRakaat.toFloat() / totalRakaats.toFloat()
    }

    fun getExpectedNextPosition(): PrayerPosition {
        val currentIndex = simplifiedRakaatSequence.indexOf(currentPosition)
        if (currentIndex == -1 || currentIndex == simplifiedRakaatSequence.size - 1) {
            return simplifiedRakaatSequence.firstOrNull() ?: PrayerPosition.UNKNOWN
        }
        return simplifiedRakaatSequence[currentIndex + 1]
    }
}
