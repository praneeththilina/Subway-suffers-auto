package com.example.feature.swipe.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.data.tracking.DeviceMotionSensorTracker
import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.domain.model.MovementInputMode
import com.example.feature.swipe.domain.model.PoseCalibrationState
import com.example.feature.swipe.domain.model.SwipeTuningConfig
import com.example.feature.swipe.domain.usecase.PerformSwipeUseCase
import com.example.service.OverlayService
import com.example.service.SwipeControllerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SwipeControllerUiState(
    val isServiceConnected: Boolean = false,
    val isFloatingOverlayVisible: Boolean = false,
    val isApplicationOverlayRunning: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val isCameraPreviewEnabled: Boolean = true,
    val movementInputMode: MovementInputMode = MovementInputMode.CAMERA_POSE,
    val isMovementTrackingActive: Boolean = false,
    val poseCalibrationState: PoseCalibrationState = PoseCalibrationState(),
    val lastActionLabel: String = "READY",
    val isAutoJumpRunning: Boolean = false,
    val autoJumpCountdownMs: Long = 3000L,
    val autoJumpTotalCount: Int = 0,
    val tuningConfig: SwipeTuningConfig = SwipeTuningConfig(),
    val telemetryLogs: List<GestureTelemetryEvent> = emptyList(),
    val latestGestureStroke: GestureStrokePoint? = null,
    val latestDispatchedDirection: Pair<String, Long>? = null,
    val isInAppFloatingFabVisible: Boolean = true,
    val isRunnerSandboxOpen: Boolean = false,
    val statusBannerMessage: String? = null
)

