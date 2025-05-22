package com.viperdam.kidsprayer.ml

import android.util.Log
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import kotlin.math.atan2

object PrayerPositionClassifier {

    private const val TAG = "PrayerPositionClassifier"

    // Angle ranges adjusted for front camera and made more lenient
    private val STANDING_SPINE_ANGLE_RANGE = 180.0..270.0  // Adjusted for vertical spine with front camera
    private val STANDING_KNEE_ANGLE_RANGE = 160.0..200.0  // More precise range for straight knees

    private val BOWING_SPINE_ANGLE_RANGE = 210.0..320.0  // Adjusted for downward y-axis
    private val BOWING_KNEE_ANGLE_RANGE = 140.0..220.0  // Adjusted range

    private val PROSTRATION_KNEE_ANGLE_RANGE = 70.0..160.0  // Adjusted range

    private val SITTING_KNEE_ANGLE_RANGE = 40.0..140.0  // Adjusted range
    private val SITTING_SPINE_ANGLE_RANGE = 140.0..220.0  // Adjusted range

    // Required landmark indices for valid pose detection (from MediaPipeHelper)
    private val REQUIRED_LANDMARKS = listOf(0, 1, 2, 11, 12, 13, 14, 23, 24, 25, 26, 27, 28, 15, 16)

    private const val SMOOTHING_FACTOR: Double = 0.3
    private var smoothedSpineAngle: Double? = null
    private var smoothedLeftKneeAngle: Double? = null
    private var smoothedRightKneeAngle: Double? = null
    private var smoothedLeftElbowAngle: Double? = null
    private var smoothedRightElbowAngle: Double? = null
    private var smoothedLeftShoulderAngle: Double? = null
    private var smoothedRightShoulderAngle: Double? = null

    fun classifyPose(landmarks: List<NormalizedLandmark>): PrayerPosition {

        if (!hasRequiredLandmarks(landmarks)) {
            Log.d(TAG, "Missing required landmarks")
            return PrayerPosition.UNKNOWN
        }

        return when {
            isStanding(landmarks) -> PrayerPosition.STANDING
            isBowing(landmarks) -> PrayerPosition.BOWING
            isProstrating(landmarks) -> PrayerPosition.PROSTRATION
            isSitting(landmarks) -> PrayerPosition.SITTING
            else -> PrayerPosition.UNKNOWN
        }
    }

