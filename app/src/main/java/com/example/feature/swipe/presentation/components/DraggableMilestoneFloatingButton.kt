package com.example.feature.swipe.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.SwipeControllerService
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral
import kotlin.math.roundToInt

/**
 * Small, translucent, draggable control panel that sits on top of Subway Surfers
 * (or the in-app Subway Runner Sandbox) so the user can Start/Stop tracking, view
 * the mini camera preview, and see the detected movement (JUMP, ROLL, LEFT, RIGHT)
 * in real time without blocking touches to the underlying game.
 */
@Composable
fun DraggableMilestoneFloatingButton(
    visible: Boolean,
    isAutoJumpRunning: Boolean,
    isCameraPreviewEnabled: Boolean,
    lastActionLabel: String,
    countdownMs: Long,
    jumpCount: Int,
    onToggleAutoJump: () -> Unit,
    onToggleCameraPreview: () -> Unit,
    onInstantSwipe: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val actionColor = when (lastActionLabel) {
        "JUMP" -> EmeraldPulse
        "ROLL" -> AmberWarning
        "LEFT", "RIGHT" -> ElectricCyan
        "HOVERBOARD" -> KineticCoral
        else -> ElectricCyan
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .testTag("floating_milestone_button")
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            // Translucent obsidian panel (#CC090D16) so the underlying game stays visible
            color = Color(0xCC090D16),
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(18.dp))
                .border(
                    width = 1.5.dp,
                    color = if (isAutoJumpRunning) KineticCoral.copy(alpha = 0.85f) else ElectricCyan.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Row: Drag Handle + Camera Toggle + Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAutoJumpRunning) "⠿ ● TRACKING ON" else "⠿ SWIPE HUD",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAutoJumpRunning) KineticCoral else ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onToggleCameraPreview,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0x881E293B))
                                .testTag("floating_toggle_camera_button")
                        ) {
                            Icon(
                                imageVector = if (isCameraPreviewEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle mini camera preview",
                                tint = if (isCameraPreviewEnabled) ElectricCyan else Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0x881E293B))
                                .testTag("dismiss_floating_milestone_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hide floating HUD panel",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Middle Row: Mini Camera Preview + Real-Time Action Label (JUMP, ROLL, LEFT, RIGHT)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCameraPreviewEnabled) {
                        MiniCameraPreviewBox(
                            lastActionLabel = lastActionLabel,
                            modifier = Modifier
                                .size(width = 62.dp, height = 76.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "LAST TRIGGERED",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = lastActionLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = JetBrainsMonoFamily,
                            color = actionColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("floating_detected_action_label")
                        )
                        Text(
                            text = if (isAutoJumpRunning) {
                                String.format("Next UP: %.1fs (#%d)", countdownMs / 1000f, jumpCount)
                            } else {
                                "Ready • 3.0s Auto-Jump"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = Color(0xFFCBD5E1)
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Button(
                            onClick = onToggleAutoJump,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAutoJumpRunning) KineticCoral else ElectricCyan,
                                contentColor = if (isAutoJumpRunning) Color.White else Color(0xFF041318)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("floating_auto_jump_toggle")
                        ) {
                            Icon(
                                imageVector = if (isAutoJumpRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isAutoJumpRunning) "STOP" else "START",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // Compact Quick Action Chips (JUMP, ROLL, LEFT, RIGHT)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "JUMP" to SwipeControllerService.DIRECTION_UP,
                        "ROLL" to SwipeControllerService.DIRECTION_DOWN,
                        "LEFT" to SwipeControllerService.DIRECTION_LEFT,
                        "RIGHT" to SwipeControllerService.DIRECTION_RIGHT
                    ).forEach { (label, dir) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x881E293B))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .clickable { onInstantSwipe(dir) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("floating_chip_${label.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
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
private fun MiniCameraPreviewBox(
    lastActionLabel: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasCamPerm = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    val poseController = remember(context) {
        com.example.feature.swipe.data.tracking.PoseLandmarkerController(context.applicationContext) { dir ->
            SwipeControllerService.performSwipeWithSource(dir, "MediaPipe Pose LIVE_STREAM")
        }
    }

    var cameraBoundSuccessfully by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.background(Color(0xDD0B1320)),
        contentAlignment = Alignment.Center
    ) {
        if (hasCamPerm) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { previewView ->
                    poseController.bindCameraPoseStream(lifecycleOwner, previewView)
                    cameraBoundSuccessfully = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Reticle overlay on top of mini camera feed (or sensor reticle when camera is not granted)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(
                color = ElectricCyan.copy(alpha = 0.45f),
                radius = size.minDimension * 0.32f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
            val vectorOffset = when (lastActionLabel) {
                "JUMP" -> Offset(cx, cy - 16f)
                "ROLL" -> Offset(cx, cy + 16f)
                "LEFT" -> Offset(cx - 16f, cy)
                "RIGHT" -> Offset(cx + 16f, cy)
                else -> Offset(cx, cy)
            }
            drawLine(
                color = KineticCoral,
                start = Offset(cx, cy),
                end = vectorOffset,
                strokeWidth = 4f
            )
            drawCircle(
                color = ElectricCyan,
                radius = 4f,
                center = vectorOffset
            )
        }

        if (!hasCamPerm && !cameraBoundSuccessfully) {
            Text(
                text = "CAM\nHUD",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = ElectricCyan.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            poseController.release()
            if (hasCamPerm) {
                runCatching {
                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                }
            }
        }
    }
}
