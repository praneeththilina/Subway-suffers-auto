package com.example.feature.swipe.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.feature.swipe.domain.model.GestureTelemetryEvent
import com.example.feature.swipe.presentation.components.DraggableMilestoneFloatingButton
import com.example.feature.swipe.presentation.components.GestureTrajectoryCanvas
import com.example.feature.swipe.presentation.components.SubwayRunnerSandboxModal
import com.example.service.SwipeControllerService
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SwipeControllerScreen(
    viewModel: SwipeControllerViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isRunnerSandboxOpen) {
                SubwayRunnerSandboxModal(
                    isAutoJumpRunning = uiState.isAutoJumpRunning,
                    autoJumpCountdownMs = uiState.autoJumpCountdownMs,
                    autoJumpCount = uiState.autoJumpTotalCount,
                    latestDispatchedDirection = uiState.latestDispatchedDirection,
                    onToggleAutoJump = { viewModel.toggleAutoJumpLoop() },
                    onTriggerManualSwipe = { dir ->
                        viewModel.triggerSwipe(dir, "Runner Sandbox Gesture")
                    },
                    onClose = { viewModel.setRunnerSandboxOpen(false) }
                )
            } else {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val isWideScreen = maxWidth >= 700.dp

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = if (isWideScreen) 960.dp else 600.dp)
                            .testTag("main_controller_list"),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 128.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            HeroAccessibilityBannerCard(
                                isServiceConnected = uiState.isServiceConnected,
                                statusBannerMessage = uiState.statusBannerMessage,
                                onOpenAccessibilitySettings = {
                                    viewModel.openAccessibilitySettings(context)
                                },
                                onRefreshStatus = {
                                    viewModel.refreshAccessibilityStatus(context)
                                }
                            )
                        }

                        item {
                            MilestoneAutoJumpCard(
                                isServiceConnected = uiState.isServiceConnected,
                                isFloatingOverlayVisible = uiState.isFloatingOverlayVisible,
                                isInAppFloatingFabVisible = uiState.isInAppFloatingFabVisible,
                                isAutoJumpRunning = uiState.isAutoJumpRunning,
                                autoJumpCountdownMs = uiState.autoJumpCountdownMs,
                                intervalMs = uiState.tuningConfig.milestoneIntervalMs,
                                autoJumpTotalCount = uiState.autoJumpTotalCount,
                                onToggleAutoJump = { viewModel.toggleAutoJumpLoop() },
                                onToggleSystemOverlay = { viewModel.toggleSystemFloatingOverlay() },
                                onShowInAppFloatingFab = {
                                    viewModel.setInAppFloatingFabVisible(!uiState.isInAppFloatingFabVisible)
                                },
                                onLaunchSubwaySurfers = {
                                    viewModel.launchSubwaySurfersOrSandbox(context)
                                },
                                onOpenRunnerSandbox = {
                                    viewModel.setRunnerSandboxOpen(true)
                                }
                            )
                        }

                        item {
                            ManualGestureDeckCard(
                                latestStroke = uiState.latestGestureStroke,
                                latestDirectionPair = uiState.latestDispatchedDirection,
                                onDispatchDirection = { direction ->
                                    viewModel.triggerSwipe(direction, "Manual D-Pad")
                                }
                            )
                        }

                        item {
                            GestureTuningCard(
                                durationMs = uiState.tuningConfig.swipeDurationMs,
                                amplitudeFraction = uiState.tuningConfig.swipeAmplitudeFraction,
                                milestoneIntervalMs = uiState.tuningConfig.milestoneIntervalMs,
                                onDurationChange = { viewModel.updateSwipeDuration(it) },
                                onAmplitudeChange = { viewModel.updateSwipeAmplitude(it) },
                                onIntervalChange = { viewModel.updateMilestoneInterval(it) }
                            )
                        }

                        item {
                            TelemetryLogHeader(
                                logCount = uiState.telemetryLogs.size,
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }

                        if (uiState.telemetryLogs.isEmpty()) {
                            item {
                                EmptyTelemetryPlaceholder()
                            }
                        } else {
                            items(
                                items = uiState.telemetryLogs,
                                key = { it.id }
                            ) { event ->
                                TelemetryEventRow(event = event)
                            }
                        }
                    }
                }
            }

            // Temporary Draggable Floating Button for the Milestone Test
            // Visible over both the Controller Dashboard and the Subway Runner Sandbox!
            DraggableMilestoneFloatingButton(
                visible = uiState.isInAppFloatingFabVisible,
                isAutoJumpRunning = uiState.isAutoJumpRunning,
                countdownMs = uiState.autoJumpCountdownMs,
                jumpCount = uiState.autoJumpTotalCount,
                onToggleAutoJump = { viewModel.toggleAutoJumpLoop() },
                onInstantSwipeUp = {
                    SwipeControllerService.performSwipe(SwipeControllerService.DIRECTION_UP)
                },
                onDismiss = { viewModel.setInAppFloatingFabVisible(false) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun HeroAccessibilityBannerCard(
    isServiceConnected: Boolean,
    statusBannerMessage: String?,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isServiceConnected) EmeraldPulse.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(22.dp)
            )
            .testTag("hero_accessibility_card")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_swipe_banner_1794139347897),
                    contentDescription = stringResource(R.string.hero_title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x33090D16),
                                    Color(0xCC090D16),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )

                // Service Status Badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xDD090D16))
                        .border(
                            1.dp,
                            if (isServiceConnected) EmeraldPulse else AmberWarning,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isServiceConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isServiceConnected) EmeraldPulse else AmberWarning,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceConnected) {
                            stringResource(R.string.service_status_active)
                        } else {
                            stringResource(R.string.service_status_inactive)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isServiceConnected) EmeraldPulse else AmberWarning
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hero_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF041318)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_accessibility_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsAccessibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.enable_accessibility_btn),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onRefreshStatus,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("refresh_service_status_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_status_btn),
                            tint = ElectricCyan
                        )
                    }
                }

                AnimatedVisibility(visible = statusBannerMessage != null) {
                    statusBannerMessage?.let { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0C1928))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneAutoJumpCard(
    isServiceConnected: Boolean,
    isFloatingOverlayVisible: Boolean,
    isInAppFloatingFabVisible: Boolean,
    isAutoJumpRunning: Boolean,
    autoJumpCountdownMs: Long,
    intervalMs: Long,
    autoJumpTotalCount: Int,
    onToggleAutoJump: () -> Unit,
    onToggleSystemOverlay: () -> Unit,
    onShowInAppFloatingFab: () -> Unit,
    onLaunchSubwaySurfers: () -> Unit,
    onOpenRunnerSandbox: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isAutoJumpRunning) KineticCoral else ElectricCyan.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .testTag("milestone_auto_jump_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isAutoJumpRunning) KineticCoral.copy(alpha = 0.2f)
                                else ElectricCyan.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = if (isAutoJumpRunning) KineticCoral else ElectricCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.milestone_card_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "SwipeControllerService.performSwipe(\"UP\")",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = ElectricCyan
                        )
                    }
                }

                // Jump Counter Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "JUMPS: $autoJumpTotalCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = EmeraldPulse
                    )
                }
            }

            Text(
                text = stringResource(R.string.milestone_card_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Progress Countdown Bar
            val progressFraction = if (isAutoJumpRunning && intervalMs > 0L) {
                (autoJumpCountdownMs.toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAutoJumpRunning) {
                            "AUTO-JUMP LOOP ACTIVE"
                        } else {
                            "STANDBY (${String.format(Locale.US, "%.1fs", intervalMs / 1000f)} PERIOD)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAutoJumpRunning) KineticCoral else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fs", autoJumpCountdownMs / 1000f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = ElectricCyan
                    )
                }
                LinearProgressIndicator(
                    progress = { progressFraction },
                    color = if (isAutoJumpRunning) KineticCoral else ElectricCyan,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }

            // Primary Action Row: Start/Stop 3s Auto-Jump + Floating Overlay Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleAutoJump,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAutoJumpRunning) KineticCoral else ElectricCyan,
                        contentColor = if (isAutoJumpRunning) Color.White else Color(0xFF041318)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("toggle_auto_jump_button")
                ) {
                    Icon(
                        imageVector = if (isAutoJumpRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAutoJumpRunning) {
                            stringResource(R.string.stop_auto_jump)
                        } else {
                            stringResource(R.string.start_auto_jump)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (isServiceConnected) {
                            onToggleSystemOverlay()
                        } else {
                            onShowInAppFloatingFab()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("toggle_floating_overlay_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceConnected) {
                            if (isFloatingOverlayVisible) "Hide OS Overlay" else "OS Overlay"
                        } else {
                            if (isInAppFloatingFabVisible) "Hide Float Btn" else "Show Float Btn"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Secondary Row: Launch Subway Surfers OR Open Built-In Subway Runner Sandbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onLaunchSubwaySurfers,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("launch_subway_surfers_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.launch_subway_surfers),
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenRunnerSandbox,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("open_runner_sandbox_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = EmeraldPulse,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Runner Sandbox",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldPulse,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualGestureDeckCard(
    latestStroke: com.example.feature.swipe.domain.model.GestureStrokePoint?,
    latestDirectionPair: Pair<String, Long>?,
    onDispatchDirection: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .testTag("manual_gesture_deck_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.manual_deck_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.manual_deck_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Tactical 5-Button Directional Pad (UP, LEFT, DOUBLE_TAP, RIGHT, DOWN)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // UP Button
                DirectionalPadButton(
                    label = "UP (JUMP)",
                    directionCode = SwipeControllerService.DIRECTION_UP,
                    icon = Icons.Default.KeyboardArrowUp,
                    isPrimary = true,
                    onClick = { onDispatchDirection(SwipeControllerService.DIRECTION_UP) },
                    testTag = "swipe_up_button"
                )

                // LEFT + DOUBLE_TAP + RIGHT Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DirectionalPadButton(
                        label = "LEFT",
                        directionCode = SwipeControllerService.DIRECTION_LEFT,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        isPrimary = false,
                        onClick = { onDispatchDirection(SwipeControllerService.DIRECTION_LEFT) },
                        modifier = Modifier.weight(1f),
                        testTag = "swipe_left_button"
                    )

                    Button(
                        onClick = { onDispatchDirection(SwipeControllerService.DIRECTION_DOUBLE_TAP) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KineticCoral,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(56.dp)
                            .testTag("swipe_double_tap_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DOUBLE_TAP",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    DirectionalPadButton(
                        label = "RIGHT",
                        directionCode = SwipeControllerService.DIRECTION_RIGHT,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        isPrimary = false,
                        onClick = { onDispatchDirection(SwipeControllerService.DIRECTION_RIGHT) },
                        modifier = Modifier.weight(1f),
                        testTag = "swipe_right_button"
                    )
                }

                // DOWN Button
                DirectionalPadButton(
                    label = "DOWN (ROLL)",
                    directionCode = SwipeControllerService.DIRECTION_DOWN,
                    icon = Icons.Default.KeyboardArrowDown,
                    isPrimary = false,
                    onClick = { onDispatchDirection(SwipeControllerService.DIRECTION_DOWN) },
                    testTag = "swipe_down_button"
                )
            }

            // Live Gesture Vector Trajectory Canvas
            GestureTrajectoryCanvas(
                latestStroke = latestStroke,
                latestDirectionPair = latestDirectionPair
            )
        }
    }
}

@Composable
private fun DirectionalPadButton(
    label: String,
    directionCode: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isPrimary) ElectricCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isPrimary) ElectricCyan else MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            .height(54.dp)
            .border(
                width = 1.dp,
                color = if (isPrimary) ElectricCyan else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = directionCode,
            tint = ElectricCyan
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun GestureTuningCard(
    durationMs: Long,
    amplitudeFraction: Float,
    milestoneIntervalMs: Long,
    onDurationChange: (Long) -> Unit,
    onAmplitudeChange: (Float) -> Unit,
    onIntervalChange: (Long) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .testTag("gesture_tuning_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.tuning_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Duration Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.swipe_duration_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = ElectricCyan
                    )
                }
                Slider(
                    value = durationMs.toFloat(),
                    onValueChange = { onDurationChange(it.toLong()) },
                    valueRange = 60f..500f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("duration_slider")
                )
            }

            // Amplitude Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.swipe_amplitude_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(amplitudeFraction * 100).toInt()}% of screen",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = ElectricCyan
                    )
                }
                Slider(
                    value = amplitudeFraction,
                    onValueChange = { onAmplitudeChange(it) },
                    valueRange = 0.15f..0.45f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("amplitude_slider")
                )
            }

            // Milestone Auto-Jump Interval Slider (default 3000ms = 3.0s)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.auto_interval_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f sec (%d ms)", milestoneIntervalMs / 1000f, milestoneIntervalMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = KineticCoral
                    )
                }
                Slider(
                    value = milestoneIntervalMs.toFloat(),
                    onValueChange = { onIntervalChange((it / 500f).toLong() * 500L) },
                    valueRange = 1000f..6000f,
                    colors = SliderDefaults.colors(
                        thumbColor = KineticCoral,
                        activeTrackColor = KineticCoral
                    ),
                    modifier = Modifier.testTag("interval_slider")
                )
            }
        }
    }
}

@Composable
private fun TelemetryLogHeader(
    logCount: Int,
    onClearLogs: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.telemetry_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = logCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = ElectricCyan
                )
            }
        }

        if (logCount > 0) {
            TextButton(
                onClick = onClearLogs,
                modifier = Modifier.testTag("clear_telemetry_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clear_logs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyTelemetryPlaceholder() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = ElectricCyan.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.empty_logs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelemetryEventRow(event: GestureTelemetryEvent) {
    val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val formattedTime = timeFormatter.format(Date(event.timestampMillis))

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (event.direction == "DOUBLE_TAP") KineticCoral.copy(alpha = 0.2f)
                                else ElectricCyan.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "performSwipe(\"${event.direction}\")",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = if (event.direction == "DOUBLE_TAP") KineticCoral else ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = event.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Path: (${event.startX}, ${event.startY}) → (${event.endX}, ${event.endY}) • ${event.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = event.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (event.dispatchedViaService) EmeraldPulse else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
