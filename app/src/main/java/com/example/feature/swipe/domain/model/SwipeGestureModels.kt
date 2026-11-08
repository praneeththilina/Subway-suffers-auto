package com.example.feature.swipe.domain.model

enum class SwipeDirection(val rawValue: String, val displayLabel: String) {
    UP("UP", "Swipe Up (Jump)"),
    DOWN("DOWN", "Swipe Down (Roll)"),
    LEFT("LEFT", "Swipe Left (Lane L)"),
    RIGHT("RIGHT", "Swipe Right (Lane R)"),
    DOUBLE_TAP("DOUBLE_TAP", "Double Tap (Hoverboard)");

    companion object {
        fun fromString(direction: String): SwipeDirection? {
            return entries.firstOrNull {
                it.rawValue.equals(direction.trim(), ignoreCase = true)
            }
        }
    }
}

/**
 * Represents the 3 columns (lanes) in Subway Surfers.
 * Player always starts in [MIDDLE] (column 0).
 * - From [MIDDLE]: can go 1 step [LEFT] (-1) or 1 step [RIGHT] (+1).
 * - From [LEFT]: cannot go LEFT again (blocked at left boundary), but can go up to 2 steps RIGHT ([MIDDLE] -> [RIGHT]).
 * - From [RIGHT]: cannot go RIGHT again (blocked at right boundary), but can go up to 2 steps LEFT ([MIDDLE] -> [LEFT]).
 */
enum class RunnerLane(val columnIndex: Int, val label: String, val shortCode: String) {
    LEFT(-1, "LEFT COLUMN", "L (-1)"),
    MIDDLE(0, "MIDDLE COLUMN", "M (0)"),
    RIGHT(1, "RIGHT COLUMN", "R (+1)");

    val canMoveLeft: Boolean
        get() = columnIndex > LEFT.columnIndex

    val canMoveRight: Boolean
        get() = columnIndex < RIGHT.columnIndex

    val remainingStepsLeft: Int
        get() = columnIndex - LEFT.columnIndex

    val remainingStepsRight: Int
        get() = RIGHT.columnIndex - columnIndex

    companion object {
        fun fromColumnIndex(index: Int): RunnerLane {
            return when (index.coerceIn(-1, 1)) {
                -1 -> LEFT
                1 -> RIGHT
                else -> MIDDLE
            }
        }
    }
}

/**
 * Real-time Screen Vision state from reading the active display (via AccessibilityService
 * takeScreenshot on API 30+ or MediaProjection / Runner track analyzer).
 */
data class ScreenVisionAutopilotState(
    val isAutopilotEnabled: Boolean = false,
    val isScreenReaderActive: Boolean = false,
    val currentLane: RunnerLane = RunnerLane.MIDDLE,
    val detectedPlayerLane: RunnerLane = RunnerLane.MIDDLE,
    val leftLaneHazard: Float = 0f,
    val middleLaneHazard: Float = 0f,
    val rightLaneHazard: Float = 0f,
    val obstacleTypeInCurrentLane: String = "CLEAR",
    val lastAutopilotDecision: String = "STANDBY (Start in MIDDLE)",
    val blockedBoundaryMoveMessage: String? = null,
    val framesAnalyzedCount: Int = 0
)

enum class MovementInputMode(val title: String, val subtitle: String) {
    SCREEN_VISION_AUTOPILOT(
        "Screen Vision Autopilot (Display Reader)",
        "Reads live display lanes (Left/Middle/Right), detects obstacles & automates gameplay"
    ),
    CAMERA_POSE(
        "Option A: Camera Pose (MediaPipe)",
        "Tracks Nose & Shoulder X/Y landmarks in LIVE_STREAM mode (WAITING_FOR_NEUTRAL + Jump-Cancel)"
    ),
    DEVICE_MOTION(
        "Option B: Device Motion (Sensors)",
        "Tracks Accelerometer & Gyroscope tilts (WAITING_FOR_NEUTRAL + Jump-Cancel)"
    )
}

/**
 * Per-axis state machine preventing accidental opposite-direction swipes when returning
 * the body or hand back to the center "Neutral Box".
 */
enum class AxisLockState(val badgeLabel: String) {
    READY("READY"),
    WAITING_FOR_NEUTRAL("WAITING_FOR_NEUTRAL")
}

data class PoseCalibrationState(
    val isCalibrated: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val currentX: Float = 0.5f,
    val currentY: Float = 0.5f,
    val noseX: Float = 0.5f,
    val noseY: Float = 0.38f,
    val leftShoulderX: Float = 0.36f,
    val leftShoulderY: Float = 0.60f,
    val rightShoulderX: Float = 0.64f,
    val rightShoulderY: Float = 0.60f,
    val thresholdX: Float = 0.11f,
    val thresholdY: Float = 0.10f,
    val debounceCooldownMs: Long = 250L,
    val neutralHoldRequiredMs: Long = 100L,
    val horizontalState: AxisLockState = AxisLockState.READY,
    val verticalState: AxisLockState = AxisLockState.READY,
    val horizontalNeutralProgressMs: Long = 100L,
    val verticalNeutralProgressMs: Long = 100L,
    val jumpCancelActive: Boolean = false,
    val modelStatus: String = "Initializing MediaPipe Pose Landmarker..."
) {
    val deltaX: Float
        get() = currentX - centerX

    val deltaY: Float
        get() = currentY - centerY
}

data class GestureStrokePoint(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
    val isDoubleTap: Boolean = false
)

data class GestureTelemetryEvent(
    val id: Long,
    val direction: String,
    val timestampMillis: Long,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    val dispatchedViaService: Boolean,
    val statusMessage: String,
    val source: String
)

data class SwipeTuningConfig(
    val swipeDurationMs: Long = 160L,
    val swipeAmplitudeFraction: Float = 0.34f,
    val milestoneIntervalMs: Long = 3000L
)