class SwipeControllerViewModel(
    private val performSwipeUseCase: PerformSwipeUseCase = PerformSwipeUseCase()
) : ViewModel() {

    private val _canDrawOverlays = MutableStateFlow(false)
    private val _hasCameraPermission = MutableStateFlow(false)
    private val _isInAppFloatingFabVisible = MutableStateFlow(true)
    private val _isRunnerSandboxOpen = MutableStateFlow(false)
    private val _statusBannerMessage = MutableStateFlow<String?>(null)

    private var localAutoJumpJob: Job? = null
    private var inAppMotionTracker: DeviceMotionSensorTracker? = null

    val uiState: StateFlow<SwipeControllerUiState> = combine(
        SwipeTelemetryRepository.isServiceConnected,
        SwipeTelemetryRepository.isFloatingOverlayVisible,
        SwipeTelemetryRepository.isApplicationOverlayRunning,
        _canDrawOverlays,
        _hasCameraPermission,
        SwipeTelemetryRepository.isCameraPreviewEnabledInOverlay,
        SwipeTelemetryRepository.movementInputMode,
        SwipeTelemetryRepository.isMovementTrackingActive,
        SwipeTelemetryRepository.poseCalibrationState,
        SwipeTelemetryRepository.lastActionLabel,
        SwipeTelemetryRepository.isAutoJumpRunning,
        SwipeTelemetryRepository.autoJumpCountdownMs,
        SwipeTelemetryRepository.autoJumpTotalCount,
        SwipeTelemetryRepository.tuningConfig,
        SwipeTelemetryRepository.telemetryLogs,
        SwipeTelemetryRepository.latestGestureStroke,
        SwipeTelemetryRepository.latestDispatchedDirection,
        _isInAppFloatingFabVisible,
        _isRunnerSandboxOpen,
        _statusBannerMessage
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SwipeControllerUiState(
            isServiceConnected = values[0] as Boolean,
            isFloatingOverlayVisible = values[1] as Boolean,
            isApplicationOverlayRunning = values[2] as Boolean,
            canDrawOverlays = values[3] as Boolean,
            hasCameraPermission = values[4] as Boolean,
            isCameraPreviewEnabled = values[5] as Boolean,
            movementInputMode = values[6] as MovementInputMode,
            isMovementTrackingActive = values[7] as Boolean,
            poseCalibrationState = values[8] as PoseCalibrationState,
            lastActionLabel = values[9] as String,
            isAutoJumpRunning = values[10] as Boolean,
            autoJumpCountdownMs = values[11] as Long,
            autoJumpTotalCount = values[12] as Int,
            tuningConfig = values[13] as SwipeTuningConfig,
            telemetryLogs = values[14] as List<GestureTelemetryEvent>,
            latestGestureStroke = values[15] as GestureStrokePoint?,
            latestDispatchedDirection = values[16] as Pair<String, Long>?,
            isInAppFloatingFabVisible = values[17] as Boolean,
            isRunnerSandboxOpen = values[18] as Boolean,
            statusBannerMessage = values[19] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SwipeControllerUiState()
    )

    fun refreshPermissionsAndStatus(context: Context) {
        val enabledInSettings = isAccessibilityServiceEnabledInSettings(context)
        val activeInstance = SwipeControllerService.isServiceActive()
        SwipeTelemetryRepository.setServiceConnected(enabledInSettings || activeInstance)

        _canDrawOverlays.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        _hasCameraPermission.value = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (inAppMotionTracker == null) {
            inAppMotionTracker = DeviceMotionSensorTracker(context.applicationContext) { dir ->
                performSwipeUseCase(dir, "Device Motion Sensor")
            }
        }
    }

    fun selectMovementInputMode(mode: MovementInputMode, context: Context) {
        SwipeTelemetryRepository.setMovementInputMode(mode)
        if (SwipeTelemetryRepository.isMovementTrackingActive.value) {
            if (mode == MovementInputMode.DEVICE_MOTION) {
                inAppMotionTracker?.startListening()
            } else {
                inAppMotionTracker?.stopListening()
            }
        }
        _statusBannerMessage.value = "Selected ${mode.title}."
    }

    fun toggleMovementTracking(context: Context) {
        val nextActive = !SwipeTelemetryRepository.isMovementTrackingActive.value
        SwipeTelemetryRepository.setMovementTrackingActive(nextActive)

        if (nextActive) {
            if (SwipeTelemetryRepository.movementInputMode.value == MovementInputMode.DEVICE_MOTION) {
                if (inAppMotionTracker == null) {
                    inAppMotionTracker = DeviceMotionSensorTracker(context.applicationContext) { dir ->
                        performSwipeUseCase(dir, "Device Motion Sensor")
                    }
                }
                inAppMotionTracker?.startListening()
            }
            _statusBannerMessage.value =
                "Movement Tracking ACTIVE (${SwipeTelemetryRepository.movementInputMode.value.title}, 250ms debounce)!"
        } else {
            inAppMotionTracker?.stopListening()
            _statusBannerMessage.value = "Movement Tracking paused."
        }
    }

    fun recalibratePoseCenter() {
        SwipeTelemetryRepository.recalibrateToCurrentPose()
        inAppMotionTracker?.recalibrateBaseline()
        _statusBannerMessage.value = "Calibrated neutral Nose & Shoulder center point!"
    }

    /**
     * Feeds normalized (x, y) pose coordinates into the MediaPipe Pose threshold evaluator
     * (including 250ms debounce cooldown) and triggers performSwipe(direction) when exceeded.
     */
    fun onInteractivePoseUpdated(normX: Float, normY: Float) {
        val detectedDir = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = normX,
            noseY = (normY - 0.08f).coerceIn(0.02f, 0.98f),
            leftShoulderX = (normX - 0.12f).coerceIn(0.02f, 0.98f),
            leftShoulderY = (normY + 0.07f).coerceIn(0.02f, 0.98f),
            rightShoulderX = (normX + 0.12f).coerceIn(0.02f, 0.98f),
            rightShoulderY = (normY + 0.07f).coerceIn(0.02f, 0.98f),
            nowMs = System.currentTimeMillis()
        )
        if (detectedDir != null) {
            val sourceLabel = if (SwipeTelemetryRepository.movementInputMode.value == MovementInputMode.CAMERA_POSE) {
                "MediaPipe Pose (250ms)"
            } else {
                "Device Motion (250ms)"
            }
            performSwipeUseCase(detectedDir, sourceLabel)
        }
    }

    fun updatePoseThresholds(thresholdX: Float, thresholdY: Float) {
        SwipeTelemetryRepository.updatePoseThresholds(
            thresholdX = thresholdX,
            thresholdY = thresholdY
        )
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _hasCameraPermission.value = granted
        _statusBannerMessage.value = if (granted) {
            "Camera permission granted! MediaPipe Pose Landmarker & Mini Camera Preview ready."
        } else {
            "Camera permission denied — use Interactive Pose Radar or Option B (Device Motion)."
        }
    }

    fun toggleApplicationOverlayService(context: Context) {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        _canDrawOverlays.value = canDraw

        if (!canDraw) {
            openManageOverlayPermissionSettings(context)
            _statusBannerMessage.value =
                "Please allow 'Display over other apps' so OverlayService (TYPE_APPLICATION_OVERLAY) can float over Subway Surfers."
            return
        }

        val isRunning = SwipeTelemetryRepository.isApplicationOverlayRunning.value
        if (isRunning) {
            OverlayService.stopOverlay(context)
            _statusBannerMessage.value = "Stopped OverlayService translucent control panel."
        } else {
            OverlayService.startOverlay(context)
            _statusBannerMessage.value =
                "OverlayService started! Translucent control panel (FLAG_NOT_FOCUSABLE) is now floating on screen."
        }
    }

    fun openManageOverlayPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    fun toggleCameraPreviewInOverlay() {
        val next = !SwipeTelemetryRepository.isCameraPreviewEnabledInOverlay.value
        SwipeTelemetryRepository.setCameraPreviewEnabledInOverlay(next)
    }

    fun triggerSwipe(direction: String, source: String = "Manual Control Pad") {
        val dispatched = performSwipeUseCase(direction, source)
        val actionName = SwipeTelemetryRepository.mapDirectionToGameAction(direction)
        if (!dispatched && !SwipeControllerService.isServiceActive()) {
            _statusBannerMessage.value =
                "Action '$actionName' ($direction) simulated in-app. Enable SwipeController in Accessibility Settings for cross-app OS gestures."
        } else {
            _statusBannerMessage.value =
                "Triggered $actionName via performSwipe(\"$direction\")."
        }
    }

    fun toggleAutoJumpLoop() {
        val currentlyRunning = SwipeTelemetryRepository.isAutoJumpRunning.value
        val nextState = !currentlyRunning

        if (SwipeControllerService.isServiceActive()) {
            localAutoJumpJob?.cancel()
            performSwipeUseCase.setAutoJumpActive(nextState)
            _statusBannerMessage.value = if (nextState) {
                "Milestone 3s Auto-Jump started via SwipeControllerService."
            } else {
                "Milestone 3s Auto-Jump stopped."
            }
        } else {
            if (nextState) {
                startLocalFallbackAutoJumpLoop()
                _statusBannerMessage.value =
                    "Milestone 3s Auto-Jump active! Calling SwipeControllerService.performSwipe(\"UP\") every 3s."
            } else {
                stopLocalFallbackAutoJumpLoop()
                _statusBannerMessage.value = "Milestone 3s Auto-Jump stopped."
            }
        }
    }

    private fun startLocalFallbackAutoJumpLoop() {
        localAutoJumpJob?.cancel()
        SwipeTelemetryRepository.setAutoJumpRunning(true)
        localAutoJumpJob = viewModelScope.launch {
            SwipeControllerService.performSwipe(SwipeControllerService.DIRECTION_UP)
            SwipeTelemetryRepository.incrementAutoJumpCount()

            while (isActive && SwipeTelemetryRepository.isAutoJumpRunning.value) {
                val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
                var remaining = intervalMs
                while (remaining > 0L && isActive && SwipeTelemetryRepository.isAutoJumpRunning.value) {
                    SwipeTelemetryRepository.updateAutoJumpCountdown(remaining)
                    delay(100L)
                    remaining -= 100L
                }
                if (isActive && SwipeTelemetryRepository.isAutoJumpRunning.value) {
                    SwipeControllerService.performSwipe(SwipeControllerService.DIRECTION_UP)
                    SwipeTelemetryRepository.incrementAutoJumpCount()
                }
            }
        }
    }

    private fun stopLocalFallbackAutoJumpLoop() {
        localAutoJumpJob?.cancel()
        localAutoJumpJob = null
        SwipeTelemetryRepository.setAutoJumpRunning(false)
    }

    fun toggleSystemFloatingOverlay() {
        if (!SwipeControllerService.isServiceActive()) {
            _isInAppFloatingFabVisible.value = true
            _statusBannerMessage.value =
                "In-app Translucent HUD enabled. Grant Overlay or Accessibility permission to float over external games."
            return
        }
        val shown = performSwipeUseCase.toggleFloatingMilestoneOverlay()
        _statusBannerMessage.value = if (shown) {
            "Accessibility Overlay attached! Open Subway Surfers to test."
        } else {
            "Accessibility Overlay hidden."
        }
    }

    fun setInAppFloatingFabVisible(visible: Boolean) {
        _isInAppFloatingFabVisible.value = visible
    }

    fun setRunnerSandboxOpen(open: Boolean) {
        _isRunnerSandboxOpen.value = open
    }

    fun updateSwipeDuration(durationMs: Long) {
        SwipeTelemetryRepository.updateTuningConfig(swipeDurationMs = durationMs)
    }

    fun updateSwipeAmplitude(fraction: Float) {
        SwipeTelemetryRepository.updateTuningConfig(swipeAmplitudeFraction = fraction)
    }

    fun updateMilestoneInterval(intervalMs: Long) {
        SwipeTelemetryRepository.updateTuningConfig(milestoneIntervalMs = intervalMs)
    }

    fun clearLogs() {
        SwipeTelemetryRepository.clearLogs()
        _statusBannerMessage.value = null
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun launchSubwaySurfersOrSandbox(context: Context) {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(SUBWAY_SURFERS_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            _isRunnerSandboxOpen.value = true
            _statusBannerMessage.value =
                "Subway Surfers APK not detected on device — opened Built-In Subway Runner Sandbox with Translucent Overlay HUD!"
        }
    }

    private fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, SwipeControllerService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    override fun onCleared() {
        localAutoJumpJob?.cancel()
        inAppMotionTracker?.stopListening()
        super.onCleared()
    }

    companion object {
        const val SUBWAY_SURFERS_PACKAGE = "com.kiloo.subwaysurf"
    }
}
