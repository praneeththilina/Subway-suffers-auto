package com.example.feature.swipe.data.repository

import com.example.feature.swipe.domain.model.AxisLockState
import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.domain.model.MovementInputMode
import com.example.feature.swipe.domain.model.PoseCalibrationState
import com.example.feature.swipe.domain.model.SwipeTuningConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SwipeTelemetryRepository {
    private val eventIdCounter = AtomicLong(1L)

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _isFloatingOverlayVisible = MutableStateFlow(false)
    val isFloatingOverlayVisible: StateFlow<Boolean> = _isFloatingOverlayVisible.asStateFlow()

    private val _isApplicationOverlayRunning = MutableStateFlow(false)
    val isApplicationOverlayRunning: StateFlow<Boolean> = _isApplicationOverlayRunning.asStateFlow()

    private val _isCameraPreviewEnabledInOverlay = MutableStateFlow(true)
    val isCameraPreviewEnabledInOverlay: StateFlow<Boolean> = _isCameraPreviewEnabledInOverlay.asStateFlow()

    private val _movementInputMode = MutableStateFlow(MovementInputMode.CAMERA_POSE)
    val movementInputMode: StateFlow<MovementInputMode> = _movementInputMode.asStateFlow()

    private val _isMovementTrackingActive = MutableStateFlow(false)
    val isMovementTrackingActive: StateFlow<Boolean> = _isMovementTrackingActive.asStateFlow()

    private val _poseCalibrationState = MutableStateFlow(PoseCalibrationState())
    val poseCalibrationState: StateFlow<PoseCalibrationState> = _poseCalibrationState.asStateFlow()

    private val _isAutoJumpRunning = MutableStateFlow(false)
    val isAutoJumpRunning: StateFlow<Boolean> = _isAutoJumpRunning.asStateFlow()

    private val _autoJumpCountdownMs = MutableStateFlow(3000L)
    val autoJumpCountdownMs: StateFlow<Long> = _autoJumpCountdownMs.asStateFlow()

    private val _autoJumpTotalCount = MutableStateFlow(0)
    val autoJumpTotalCount: StateFlow<Int> = _autoJumpTotalCount.asStateFlow()

    private val _tuningConfig = MutableStateFlow(SwipeTuningConfig())
    val tuningConfig: StateFlow<SwipeTuningConfig> = _tuningConfig.asStateFlow()

    private val _telemetryLogs = MutableStateFlow<List<GestureTelemetryEvent>>(emptyList())
    val telemetryLogs: StateFlow<List<GestureTelemetryEvent>> = _telemetryLogs.asStateFlow()

    private val _latestGestureStroke = MutableStateFlow<GestureStrokePoint?>(null)
    val latestGestureStroke: StateFlow<GestureStrokePoint?> = _latestGestureStroke.asStateFlow()

    private val _latestDispatchedDirection = MutableStateFlow<Pair<String, Long>?>(null)
    val latestDispatchedDirection: StateFlow<Pair<String, Long>?> = _latestDispatchedDirection.asStateFlow()

    private val _lastActionLabel = MutableStateFlow("READY")
    val lastActionLabel: StateFlow<String> = _lastActionLabel.asStateFlow()

    // Decoupled Horizontal & Vertical State Machine Variables:
    // - Allows Jump-Cancel (vertical UP/DOWN triggers immediately even while horizontal X is locked
    //   in WAITING_FOR_NEUTRAL or horizontal 250ms debounce).
    // - Prevents returning the body/hand to center from accidentally swiping in the opposite direction
    //   by requiring at least 100ms dwell time inside the center Neutral Box before unlocking.
    @Volatile
    private var lastHorizontalTriggerMs: Long = -10_000L

    @Volatile
    private var lastVerticalTriggerMs: Long = -10_000L

    @Volatile
    private var horizontalNeutralEnteredAtMs: Long? = null

    @Volatile
    private var verticalNeutralEnteredAtMs: Long? = null

    fun mapDirectionToGameAction(direction: String): String {
        return when (direction.trim().uppercase()) {
            "UP" -> "JUMP"
            "DOWN" -> "ROLL"
            "LEFT" -> "LEFT"
            "RIGHT" -> "RIGHT"
            "DOUBLE_TAP" -> "HOVERBOARD"
            else -> direction.trim().uppercase()
        }
    }

    fun setMovementInputMode(mode: MovementInputMode) {
        _movementInputMode.value = mode
    }

    fun setMovementTrackingActive(active: Boolean) {
        _isMovementTrackingActive.value = active
    }

    fun updatePoseModelStatus(status: String) {
        _poseCalibrationState.update { it.copy(modelStatus = status) }
    }

    fun updatePoseThresholds(
        thresholdX: Float = _poseCalibrationState.value.thresholdX,
        thresholdY: Float = _poseCalibrationState.value.thresholdY,
        debounceCooldownMs: Long = _poseCalibrationState.value.debounceCooldownMs,
        neutralHoldRequiredMs: Long = _poseCalibrationState.value.neutralHoldRequiredMs
    ) {
        _poseCalibrationState.update {
            it.copy(
                thresholdX = thresholdX.coerceIn(0.04f, 0.30f),
                thresholdY = thresholdY.coerceIn(0.04f, 0.30f),
                debounceCooldownMs = debounceCooldownMs.coerceIn(100L, 1000L),
                neutralHoldRequiredMs = neutralHoldRequiredMs.coerceIn(50L, 500L)
            )
        }
    }

    fun calibratePoseCenter(centerX: Float, centerY: Float) {
        lastHorizontalTriggerMs = -10_000L
        lastVerticalTriggerMs = -10_000L
        horizontalNeutralEnteredAtMs = null
        verticalNeutralEnteredAtMs = null
        _poseCalibrationState.update {
            it.copy(
                isCalibrated = true,
                centerX = centerX.coerceIn(0.1f, 0.9f),
                centerY = centerY.coerceIn(0.1f, 0.9f),
                horizontalState = AxisLockState.READY,
                verticalState = AxisLockState.READY,
                horizontalNeutralProgressMs = it.neutralHoldRequiredMs,
                verticalNeutralProgressMs = it.neutralHoldRequiredMs,
                jumpCancelActive = false,
                modelStatus = "Center Calibrated (${String.format("%.2f", centerX)}, ${String.format("%.2f", centerY)})"
            )
        }
    }

    fun recalibrateToCurrentPose() {
        val current = _poseCalibrationState.value
        calibratePoseCenter(current.currentX, current.currentY)
    }

    /**
     * Evaluates normalized nose & shoulder coordinates against the calibrated center point using:
     * 1. WAITING_FOR_NEUTRAL state machine: Once a horizontal swipe (LEFT/RIGHT) fires, horizontal
     *    swipes are locked in [AxisLockState.WAITING_FOR_NEUTRAL] until the body returns inside the
     *    center Neutral Box (`|dx| < thresholdX`) for at least `neutralHoldRequiredMs` (100ms).
     *    Same per-axis neutral return protection applies to vertical (UP/DOWN) so returning from a
     *    jump doesn't accidentally trigger a roll.
     * 2. Jump-Cancel Support: Vertical triggers (UP/DOWN) evaluate independently and can fire
     *    immediately even while horizontal movement is locked in [AxisLockState.WAITING_FOR_NEUTRAL]
     *    or horizontal 250ms debounce cooldown, allowing players to jump/roll mid-lane-change.
     */
    fun processPoseLandmarksAndEvaluateDirection(
        noseX: Float,
        noseY: Float,
        leftShoulderX: Float,
        leftShoulderY: Float,
        rightShoulderX: Float,
        rightShoulderY: Float,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val shoulderMidX = (leftShoulderX + rightShoulderX) * 0.5f
        val shoulderMidY = (leftShoulderY + rightShoulderY) * 0.5f
        val trackedX = (noseX * 0.45f) + (shoulderMidX * 0.55f)
        val trackedY = (noseY * 0.45f) + (shoulderMidY * 0.55f)

        val currentState = _poseCalibrationState.value
        if (!currentState.isCalibrated) {
            _poseCalibrationState.value = currentState.copy(
                isCalibrated = true,
                centerX = trackedX,
                centerY = trackedY,
                currentX = trackedX,
                currentY = trackedY,
                noseX = noseX,
                noseY = noseY,
                leftShoulderX = leftShoulderX,
                leftShoulderY = leftShoulderY,
                rightShoulderX = rightShoulderX,
                rightShoulderY = rightShoulderY,
                horizontalState = AxisLockState.READY,
                verticalState = AxisLockState.READY,
                horizontalNeutralProgressMs = currentState.neutralHoldRequiredMs,
                verticalNeutralProgressMs = currentState.neutralHoldRequiredMs
            )
            return null
        }

        val dx = trackedX - currentState.centerX
        val dy = trackedY - currentState.centerY
        val threshX = currentState.thresholdX
        val threshY = currentState.thresholdY
        val requiredNeutralMs = currentState.neutralHoldRequiredMs
        val debounceMs = currentState.debounceCooldownMs

        val isInsideHorizontalNeutral = abs(dx) < threshX
        val isInsideVerticalNeutral = abs(dy) < threshY

        // 1. Update Horizontal State Machine (READY vs WAITING_FOR_NEUTRAL with 100ms Neutral Box hold)
        var nextHorizontalState = currentState.horizontalState
        var nextHorizNeutralProgress = currentState.horizontalNeutralProgressMs

        if (nextHorizontalState == AxisLockState.WAITING_FOR_NEUTRAL) {
            if (isInsideHorizontalNeutral) {
                val enteredAt = horizontalNeutralEnteredAtMs ?: nowMs.also {
                    horizontalNeutralEnteredAtMs = it
                }
                val elapsedInNeutral = (nowMs - enteredAt).coerceAtLeast(0L)
                if (elapsedInNeutral >= requiredNeutralMs) {
                    nextHorizontalState = AxisLockState.READY
                    nextHorizNeutralProgress = requiredNeutralMs
                    horizontalNeutralEnteredAtMs = null
                } else {
                    nextHorizNeutralProgress = elapsedInNeutral
                }
            } else {
                // Left the Neutral Box before completing 100ms hold -> reset timer
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            }
        } else {
            nextHorizNeutralProgress = requiredNeutralMs
        }

        // 2. Update Vertical State Machine (READY vs WAITING_FOR_NEUTRAL with 100ms Neutral Box hold)
        var nextVerticalState = currentState.verticalState
        var nextVertNeutralProgress = currentState.verticalNeutralProgressMs

        if (nextVerticalState == AxisLockState.WAITING_FOR_NEUTRAL) {
            if (isInsideVerticalNeutral) {
                val enteredAt = verticalNeutralEnteredAtMs ?: nowMs.also {
                    verticalNeutralEnteredAtMs = it
                }
                val elapsedInNeutral = (nowMs - enteredAt).coerceAtLeast(0L)
                if (elapsedInNeutral >= requiredNeutralMs) {
                    nextVerticalState = AxisLockState.READY
                    nextVertNeutralProgress = requiredNeutralMs
                    verticalNeutralEnteredAtMs = null
                } else {
                    nextVertNeutralProgress = elapsedInNeutral
                }
            } else {
                verticalNeutralEnteredAtMs = null
                nextVertNeutralProgress = 0L
            }
        } else {
            nextVertNeutralProgress = requiredNeutralMs
        }

        // 3. Evaluate Candidate Triggers on Each Axis
        val canFireVertical = nextVerticalState == AxisLockState.READY &&
            !isInsideVerticalNeutral &&
            (nowMs - lastVerticalTriggerMs >= debounceMs)

        val canFireHorizontal = nextHorizontalState == AxisLockState.READY &&
            !isInsideHorizontalNeutral &&
            (nowMs - lastHorizontalTriggerMs >= debounceMs)

        var detectedDirection: String? = null
        var didJumpCancel = false

        if (canFireVertical && canFireHorizontal) {
            // Both axes are ready and exceeded threshold simultaneously: pick dominant normalized ratio
            if (abs(dy) / threshY >= abs(dx) / threshX) {
                detectedDirection = if (dy < 0f) "UP" else "DOWN"
                lastVerticalTriggerMs = nowMs
                nextVerticalState = AxisLockState.WAITING_FOR_NEUTRAL
                verticalNeutralEnteredAtMs = null
                nextVertNeutralProgress = 0L
            } else {
                detectedDirection = if (dx > 0f) "RIGHT" else "LEFT"
                lastHorizontalTriggerMs = nowMs
                nextHorizontalState = AxisLockState.WAITING_FOR_NEUTRAL
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            }
        } else if (canFireVertical) {
            // Jump-Cancel Support: Vertical (UP/DOWN) fires immediately even if Horizontal is locked
            // in WAITING_FOR_NEUTRAL or horizontal 250ms cooldown!
            detectedDirection = if (dy < 0f) "UP" else "DOWN"
            didJumpCancel = nextHorizontalState == AxisLockState.WAITING_FOR_NEUTRAL ||
                (nowMs - lastHorizontalTriggerMs < debounceMs)
            lastVerticalTriggerMs = nowMs
            nextVerticalState = AxisLockState.WAITING_FOR_NEUTRAL
            verticalNeutralEnteredAtMs = null
            nextVertNeutralProgress = 0L
        } else if (canFireHorizontal) {
            detectedDirection = if (dx > 0f) "RIGHT" else "LEFT"
            lastHorizontalTriggerMs = nowMs
            nextHorizontalState = AxisLockState.WAITING_FOR_NEUTRAL
            horizontalNeutralEnteredAtMs = null
            nextHorizNeutralProgress = 0L
        }

        _poseCalibrationState.value = currentState.copy(
            currentX = trackedX,
            currentY = trackedY,
            noseX = noseX,
            noseY = noseY,
            leftShoulderX = leftShoulderX,
            leftShoulderY = leftShoulderY,
            rightShoulderX = rightShoulderX,
            rightShoulderY = rightShoulderY,
            horizontalState = nextHorizontalState,
            verticalState = nextVerticalState,
            horizontalNeutralProgressMs = nextHorizNeutralProgress,
            verticalNeutralProgressMs = nextVertNeutralProgress,
            jumpCancelActive = didJumpCancel
        )

        return detectedDirection
    }

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected) {
            _isFloatingOverlayVisible.value = false
            _isAutoJumpRunning.value = false
        }
    }

    fun setFloatingOverlayVisible(visible: Boolean) {
        _isFloatingOverlayVisible.value = visible
    }

    fun setApplicationOverlayRunning(running: Boolean) {
        _isApplicationOverlayRunning.value = running
    }

    fun setCameraPreviewEnabledInOverlay(enabled: Boolean) {
        _isCameraPreviewEnabledInOverlay.value = enabled
    }

    fun setAutoJumpRunning(running: Boolean) {
        _isAutoJumpRunning.value = running
        if (!running) {
            _autoJumpCountdownMs.value = _tuningConfig.value.milestoneIntervalMs
        }
    }

    fun updateAutoJumpCountdown(remainingMs: Long) {
        _autoJumpCountdownMs.value = remainingMs.coerceAtLeast(0L)
    }

    fun incrementAutoJumpCount() {
        _autoJumpTotalCount.update { it + 1 }
    }

    fun updateTuningConfig(
        swipeDurationMs: Long = _tuningConfig.value.swipeDurationMs,
        swipeAmplitudeFraction: Float = _tuningConfig.value.swipeAmplitudeFraction,
        milestoneIntervalMs: Long = _tuningConfig.value.milestoneIntervalMs
    ) {
        _tuningConfig.value = SwipeTuningConfig(
            swipeDurationMs = swipeDurationMs.coerceIn(60L, 600L),
            swipeAmplitudeFraction = swipeAmplitudeFraction.coerceIn(0.15f, 0.45f),
            milestoneIntervalMs = milestoneIntervalMs.coerceIn(1000L, 10000L)
        )
    }

    fun recordGestureEvent(
        direction: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        dispatchedViaService: Boolean,
        statusMessage: String,
        source: String = "Manual"
    ) {
        val now = System.currentTimeMillis()
        val normalizedDir = direction.trim().uppercase()
        _latestGestureStroke.value = GestureStrokePoint(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            isDoubleTap = normalizedDir == "DOUBLE_TAP"
        )
        _latestDispatchedDirection.value = normalizedDir to now
        _lastActionLabel.value = mapDirectionToGameAction(normalizedDir)

        val event = GestureTelemetryEvent(
            id = eventIdCounter.getAndIncrement(),
            direction = normalizedDir,
            timestampMillis = now,
            startX = startX.toInt(),
            startY = startY.toInt(),
            endX = endX.toInt(),
            endY = endY.toInt(),
            durationMs = durationMs,
            dispatchedViaService = dispatchedViaService,
            statusMessage = statusMessage,
            source = source
        )
        _telemetryLogs.update { current ->
            (listOf(event) + current).take(60)
        }
    }

    fun clearLogs() {
        _telemetryLogs.value = emptyList()
        _autoJumpTotalCount.value = 0
        _lastActionLabel.value = "READY"
        lastHorizontalTriggerMs = -10_000L
        lastVerticalTriggerMs = -10_000L
        horizontalNeutralEnteredAtMs = null
        verticalNeutralEnteredAtMs = null
        _poseCalibrationState.update {
            it.copy(
                horizontalState = AxisLockState.READY,
                verticalState = AxisLockState.READY,
                horizontalNeutralProgressMs = it.neutralHoldRequiredMs,
                verticalNeutralProgressMs = it.neutralHoldRequiredMs,
                jumpCancelActive = false
            )
        }
    }
}
