package com.example.feature.swipe.data.repository

import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.domain.model.SwipeTuningConfig
import java.util.concurrent.atomic.AtomicLong
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
    }
}