    private fun hasRequiredLandmarks(landmarks: List<NormalizedLandmark>): Boolean {
        return try {
            REQUIRED_LANDMARKS.all { index ->
                landmarks[index].let { l ->
                    val x = l.x
                    val y = l.y
                    val visibility = l.visibility
                    val isValid = !x.isNaN() && !y.isNaN() && x != 0f && y != 0f && visibility > 0.3f
                    if (!isValid) {
                        Log.d(TAG, "Landmark $index is missing or invalid: x=$x, y=$y, visibility=$visibility")
                    }
                    isValid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking landmarks", e)
            false
        }
    }

    private fun calculateSpineAngle(landmarks: List<NormalizedLandmark>): Double {
        try {
            // Calculate angle between shoulders and hips
            val midHip = getMidpoint(landmarks[23], landmarks[24])
            val midShoulder = getMidpoint(landmarks[11], landmarks[12])

            // Calculate angle between hip-shoulder vector and the vertical axis (0, 1) - downwards in image coordinates
            val rawAngle = calculateAngle(
                Pair(0f, 1f), // Vertical axis (downwards)
                midHip,
                midShoulder
            )
            smoothedSpineAngle = if (smoothedSpineAngle == null) rawAngle else (SMOOTHING_FACTOR * rawAngle + (1 - SMOOTHING_FACTOR) * smoothedSpineAngle!!)
            Log.d(TAG, "Smoothed Spine Angle: $smoothedSpineAngle")
            return smoothedSpineAngle!!
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating spine angle", e)
            return 90.0 // Default to vertical
        }
    }

    private fun calculateKneeAngle(landmarks: List<NormalizedLandmark>, isLeft: Boolean): Double {
        try {
            val ankle = landmarks[if (isLeft) 27 else 28]
            val knee = landmarks[if (isLeft) 25 else 26]
            val hip = landmarks[if (isLeft) 23 else 24]
            val rawAngle = calculateAngle(Pair(ankle.x, ankle.y), Pair(knee.x, knee.y), Pair(hip.x, hip.y))
            if (isLeft) {
                smoothedLeftKneeAngle = if (smoothedLeftKneeAngle == null) rawAngle else (SMOOTHING_FACTOR * rawAngle + (1 - SMOOTHING_FACTOR) * smoothedLeftKneeAngle!!)
                Log.d(TAG, "Smoothed Left Knee Angle: $smoothedLeftKneeAngle")
                return smoothedLeftKneeAngle!!
            } else {
                smoothedRightKneeAngle = if (smoothedRightKneeAngle == null) rawAngle else (SMOOTHING_FACTOR * rawAngle + (1 - SMOOTHING_FACTOR) * smoothedRightKneeAngle!!)
                Log.d(TAG, "Smoothed Right Knee Angle: $smoothedRightKneeAngle")
                return smoothedRightKneeAngle!!
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating knee angle", e)
            return 180.0 // Default to straight leg
        }
    }

    private fun calculateAngle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Double {
        val abX = (a.x - b.x).toDouble()
        val abY = (a.y - b.y).toDouble()
        val cbX = (c.x - b.x).toDouble()
        val cbY = (c.y - b.y).toDouble()
        val dot = abX * cbX + abY * cbY
        val magAB = Math.sqrt(abX * abX + abY * abY)
        val magCB = Math.sqrt(cbX * cbX + cbY * cbY)
        return Math.toDegrees(Math.acos(dot / (magAB * magCB)))
    }

    private fun getMidpoint(p1: NormalizedLandmark, p2: NormalizedLandmark): Pair<Float, Float> {
        return Pair((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
    }

    private fun isStanding(landmarks: List<NormalizedLandmark>): Boolean {
        val spineAngle = calculateSpineAngle(landmarks)
        val leftKneeAngle = calculateKneeAngle(landmarks, true)
        val rightKneeAngle = calculateKneeAngle(landmarks, false)

        // Get heights for all body parts
        val shoulderY = (landmarks[11].y + landmarks[12].y) / 2
        val elbowY = (landmarks[13].y + landmarks[14].y) / 2
        val wristY = (landmarks[15].y + landmarks[16].y) / 2

        // Calculate horizontal positions
        val leftShoulderX = landmarks[11].x
        val rightShoulderX = landmarks[12].x
        val shoulderWidth = kotlin.math.abs(rightShoulderX - leftShoulderX)

        // Calculate vertical alignments for standing
        val isSpineVertical = spineAngle in STANDING_SPINE_ANGLE_RANGE ||
                spineAngle in (360.0 - 30.0)..360.0 // Also handle angles close to 360
        val areKneesStraight = leftKneeAngle in STANDING_KNEE_ANGLE_RANGE &&
                rightKneeAngle in STANDING_KNEE_ANGLE_RANGE

        // Compute additional joint angles for improved detection
        val leftElbowAngle = calculateAngle(landmarks[11], landmarks[13], landmarks[15])
        val rightElbowAngle = calculateAngle(landmarks[12], landmarks[14], landmarks[16])
        val leftShoulderAngle = calculateAngle(landmarks[13], landmarks[11], landmarks[12])
        val rightShoulderAngle = calculateAngle(landmarks[14], landmarks[12], landmarks[11])
        smoothedLeftElbowAngle = if (smoothedLeftElbowAngle == null) leftElbowAngle else (SMOOTHING_FACTOR * leftElbowAngle + (1 - SMOOTHING_FACTOR) * smoothedLeftElbowAngle!!)
        smoothedRightElbowAngle = if (smoothedRightElbowAngle == null) rightElbowAngle else (SMOOTHING_FACTOR * rightElbowAngle + (1 - SMOOTHING_FACTOR) * smoothedRightElbowAngle!!)
        smoothedLeftShoulderAngle = if (smoothedLeftShoulderAngle == null) leftShoulderAngle else (SMOOTHING_FACTOR * leftShoulderAngle + (1 - SMOOTHING_FACTOR) * smoothedLeftShoulderAngle!!)
        smoothedRightShoulderAngle = if (smoothedRightShoulderAngle == null) rightShoulderAngle else (SMOOTHING_FACTOR * rightShoulderAngle + (1 - SMOOTHING_FACTOR) * smoothedRightShoulderAngle!!)
        Log.d(TAG, "Smoothed Left Elbow Angle: $leftElbowAngle")
        Log.d(TAG, "Smoothed Right Elbow Angle: $rightElbowAngle")
        Log.d(TAG, "Smoothed Left Shoulder Angle: $leftShoulderAngle")
        Log.d(TAG, "Smoothed Right Shoulder Angle: $rightShoulderAngle")

        // Log angles and intermediate checks
        Log.d(TAG, "isStanding: spineAngle=$spineAngle, leftKneeAngle=$leftKneeAngle, rightKneeAngle=$rightKneeAngle")
        Log.d(TAG, "isStanding: isSpineVertical=$isSpineVertical, areKneesStraight=$areKneesStraight")

        // After logging spine and knee angles, add computation for folded hands condition
        val chestCenter = getMidpoint(landmarks[11], landmarks[12])
        val leftWrist = landmarks[15]
        val rightWrist = landmarks[16]

        data class Point(val x: Double, val y: Double)

        fun distance(p1: Point, p2: Point): Double {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return Math.sqrt(dx * dx + dy * dy)
        }

        val chestPoint = Point(chestCenter.first.toDouble(), chestCenter.second.toDouble())
        val leftWristPoint = Point(leftWrist.x.toDouble(), leftWrist.y.toDouble())
        val rightWristPoint = Point(rightWrist.x.toDouble(), rightWrist.y.toDouble())

        // Threshold for folded hands, made more lenient
        val HANDS_FOLDED_THRESHOLD = 0.15 // Increased from 0.1 to 0.15

        val leftWristDistance = distance(chestPoint, leftWristPoint)
        val rightWristDistance = distance(chestPoint, rightWristPoint)
        val wristsDistance = distance(leftWristPoint, rightWristPoint)

        Log.d(TAG, "Hands folded computed: leftWristDistance=$leftWristDistance, rightWristDistance=$rightWristDistance, wristsDistance=$wristsDistance, threshold=$HANDS_FOLDED_THRESHOLD")

        val areHandsFolded = leftWristDistance < HANDS_FOLDED_THRESHOLD && rightWristDistance < HANDS_FOLDED_THRESHOLD && wristsDistance < HANDS_FOLDED_THRESHOLD

        Log.d(TAG, "Chest Center: $chestCenter, Left Wrist Distance: $leftWristDistance, Right Wrist Distance: $rightWristDistance, Wrists Distance: $wristsDistance")
        Log.d(TAG, "areHandsFolded: $areHandsFolded")

        // Update the standing condition to consider both posture and hands
        val isStanding = isSpineVertical && areKneesStraight && 
            (areHandsFolded || (leftWristDistance < 0.2 && rightWristDistance < 0.2 && wristsDistance < 0.15))

        Log.d(TAG, "Final isStanding decision: $isStanding")

        return isStanding
    }

    private fun isBowing(landmarks: List<NormalizedLandmark>): Boolean {
        return try {
            val spineAngle = calculateSpineAngle(landmarks)
            val leftKneeAngle = calculateKneeAngle(landmarks, true)
            val rightKneeAngle = calculateKneeAngle(landmarks, false)

            // Get vertical positions
            val noseY = landmarks[0].y
            val hipY = (landmarks[23].y + landmarks[24].y) / 2
            val shoulderY = (landmarks[11].y + landmarks[12].y) / 2
            // Removed unused wristY and kneeY variables

            // Calculate relative positions
            val isSpineBent = spineAngle in BOWING_SPINE_ANGLE_RANGE
            val areKneesStraight = leftKneeAngle in BOWING_KNEE_ANGLE_RANGE &&
                    rightKneeAngle in BOWING_KNEE_ANGLE_RANGE

            // Log angles and intermediate checks
            Log.d(TAG, "isBowing: spineAngle=$spineAngle, leftKneeAngle=$leftKneeAngle, rightKneeAngle=$rightKneeAngle")
            Log.d(TAG, "isBowing: isSpineBent=$isSpineBent, areKneesStraight=$areKneesStraight")

            return isSpineBent && areKneesStraight

        } catch (e: Exception) {
            Log.e(TAG, "Error checking bowing position", e)
            return false
        }
    }

    private fun isProstrating(landmarks: List<NormalizedLandmark>): Boolean {
        val noseHeight = landmarks[0].y
        val midHipHeight = (landmarks[23].y + landmarks[24].y) / 2
        val leftKneeAngle = calculateKneeAngle(landmarks, true)
        val rightKneeAngle = calculateKneeAngle(landmarks, false)

        // Check if nose is below hips and knees are bent
        val isNoseBelowHips = noseHeight > midHipHeight
        val areKneesBent = leftKneeAngle in PROSTRATION_KNEE_ANGLE_RANGE &&
                rightKneeAngle in PROSTRATION_KNEE_ANGLE_RANGE

        // Log angles and intermediate checks
        Log.d(TAG, "isProstrating: noseHeight=$noseHeight, midHipHeight=$midHipHeight, leftKneeAngle=$leftKneeAngle, rightKneeAngle=$rightKneeAngle")
        Log.d(TAG, "isProstrating: isNoseBelowHips=$isNoseBelowHips, areKneesBent=$areKneesBent")

        return isNoseBelowHips && areKneesBent
    }

    private fun isSitting(landmarks: List<NormalizedLandmark>): Boolean {
        val leftKneeAngle = calculateKneeAngle(landmarks, true)
        val rightKneeAngle = calculateKneeAngle(landmarks, false)
        val spineAngle = calculateSpineAngle(landmarks)

        // For sitting, knees should be bent (angles around 90 degrees)
        // Left knee should be around 90 degrees (allowing for some variation)
        // Right knee should be around 270 degrees (allowing for some variation)
        val areKneesBent = (leftKneeAngle in 60.0..120.0 || 
                           rightKneeAngle in 240.0..300.0)

        // In sitting position, spine should be more vertical
        val isSpineVertical = spineAngle in 180.0..270.0

        Log.d(TAG, "Smoothed Left Knee Angle: $leftKneeAngle")
        Log.d(TAG, "Smoothed Right Knee Angle: $rightKneeAngle")
        Log.d(TAG, "Smoothed Spine Angle: $spineAngle")
        Log.d(TAG, "isSitting: leftKneeAngle=$leftKneeAngle, rightKneeAngle=$rightKneeAngle, spineAngle=$spineAngle")
        Log.d(TAG, "isSitting: areKneesBent=$areKneesBent, isSpineVertical=$isSpineVertical")

        return areKneesBent && isSpineVertical
    }

    private fun calculateAngle(
        first: Pair<Float, Float>,
        middle: Pair<Float, Float>,
        last: Pair<Float, Float>
    ): Double {
        val angle = Math.toDegrees(
            atan2(
                (last.second.toDouble() - middle.second.toDouble()),
                (last.first.toDouble() - middle.first.toDouble())
            ) -
                atan2(
                    (first.second.toDouble() - middle.second.toDouble()),
                    (first.first.toDouble() - middle.first.toDouble())
                )
        )
        return (angle + 360) % 360 // Normalize to 0-360
    }
}
