package com.example.feature.swipe.data.repository

import com.example.feature.swipe.domain.model.AxisLockState
import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.domain.model.MovementInputMode
import com.example.feature.swipe.domain.model.PoseCalibrationState
import com.example.feature.swipe.domain.model.RunnerLane
import com.example.feature.swipe.domain.model.ScreenVisionAutopilotState
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

    private val _movementInputMode = MutableStateFlow(MovementInputMode.SCREEN_VISION_AUTOPILOT)
    val movementInputMode: StateFlow<MovementInputMode> = _movementInputMode.asStateFlow()

    private val _isMovementTrackingActive = MutableStateFlow(false)
    val isMovementTrackingActive: StateFlow<Boolean> = _isMovementTrackingActive.asStateFlow()

    private val _poseCalibrationState = MutableStateFlow(PoseCalibrationState())
    val poseCalibrationState: StateFlow<PoseCalibrationState> = _poseCalibrationState.asStateFlow()

    // 3-Column Lane State & Screen Vision Display Reader State (starts in MIDDLE column = 0)
    private val _screenVisionState = MutableStateFlow(ScreenVisionAutopilotState())
    val screenVisionState: StateFlow<ScreenVisionAutopilotState> = _screenVisionState.asStateFlow()

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

    private val _lastActionLabel = MutableStateFlow("READY (MIDDLE)")
    val lastActionLabel: StateFlow<String> = _lastActionLabel.asStateFlow()

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

    /**
     * Resets the player's tracked position to the MIDDLE column (0) — e.g. when starting a new run.
     */
    fun resetLaneToMiddle() {
        _screenVisionState.update {
            it.copy(
                currentLane = RunnerLane.MIDDLE,
                detectedPlayerLane = RunnerLane.MIDDLE,
                blockedBoundaryMoveMessage = null,
                lastAutopilotDecision = "Reset to MIDDLE Column (Can go 1 Left or 1 Right)"
            )
        }
        _lastActionLabel.value = "MIDDLE (0)"
    }

    /**
     * Synchronizes the player's column if the Screen Display Reader visually detects the character
     * in a specific column (`LEFT`, `MIDDLE`, or `RIGHT`).
     */
    fun syncDetectedPlayerLaneFromDisplay(detectedLane: RunnerLane) {
        _screenVisionState.update {
            it.copy(
                currentLane = detectedLane,
                detectedPlayerLane = detectedLane,
                blockedBoundaryMoveMessage = null
            )
        }
    }

    /**
     * Validates whether a requested direction is legal given the 3-column geometry:
     * - Starts in [RunnerLane.MIDDLE] (0).
     * - If in [RunnerLane.LEFT] (-1): blocks another "LEFT" swipe! Allows up to 2 "RIGHT" swipes.
     * - If in [RunnerLane.RIGHT] (+1): blocks another "RIGHT" swipe! Allows up to 2 "LEFT" swipes.
     * - "UP", "DOWN", and "DOUBLE_TAP" are always allowed in any column.
     *
     * When valid, advances `currentLane` and returns `true`. When blocked at a boundary, records
     * the boundary block message and returns `false`.
     */
    fun validateAndAdvanceColumnForSwipe(direction: String, source: String = "Swipe Engine"): Boolean {
        val normalized = direction.trim().uppercase()
        val currentLane = _screenVisionState.value.currentLane

        when (normalized) {
            "LEFT" -> {
                if (!currentLane.canMoveLeft) {
                    val msg = "BLOCKED LEFT: Player is already in LEFT column (-1)! Can move up to 2 RIGHT."
                    _screenVisionState.update {
                        it.copy(
                            blockedBoundaryMoveMessage = msg,
                            lastAutopilotDecision = msg
                        )
                    }
                    _lastActionLabel.value = "BLOCKED (AT LEFT)"
                    return false
                }
                val nextLane = RunnerLane.fromColumnIndex(currentLane.columnIndex - 1)
                _screenVisionState.update {
                    it.copy(
                        currentLane = nextLane,
                        detectedPlayerLane = nextLane,
                        blockedBoundaryMoveMessage = null,
                        lastAutopilotDecision = "Moved LEFT: ${currentLane.shortCode} → ${nextLane.shortCode} " +
                            "(Rem: ${nextLane.remainingStepsLeft}L, ${nextLane.remainingStepsRight}R)"
                    )
                }
                return true
            }
            "RIGHT" -> {
                if (!currentLane.canMoveRight) {
                    val msg = "BLOCKED RIGHT: Player is already in RIGHT column (+1)! Can move up to 2 LEFT."
                    _screenVisionState.update {
                        it.copy(
                            blockedBoundaryMoveMessage = msg,
                            lastAutopilotDecision = msg
                        )
                    }
                    _lastActionLabel.value = "BLOCKED (AT RIGHT)"
                    return false
                }
                val nextLane = RunnerLane.fromColumnIndex(currentLane.columnIndex + 1)
                _screenVisionState.update {
                    it.copy(
                        currentLane = nextLane,
                        detectedPlayerLane = nextLane,
                        blockedBoundaryMoveMessage = null,
                        lastAutopilotDecision = "Moved RIGHT: ${currentLane.shortCode} → ${nextLane.shortCode} " +
                            "(Rem: ${nextLane.remainingStepsLeft}L, ${nextLane.remainingStepsRight}R)"
                    )
                }
                return true
            }
            "UP", "DOWN", "DOUBLE_TAP" -> {
                _screenVisionState.update {
                    it.copy(
                        blockedBoundaryMoveMessage = null,
                        lastAutopilotDecision = "${mapDirectionToGameAction(normalized)} in ${currentLane.label}"
                    )
                }
                return true
            }
            else -> return false
        }
    }

    fun setAutopilotEnabled(enabled: Boolean) {
        _screenVisionState.update {
            it.copy(
                isAutopilotEnabled = enabled,
                isScreenReaderActive = enabled,
                lastAutopilotDecision = if (enabled) {
                    "Display Reader Autopilot ACTIVE in ${it.currentLane.label}"
                } else {
                    "Display Reader Autopilot Paused"
                }
            )
        }
    }

    /**
     * Updates telemetry from the live Screen Display Reader (analyzing the 3 columns on screen)
     * and computes the optimal automated action (`LEFT`, `RIGHT`, `UP`, or `DOWN`) when an obstacle
     * approaches in the player's current column.
     */
    fun updateScreenVisionReadingAndDecideAction(
        leftHazard: Float,
        middleHazard: Float,
        rightHazard: Float,
        detectedPlayerColumn: RunnerLane? = null,
        obstacleType: String = "TRAIN"
    ): String? {
        val prev = _screenVisionState.value
        val activeLane = detectedPlayerColumn ?: prev.currentLane

        val currentLaneHazard = when (activeLane) {
            RunnerLane.LEFT -> leftHazard
            RunnerLane.MIDDLE -> middleHazard
            RunnerLane.RIGHT -> rightHazard
        }

        // Danger threshold: when obstacle in the player's current column is close (>= 0.62f)
        var recommendedDirection: String? = null
        var decisionSummary = "Scanning Display (${activeLane.shortCode}) — Track Clear"

        if (currentLaneHazard >= 0.62f) {
            when (activeLane) {
                RunnerLane.MIDDLE -> {
                    // In Middle column (0): can go 1 Left or 1 Right. Pick the clearer column, or Jump/Roll!
                    recommendedDirection = when {
                        leftHazard < 0.38f && leftHazard <= rightHazard -> "LEFT"
                        rightHazard < 0.38f -> "RIGHT"
                        obstacleType == "LOW_BARRIER" -> "UP"
                        obstacleType == "HIGH_BARRIER" -> "DOWN"
                        else -> "UP"
                    }
                }
                RunnerLane.LEFT -> {
                    // In Left column (-1): NEVER swipe LEFT again! Move RIGHT to Middle (or Jump/Roll)
                    recommendedDirection = when {
                        middleHazard < 0.40f -> "RIGHT"
                        obstacleType == "HIGH_BARRIER" -> "DOWN"
                        else -> "UP"
                    }
                }
                RunnerLane.RIGHT -> {
                    // In Right column (+1): NEVER swipe RIGHT again! Move LEFT to Middle (or Jump/Roll)
                    recommendedDirection = when {
                        middleHazard < 0.40f -> "LEFT"
                        obstacleType == "HIGH_BARRIER" -> "DOWN"
                        else -> "UP"
                    }
                }
            }
            decisionSummary =
                "Obstacle ($obstacleType ${(currentLaneHazard * 100).toInt()}%) in ${activeLane.shortCode} → Auto $recommendedDirection"
        }

        _screenVisionState.value = prev.copy(
            currentLane = activeLane,
            detectedPlayerLane = activeLane,
            leftLaneHazard = leftHazard.coerceIn(0f, 1f),
            middleLaneHazard = middleHazard.coerceIn(0f, 1f),
            rightLaneHazard = rightHazard.coerceIn(0f, 1f),
            obstacleTypeInCurrentLane = if (currentLaneHazard >= 0.62f) obstacleType else "CLEAR",
            lastAutopilotDecision = decisionSummary,
            framesAnalyzedCount = prev.framesAnalyzedCount + 1
        )

        return recommendedDirection
    }

    fun setMovementInputMode(mode: MovementInputMode) {
        _movementInputMode.value = mode
    }

    fun setMovementTrackingActive(active: Boolean) {
        _isMovementTrackingActive.value = active
        if ( _movementInputMode.value == MovementInputMode.SCREEN_VISION_AUTOPILOT) {
            setAutopilotEnabled(active)
        }
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
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            }
        } else {
            nextHorizNeutralProgress = requiredNeutralMs
        }

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

        val canFireVertical = nextVerticalState == AxisLockState.READY &&
            !isInsideVerticalNeutral &&
            (nowMs - lastVerticalTriggerMs >= debounceMs)

        // Check 3-Column boundary before allowing a horizontal candidate to fire!
        val candidateHorizDir = if (dx > 0f) "RIGHT" else "LEFT"
        val currentLane = _screenVisionState.value.currentLane
        val isAllowedByColumnBounds = when (candidateHorizDir) {
            "LEFT" -> currentLane.canMoveLeft
            "RIGHT" -> currentLane.canMoveRight
            else -> true
        }

        val canFireHorizontal = nextHorizontalState == AxisLockState.READY &&
            !isInsideHorizontalNeutral &&
            (nowMs - lastHorizontalTriggerMs >= debounceMs)

        var detectedDirection: String? = null
        var didJumpCancel = false

        if (canFireVertical && canFireHorizontal && isAllowedByColumnBounds) {
            if (abs(dy) / threshY >= abs(dx) / threshX) {
                detectedDirection = if (dy < 0f) "UP" else "DOWN"
                lastVerticalTriggerMs = nowMs
                nextVerticalState = AxisLockState.WAITING_FOR_NEUTRAL
                verticalNeutralEnteredAtMs = null
                nextVertNeutralProgress = 0L
            } else {
                detectedDirection = candidateHorizDir
                lastHorizontalTriggerMs = nowMs
                nextHorizontalState = AxisLockState.WAITING_FOR_NEUTRAL
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            }
        } else if (canFireVertical) {
            detectedDirection = if (dy < 0f) "UP" else "DOWN"
            didJumpCancel = nextHorizontalState == AxisLockState.WAITING_FOR_NEUTRAL ||
                (nowMs - lastHorizontalTriggerMs < debounceMs) ||
                !isAllowedByColumnBounds
            lastVerticalTriggerMs = nowMs
            nextVerticalState = AxisLockState.WAITING_FOR_NEUTRAL
            verticalNeutralEnteredAtMs = null
            nextVertNeutralProgress = 0L
        } else if (canFireHorizontal) {
            if (isAllowedByColumnBounds) {
                detectedDirection = candidateHorizDir
                lastHorizontalTriggerMs = nowMs
                nextHorizontalState = AxisLockState.WAITING_FOR_NEUTRAL
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            } else {
                // Record boundary block message so user sees why extra LEFT or RIGHT was suppressed
                validateAndAdvanceColumnForSwipe(candidateHorizDir, "Pose/Motion Boundary Guard")
                nextHorizontalState = AxisLockState.WAITING_FOR_NEUTRAL
                horizontalNeutralEnteredAtMs = null
                nextHorizNeutralProgress = 0L
            }
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
        val laneShort = _screenVisionState.value.currentLane.shortCode
        _lastActionLabel.value = "${mapDirectionToGameAction(normalizedDir)} • $laneShort"

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
            statusMessage = "$statusMessage [Lane: ${_screenVisionState.value.currentLane.label}]",
            source = source
        )
        _telemetryLogs.update { current ->
            (listOf(event) + current).take(60)
        }
    }

    fun clearLogs() {
        _telemetryLogs.value = emptyList()
        _autoJumpTotalCount.value = 0
        resetLaneToMiddle()
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
