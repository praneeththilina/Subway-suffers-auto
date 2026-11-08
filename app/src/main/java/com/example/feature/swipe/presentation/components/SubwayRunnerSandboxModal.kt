package com.example.feature.swipe.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.domain.model.RunnerLane
import com.example.service.SwipeControllerService
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun SubwayRunnerSandboxModal(
    isAutoJumpRunning: Boolean,
    autoJumpCountdownMs: Long,
    autoJumpCount: Int,
    latestDispatchedDirection: Pair<String, Long>?,
    onToggleAutoJump: () -> Unit,
    onTriggerManualSwipe: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onClose() }

    val visionState by SwipeTelemetryRepository.screenVisionState.collectAsState()
    val currentLane = visionState.currentLane

    var actionStatus by remember { mutableStateOf("START IN MIDDLE COLUMN (0)") }
    var isHoverboardActive by remember { mutableStateOf(false) }
    var obstaclesDodgedScore by remember { mutableIntStateOf(0) }

    val jumpProgress = remember { Animatable(0f) }
    val rollProgress = remember { Animatable(0f) }

    val animatedLaneOffset by animateFloatAsState(
        targetValue = currentLane.columnIndex.toFloat(),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "lane_offset"
    )

    // React automatically whenever SwipeControllerService.performSwipe(...) is called!
    // Note: currentLane is already updated & boundary-checked in SwipeTelemetryRepository!
    LaunchedEffect(latestDispatchedDirection) {
        val pair = latestDispatchedDirection ?: return@LaunchedEffect
        when (pair.first) {
            SwipeControllerService.DIRECTION_UP -> {
                actionStatus = "⚡ JUMP in ${currentLane.shortCode}!"
                obstaclesDodgedScore += 1
                jumpProgress.snapTo(0f)
                jumpProgress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                jumpProgress.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                actionStatus = "COLUMN: ${currentLane.label}"
            }
            SwipeControllerService.DIRECTION_DOWN -> {
                actionStatus = "⬇ ROLL in ${currentLane.shortCode}!"
                obstaclesDodgedScore += 1
                rollProgress.snapTo(0f)
                rollProgress.animateTo(1f, tween(280))
                rollProgress.animateTo(0f, tween(260))
                actionStatus = "COLUMN: ${currentLane.label}"
            }
            SwipeControllerService.DIRECTION_LEFT -> {
                obstaclesDodgedScore += 1
                actionStatus =
                    "⬅ MOVED LEFT → ${currentLane.shortCode} (Can: ${currentLane.remainingStepsLeft}L, ${currentLane.remainingStepsRight}R)"
            }
            SwipeControllerService.DIRECTION_RIGHT -> {
                obstaclesDodgedScore += 1
                actionStatus =
                    "➡ MOVED RIGHT → ${currentLane.shortCode} (Can: ${currentLane.remainingStepsLeft}L, ${currentLane.remainingStepsRight}R)"
            }
            SwipeControllerService.DIRECTION_DOUBLE_TAP -> {
                isHoverboardActive = !isHoverboardActive
                actionStatus = "🛹 HOVERBOARD in ${currentLane.shortCode}!"
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "track_ties")
    val trackPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Multi-column obstacle wave (-1 = Left, 0 = Middle, +1 = Right)
    var obstacleProgress by remember { mutableFloatStateOf(0f) }
    var obstacleLaneIndex by remember { mutableIntStateOf(0) }
    var obstacleType by remember { mutableStateOf("TRAIN") }
    var autoEvadedCurrentWave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val sequenceLanes = listOf(0, -1, 0, 1, 0, -1, 1)
        val sequenceTypes = listOf("TRAIN", "LOW_BARRIER", "TRAIN", "HIGH_BARRIER")
        var waveCounter = 0

        while (true) {
            delay(16L)
            val nextProgress = obstacleProgress + 0.0075f
            if (nextProgress >= 1f) {
                obstacleProgress = 0f
                waveCounter++
                obstacleLaneIndex = sequenceLanes[waveCounter % sequenceLanes.size]
                obstacleType = sequenceTypes[waveCounter % sequenceTypes.size]
                autoEvadedCurrentWave = false
            } else {
                obstacleProgress = nextProgress
            }

            // Compute real-time 3-column display hazards from the live track
            val leftHaz = if (obstacleLaneIndex == -1) obstacleProgress else 0.08f
            val midHaz = if (obstacleLaneIndex == 0) obstacleProgress else 0.08f
            val rightHaz = if (obstacleLaneIndex == 1) obstacleProgress else 0.08f

            val recommendedAction = SwipeTelemetryRepository.updateScreenVisionReadingAndDecideAction(
                leftHazard = leftHaz,
                middleHazard = midHaz,
                rightHazard = rightHaz,
                detectedPlayerColumn = null,
                obstacleType = obstacleType
            )

            // If Display Reader Autopilot is enabled, automatically dispatch the evasive swipe
            // when the obstacle enters the danger zone (progress >= 0.64f)!
            if (visionState.isAutopilotEnabled &&
                !autoEvadedCurrentWave &&
                recommendedAction != null &&
                obstacleProgress >= 0.64f
            ) {
                autoEvadedCurrentWave = true
                SwipeControllerService.performSwipeWithSource(
                    recommendedAction,
                    "Display Reader Autopilot"
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .padding(16.dp)
            .testTag("runner_sandbox_screen")
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("close_sandbox_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close_sandbox),
                        tint = ElectricCyan
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.sandbox_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "3 Columns: Left (-1) • Middle (0) • Right (+1)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF111827)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "COL: ${currentLane.shortCode}",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldPulse
                    )
                    Text(
                        text = "Can: ${currentLane.remainingStepsLeft}L / ${currentLane.remainingStepsRight}R • #$obstaclesDodgedScore",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = ElectricCyan
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live 3-Lane Endless Runner Track Canvas
        var dragDeltaX by remember { mutableFloatStateOf(0f) }
        var dragDeltaY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onTriggerManualSwipe(SwipeControllerService.DIRECTION_DOUBLE_TAP)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragDeltaX = 0f
                            dragDeltaY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDeltaX += dragAmount.x
                            dragDeltaY += dragAmount.y
                        },
                        onDragEnd = {
                            if (abs(dragDeltaX) > abs(dragDeltaY) && abs(dragDeltaX) > 40f) {
                                if (dragDeltaX > 0) {
                                    onTriggerManualSwipe(SwipeControllerService.DIRECTION_RIGHT)
                                } else {
                                    onTriggerManualSwipe(SwipeControllerService.DIRECTION_LEFT)
                                }
                            } else if (abs(dragDeltaY) > 40f) {
                                if (dragDeltaY < 0) {
                                    onTriggerManualSwipe(SwipeControllerService.DIRECTION_UP)
                                } else {
                                    onTriggerManualSwipe(SwipeControllerService.DIRECTION_DOWN)
                                }
                            }
                        }
                    )
                }
                .testTag("runner_track_canvas")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF06101E), Color(0xFF0F172A), Color(0xFF090D16))
                    )
                )

                val horizonY = h * 0.22f
                drawLine(
                    color = ElectricCyan.copy(alpha = 0.35f),
                    start = Offset(0f, horizonY),
                    end = Offset(w, horizonY),
                    strokeWidth = 3f
                )

                val topSpread = w * 0.24f
                val bottomSpread = w * 0.86f

                for (laneIdx in -1..1) {
                    val topX = (w / 2f) + laneIdx * (topSpread / 2f)
                    val bottomX = (w / 2f) + laneIdx * (bottomSpread / 3f)
                    val isPlayerInThisLane = currentLane.columnIndex == laneIdx

                    drawLine(
                        color = if (isPlayerInThisLane) EmeraldPulse.copy(alpha = 0.65f) else Color(0xFF334155),
                        start = Offset(topX - 10f, horizonY),
                        end = Offset(bottomX - 38f, h),
                        strokeWidth = if (isPlayerInThisLane) 6f else 4f
                    )
                    drawLine(
                        color = if (isPlayerInThisLane) EmeraldPulse.copy(alpha = 0.65f) else Color(0xFF334155),
                        start = Offset(topX + 10f, horizonY),
                        end = Offset(bottomX + 38f, h),
                        strokeWidth = if (isPlayerInThisLane) 6f else 4f
                    )

                    drawLine(
                        color = if (isPlayerInThisLane) EmeraldPulse.copy(alpha = 0.45f) else ElectricCyan.copy(alpha = 0.2f),
                        start = Offset(topX, horizonY),
                        end = Offset(bottomX, h),
                        strokeWidth = 2.5f
                    )
                }

                // Display Reader Lookahead Scan Band (42% .. 60% Y)
                if (visionState.isAutopilotEnabled) {
                    drawRect(
                        color = EmeraldPulse.copy(alpha = 0.08f),
                        topLeft = Offset(0f, h * 0.42f),
                        size = Size(w, h * 0.18f)
                    )
                    drawLine(
                        color = EmeraldPulse.copy(alpha = 0.55f),
                        start = Offset(0f, h * 0.55f),
                        end = Offset(w, h * 0.55f),
                        strokeWidth = 2f
                    )
                }

                val numSleepers = 9
                for (i in 0 until numSleepers) {
                    val t = ((i.toFloat() / numSleepers) + trackPhase) % 1f
                    val y = horizonY + (h - horizonY) * (t * t)
                    val halfWidth = (topSpread * 0.6f) + (bottomSpread * 0.55f - topSpread * 0.6f) * (t * t)
                    drawLine(
                        color = ElectricCyan.copy(alpha = 0.15f + 0.35f * t),
                        start = Offset(w / 2f - halfWidth, y),
                        end = Offset(w / 2f + halfWidth, y),
                        strokeWidth = 2f + 5f * t
                    )
                }

                // Draw Approaching Obstacle in obstacleLaneIndex (-1, 0, or +1)
                val barrierT = obstacleProgress
                val barrierY = horizonY + (h * 0.78f - horizonY) * (barrierT * barrierT)
                val barrierScale = 0.25f + 0.75f * (barrierT * barrierT)
                val topObsX = (w / 2f) + obstacleLaneIndex * (topSpread / 2f)
                val bottomObsX = (w / 2f) + obstacleLaneIndex * (bottomSpread / 3f)
                val barrierLaneX = topObsX + (bottomObsX - topObsX) * (barrierT * barrierT)

                val barrierWidth = 118f * barrierScale
                val barrierHeight = if (obstacleType == "TRAIN") 78f * barrierScale else 38f * barrierScale
                val obstacleColor = when (obstacleType) {
                    "TRAIN" -> KineticCoral
                    "HIGH_BARRIER" -> AmberWarning
                    else -> Color(0xFFF43F5E)
                }

                drawRoundRect(
                    color = obstacleColor.copy(alpha = 0.90f),
                    topLeft = Offset(barrierLaneX - barrierWidth / 2f, barrierY - barrierHeight),
                    size = Size(barrierWidth, barrierHeight),
                    cornerRadius = CornerRadius(10f, 10f)
                )

                // Draw Surfer Character at bottom in currentLane
                val playerBaseY = h * 0.78f
                val playerLaneX = (w / 2f) + animatedLaneOffset * (bottomSpread / 3f)
                val jumpOffsetPx = jumpProgress.value * (h * 0.26f)
                val currentPlayerY = playerBaseY - jumpOffsetPx

                val shadowRadius = 46f * (1f - jumpProgress.value * 0.5f)
                drawOval(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(playerLaneX - shadowRadius, playerBaseY + 12f),
                    size = Size(shadowRadius * 2f, 22f)
                )

                if (isHoverboardActive) {
                    val boardPath = Path().apply {
                        moveTo(playerLaneX - 54f, currentPlayerY + 18f)
                        lineTo(playerLaneX + 54f, currentPlayerY + 18f)
                        lineTo(playerLaneX + 38f, currentPlayerY + 32f)
                        lineTo(playerLaneX - 38f, currentPlayerY + 32f)
                        close()
                    }
                    drawPath(path = boardPath, color = KineticCoral)
                }

                val bodyHeight = if (rollProgress.value > 0.2f) 56f else 96f
                val bodyWidth = if (rollProgress.value > 0.2f) 68f else 54f

                if (jumpProgress.value > 0.05f) {
                    drawCircle(
                        color = ElectricCyan.copy(alpha = 0.45f * jumpProgress.value),
                        radius = 74f,
                        center = Offset(playerLaneX, currentPlayerY - bodyHeight / 2f),
                        style = Stroke(width = 6f)
                    )
                }

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (jumpProgress.value > 0.05f) {
                            listOf(ElectricCyan, EmeraldPulse)
                        } else {
                            listOf(ElectricCyan, Color(0xFF0284C7))
                        }
                    ),
                    topLeft = Offset(playerLaneX - bodyWidth / 2f, currentPlayerY - bodyHeight),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = CornerRadius(18f, 18f)
                )

                drawCircle(
                    color = Color.White,
                    radius = 18f,
                    center = Offset(playerLaneX, currentPlayerY - bodyHeight - 16f)
                )
            }

            // Status HUD pill overlay at top of track
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xDD090D16))
                        .border(
                            1.dp,
                            if (visionState.blockedBoundaryMoveMessage != null) KineticCoral else ElectricCyan,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = visionState.blockedBoundaryMoveMessage ?: actionStatus,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            visionState.blockedBoundaryMoveMessage != null -> KineticCoral
                            jumpProgress.value > 0.05f -> EmeraldPulse
                            else -> ElectricCyan
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Controls inside Sandbox: Display Autopilot Toggle + Reset Middle + Jump Now
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    SwipeTelemetryRepository.setAutopilotEnabled(!visionState.isAutopilotEnabled)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (visionState.isAutopilotEnabled) KineticCoral else EmeraldPulse,
                    contentColor = if (visionState.isAutopilotEnabled) Color.White else Color(0xFF022C22)
                ),
                modifier = Modifier
                    .weight(1.3f)
                    .height(50.dp)
                    .testTag("sandbox_toggle_autopilot_button")
            ) {
                Icon(
                    imageVector = if (visionState.isAutopilotEnabled) Icons.Default.Stop else Icons.Default.Visibility,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (visionState.isAutopilotEnabled) "STOP AUTOPILOT" else "DISPLAY AUTOPILOT",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = {
                    SwipeTelemetryRepository.resetLaneToMiddle()
                    actionStatus = "RESET TO MIDDLE COLUMN (0)"
                },
                modifier = Modifier
                    .height(50.dp)
                    .testTag("sandbox_reset_middle_button")
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset to Middle",
                    tint = EmeraldPulse
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("M(0)", color = EmeraldPulse)
            }

            FilledTonalButton(
                onClick = { onTriggerManualSwipe(SwipeControllerService.DIRECTION_UP) },
                modifier = Modifier
                    .height(50.dp)
                    .testTag("sandbox_manual_jump_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = ElectricCyan
                )
            }
        }
    }
}
