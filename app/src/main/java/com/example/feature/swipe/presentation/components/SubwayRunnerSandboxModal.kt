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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.service.SwipeControllerService
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

    // 3 lanes: -1 (Left), 0 (Center), 1 (Right)
    var currentLane by remember { mutableIntStateOf(0) }
    var actionStatus by remember { mutableStateOf("RUNNING ON TRACK") }
    var isHoverboardActive by remember { mutableStateOf(false) }
    var localJumpScore by remember { mutableIntStateOf(0) }

    val jumpProgress = remember { Animatable(0f) }
    val rollProgress = remember { Animatable(0f) }

    val animatedLaneOffset by animateFloatAsState(
        targetValue = currentLane.toFloat(),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "lane_offset"
    )

    // React automatically whenever SwipeControllerService.performSwipe(...) is called!
    LaunchedEffect(latestDispatchedDirection) {
        val pair = latestDispatchedDirection ?: return@LaunchedEffect
        when (pair.first) {
            SwipeControllerService.DIRECTION_UP -> {
                actionStatus = "⚡ AUTO-JUMP! performSwipe(\"UP\")"
                localJumpScore += 1
                jumpProgress.snapTo(0f)
                jumpProgress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
                jumpProgress.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                actionStatus = "RUNNING ON TRACK"
            }
            SwipeControllerService.DIRECTION_DOWN -> {
                actionStatus = "⬇ ROLL! performSwipe(\"DOWN\")"
                rollProgress.snapTo(0f)
                rollProgress.animateTo(1f, tween(280))
                rollProgress.animateTo(0f, tween(260))
                actionStatus = "RUNNING ON TRACK"
            }
            SwipeControllerService.DIRECTION_LEFT -> {
                currentLane = (currentLane - 1).coerceAtLeast(-1)
                actionStatus = "⬅ LANE LEFT! performSwipe(\"LEFT\")"
            }
            SwipeControllerService.DIRECTION_RIGHT -> {
                currentLane = (currentLane + 1).coerceAtMost(1)
                actionStatus = "➡ LANE RIGHT! performSwipe(\"RIGHT\")"
            }
            SwipeControllerService.DIRECTION_DOUBLE_TAP -> {
                isHoverboardActive = !isHoverboardActive
                actionStatus = "🛹 HOVERBOARD! performSwipe(\"DOUBLE_TAP\")"
            }
        }
    }

    // Infinite track tie animation
    val infiniteTransition = rememberInfiniteTransition(label = "track_ties")
    val trackPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Obstacle train barrier synced to approach the player
    var obstacleProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(16L)
            obstacleProgress = (obstacleProgress + 0.0055f) % 1f
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
                        text = stringResource(R.string.sandbox_subtitle),
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
                        text = "JUMPS: $localJumpScore",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    Text(
                        text = if (isAutoJumpRunning) {
                            String.format("Next UP: %.1fs", autoJumpCountdownMs / 1000f)
                        } else {
                            "3s Timer Paused"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = if (isAutoJumpRunning) KineticCoral else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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

                // Horizon sky and subway track background
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

                // Draw 3 perspective railway lanes
                val topSpread = w * 0.22f
                val bottomSpread = w * 0.86f

                for (laneIdx in -1..1) {
                    val topX = (w / 2f) + laneIdx * (topSpread / 2f)
                    val bottomX = (w / 2f) + laneIdx * (bottomSpread / 3f)

                    // Left & Right rails per lane
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(topX - 10f, horizonY),
                        end = Offset(bottomX - 38f, h),
                        strokeWidth = 4f
                    )
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(topX + 10f, horizonY),
                        end = Offset(bottomX + 38f, h),
                        strokeWidth = 4f
                    )

                    // Center glowing guide line
                    drawLine(
                        color = ElectricCyan.copy(alpha = 0.2f),
                        start = Offset(topX, horizonY),
                        end = Offset(bottomX, h),
                        strokeWidth = 2f
                    )
                }

                // Animated horizontal wooden/neon sleepers moving down
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

                // Draw approaching jump barrier in player's lane
                val barrierT = obstacleProgress
                val barrierY = horizonY + (h * 0.78f - horizonY) * (barrierT * barrierT)
                val barrierScale = 0.25f + 0.75f * (barrierT * barrierT)
                val barrierLaneX = (w / 2f) + animatedLaneOffset * ((bottomSpread / 3f) * barrierScale)
                val barrierWidth = 110f * barrierScale
                val barrierHeight = 36f * barrierScale

                drawRoundRect(
                    color = KineticCoral.copy(alpha = 0.85f),
                    topLeft = Offset(barrierLaneX - barrierWidth / 2f, barrierY - barrierHeight),
                    size = Size(barrierWidth, barrierHeight),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                // Draw Surfer Character at bottom
                val playerBaseY = h * 0.78f
                val playerLaneX = (w / 2f) + animatedLaneOffset * (bottomSpread / 3f)
                val jumpOffsetPx = jumpProgress.value * (h * 0.26f)
                val currentPlayerY = playerBaseY - jumpOffsetPx

                // Ground shadow shrinks while jumping
                val shadowRadius = 46f * (1f - jumpProgress.value * 0.5f)
                drawOval(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(playerLaneX - shadowRadius, playerBaseY + 12f),
                    size = Size(shadowRadius * 2f, 22f)
                )

                // Optional Hoverboard under character
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

                // Character body (squashes during Roll, stretches + glows during Jump)
                val bodyHeight = if (rollProgress.value > 0.2f) 56f else 96f
                val bodyWidth = if (rollProgress.value > 0.2f) 68f else 54f

                if (jumpProgress.value > 0.05f) {
                    // Neon jump aura ring
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

                // Character visor/head
                drawCircle(
                    color = Color.White,
                    radius = 18f,
                    center = Offset(playerLaneX, currentPlayerY - bodyHeight - 16f)
                )
            }

            // Status HUD pill overlay at top of track
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xDD090D16))
                    .border(1.dp, ElectricCyan, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionStatus,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (jumpProgress.value > 0.05f) EmeraldPulse else ElectricCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom Controls inside Sandbox for instant testing
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleAutoJump,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAutoJumpRunning) KineticCoral else ElectricCyan,
                    contentColor = if (isAutoJumpRunning) Color.White else Color(0xFF041318)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("sandbox_toggle_auto_jump_button")
            ) {
                Icon(
                    imageVector = if (isAutoJumpRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAutoJumpRunning) "STOP 3s AUTO-JUMP" else "START 3s AUTO-JUMP",
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = { onTriggerManualSwipe(SwipeControllerService.DIRECTION_UP) },
                modifier = Modifier
                    .height(52.dp)
                    .testTag("sandbox_manual_jump_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("JUMP NOW")
            }
        }
    }
}
