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
