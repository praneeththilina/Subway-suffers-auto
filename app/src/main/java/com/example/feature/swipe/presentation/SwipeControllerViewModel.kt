package com.example.feature.swipe.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.domain.model.SwipeTuningConfig
import com.example.feature.swipe.domain.usecase.PerformSwipeUseCase
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

    private val _isInAppFloatingFabVisible = MutableStateFlow(true)
    private val _isRunnerSandboxOpen = MutableStateFlow(false)
    private val _statusBannerMessage = MutableStateFlow<String?>(null)

    private var localAutoJumpJob: Job? = null

    val uiState: StateFlow<SwipeControllerUiState> = combine(
        SwipeTelemetryRepository.isServiceConnected,
        SwipeTelemetryRepository.isFloatingOverlayVisible,
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
            isAutoJumpRunning = values[2] as Boolean,
            autoJumpCountdownMs = values[3] as Long,
            autoJumpTotalCount = values[4] as Int,
            tuningConfig = values[5] as SwipeTuningConfig,
            telemetryLogs = values[6] as List<GestureTelemetryEvent>,
            latestGestureStroke = values[7] as GestureStrokePoint?,
            latestDispatchedDirection = values[8] as Pair<String, Long>?,
            isInAppFloatingFabVisible = values[9] as Boolean,
            isRunnerSandboxOpen = values[10] as Boolean,
            statusBannerMessage = values[11] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SwipeControllerUiState()
    )

    fun refreshAccessibilityStatus(context: Context) {
        val enabledInSettings = isAccessibilityServiceEnabledInSettings(context)
        val activeInstance = SwipeControllerService.isServiceActive()
        SwipeTelemetryRepository.setServiceConnected(enabledInSettings || activeInstance)
    }

    fun triggerSwipe(direction: String, source: String = "Manual Control Pad") {
        val dispatched = performSwipeUseCase(direction, source)
        if (!dispatched && !SwipeControllerService.isServiceActive()) {
            _statusBannerMessage.value =
                "Swipe '$direction' simulated in-app. Enable SwipeController in Accessibility Settings for cross-app OS gestures."
        } else {
            _statusBannerMessage.value = "Dispatched performSwipe(\"$direction\") via GestureDescription."
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
            // Run the 3-second performSwipe("UP") loop inside ViewModel coroutine so the user
            // can test the Milestone timer and built-in Subway Runner Sandbox immediately even
            // before toggling Accessibility Settings.
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
            // Immediate first jump
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
                    // Explicitly invoke the required static method performSwipe("UP")
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
                "In-app Floating Milestone Button enabled. Enable Accessibility Service to float over external games."
            return
        }
        val shown = performSwipeUseCase.toggleFloatingMilestoneOverlay()
        _statusBannerMessage.value = if (shown) {
            "System TYPE_ACCESSIBILITY_OVERLAY floating button attached! Open Subway Surfers to test."
        } else {
            "System floating overlay hidden."
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
            // If Subway Surfers is not installed on the cloud emulator, open the built-in
            // Subway Runner Milestone Sandbox so the user can immediately watch the character
            // jump automatically every 3 seconds when performSwipe("UP") fires!
            _isRunnerSandboxOpen.value = true
            _statusBannerMessage.value =
                "Subway Surfers APK not detected on device — opened Built-In Subway Runner Sandbox to watch 3s Auto-Jump live!"
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
        super.onCleared()
    }

    companion object {
        const val SUBWAY_SURFERS_PACKAGE = "com.kiloo.subwaysurf"
    }
}
