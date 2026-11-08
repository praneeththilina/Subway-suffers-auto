package com.example.feature.swipe.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.feature.swipe.domain.model.AxisLockState
import com.example.feature.swipe.domain.model.MovementInputMode
import com.example.feature.swipe.domain.model.PoseCalibrationState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral
import java.util.Locale

@Composable
fun MovementInputTrackingCard(
    movementInputMode: MovementInputMode,
    isTrackingActive: Boolean,
    poseState: PoseCalibrationState,
    lastActionLabel: String,
    onSelectMode: (MovementInputMode) -> Unit,
    onToggleTracking: () -> Unit,
    onRecalibrateCenter: () -> Unit,
    onSimulatePoseDrag: (Float, Float) -> Unit,
    onThresholdChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHorizLocked = poseState.horizontalState == AxisLockState.WAITING_FOR_NEUTRAL
    val isVertLocked = poseState.verticalState == AxisLockState.WAITING_FOR_NEUTRAL

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isTrackingActive) EmeraldPulse else ElectricCyan.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp)
            )
            .testTag("movement_input_tracking_card")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Movement Input → Swipe Engine",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = poseState.modelStatus,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = if (isTrackingActive) EmeraldPulse else ElectricCyan
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "100ms NEUTRAL • 250ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = KineticCoral,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // State Machine Status Pills: Horizontal (X) State, Vertical (Y) State, and Jump-Cancel Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AxisStatePill(
                    axisTitle = "HORIZ (L/R)",
                    state = poseState.horizontalState,
                    neutralProgressMs = poseState.horizontalNeutralProgressMs,
                    neutralRequiredMs = poseState.neutralHoldRequiredMs,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("horizontal_axis_state_pill")
                )

                AxisStatePill(
                    axisTitle = "VERT (JUMP/ROLL)",
                    state = poseState.verticalState,
                    neutralProgressMs = poseState.verticalNeutralProgressMs,
                    neutralRequiredMs = poseState.neutralHoldRequiredMs,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vertical_axis_state_pill")
                )
            }

            if (poseState.jumpCancelActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EmeraldPulse.copy(alpha = 0.16f))
                        .border(1.dp, EmeraldPulse, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("jump_cancel_active_badge")
                ) {
                    Text(
                        text = "⚡ JUMP-CANCEL TRIGGERED: Vertical action fired immediately while Horizontal was locked in WAITING_FOR_NEUTRAL!",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = EmeraldPulse,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Option A (Camera Pose MediaPipe) vs Option B (Device Motion Sensors) Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = movementInputMode == MovementInputMode.CAMERA_POSE,
                    onClick = { onSelectMode(MovementInputMode.CAMERA_POSE) },
                    label = {
                        Text(
                            text = "Option A: Camera Pose",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                        selectedLabelColor = ElectricCyan,
                        selectedLeadingIconColor = ElectricCyan
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mode_camera_pose_chip")
                )

                FilterChip(
                    selected = movementInputMode == MovementInputMode.DEVICE_MOTION,
                    onClick = { onSelectMode(MovementInputMode.DEVICE_MOTION) },
                    label = {
                        Text(
                            text = "Option B: Device Motion",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = KineticCoral.copy(alpha = 0.2f),
                        selectedLabelColor = KineticCoral,
                        selectedLeadingIconColor = KineticCoral
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mode_device_motion_chip")
                )
            }

            // Interactive Pose & Nose/Shoulder Calibration Canvas
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF060A10))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NEUTRAL BOX RADAR (100ms HOLD TO UNLOCK)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "ΔX:%+.2f ΔY:%+.2f • %s",
                            poseState.deltaX,
                            poseState.deltaY,
                            lastActionLabel
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = EmeraldPulse
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF0F2236), Color(0xFF060911))
                            )
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val normX = (change.position.x / size.width.toFloat()).coerceIn(0.05f, 0.95f)
                                val normY = (change.position.y / size.height.toFloat()).coerceIn(0.05f, 0.95f)
                                onSimulatePoseDrag(normX, normY)
                            }
                        }
                        .testTag("pose_calibration_radar_canvas")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        val centerPxX = poseState.centerX * w
                        val centerPxY = poseState.centerY * h
                        val threshPxX = poseState.thresholdX * w
                        val threshPxY = poseState.thresholdY * h

                        val neutralBoxColor = when {
                            isHorizLocked || isVertLocked -> AmberWarning
                            else -> ElectricCyan
                        }

                        // Fill Neutral Box subtly
                        drawRect(
                            color = neutralBoxColor.copy(alpha = 0.08f),
                            topLeft = Offset(centerPxX - threshPxX, centerPxY - threshPxY),
                            size = Size(threshPxX * 2f, threshPxY * 2f)
                        )

                        // Draw Calibrated Neutral Box Border
                        drawRect(
                            color = neutralBoxColor.copy(alpha = 0.7f),
                            topLeft = Offset(centerPxX - threshPxX, centerPxY - threshPxY),
                            size = Size(threshPxX * 2f, threshPxY * 2f),
                            style = Stroke(
                                width = 2.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            )
                        )

                        // Draw UP / DOWN / LEFT / RIGHT Threshold Zone Divider Lines
                        drawLine(
                            color = Color(0xFF1E293B),
                            start = Offset(centerPxX, 0f),
                            end = Offset(centerPxX, h),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = Color(0xFF1E293B),
                            start = Offset(0f, centerPxY),
                            end = Offset(w, centerPxY),
                            strokeWidth = 1.5f
                        )

                        // Calibrated Center Reticle
                        drawCircle(
                            color = neutralBoxColor.copy(alpha = 0.8f),
                            radius = 8f,
                            center = Offset(centerPxX, centerPxY),
                            style = Stroke(width = 2f)
                        )

                        // Draw Left & Right Shoulders + Clavicle Line
                        val lsX = poseState.leftShoulderX * w
                        val lsY = poseState.leftShoulderY * h
                        val rsX = poseState.rightShoulderX * w
                        val rsY = poseState.rightShoulderY * h
                        val nosePxX = poseState.noseX * w
                        val nosePxY = poseState.noseY * h
                        val trackedPxX = poseState.currentX * w
                        val trackedPxY = poseState.currentY * h

                        drawLine(
                            color = EmeraldPulse,
                            start = Offset(lsX, lsY),
                            end = Offset(rsX, rsY),
                            strokeWidth = 5f
                        )
                        drawLine(
                            color = EmeraldPulse.copy(alpha = 0.7f),
                            start = Offset((lsX + rsX) / 2f, (lsY + rsY) / 2f),
                            end = Offset(nosePxX, nosePxY),
                            strokeWidth = 4f
                        )

                        drawCircle(
                            color = EmeraldPulse,
                            radius = 9f,
                            center = Offset(lsX, lsY)
                        )
                        drawCircle(
                            color = EmeraldPulse,
                            radius = 9f,
                            center = Offset(rsX, rsY)
                        )

                        drawCircle(
                            color = AmberWarning,
                            radius = 10f,
                            center = Offset(nosePxX, nosePxY)
                        )

                        drawLine(
                            color = KineticCoral,
                            start = Offset(centerPxX, centerPxY),
                            end = Offset(trackedPxX, trackedPxY),
                            strokeWidth = 4f
                        )
                        drawCircle(
                            color = KineticCoral,
                            radius = 7f,
                            center = Offset(trackedPxX, trackedPxY)
                        )
                    }

                    Text(
                        text = if (isVertLocked) "▲ JUMP (WAIT NEUTRAL)" else "▲ JUMP-CANCEL READY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isVertLocked) AmberWarning else EmeraldPulse,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                    )
                    Text(
                        text = if (isVertLocked) "▼ ROLL (WAIT NEUTRAL)" else "▼ ROLL READY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isVertLocked) AmberWarning else AmberWarning.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                    )
                    Text(
                        text = if (isHorizLocked) "🔒 WAIT NEUTRAL" else "◀ LEFT",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isHorizLocked) AmberWarning else ElectricCyan,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 6.dp)
                    )
                    Text(
                        text = if (isHorizLocked) "WAIT NEUTRAL 🔒" else "RIGHT ▶",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isHorizLocked) AmberWarning else ElectricCyan,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                    )
                }
            }

            // Sensitivity Threshold Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Neutral Box Size (X/Y Threshold)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "±%.0f%% Center Box", poseState.thresholdY * 100f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = ElectricCyan
                    )
                }
                Slider(
                    value = poseState.thresholdY,
                    onValueChange = { newThresh ->
                        onThresholdChange(newThresh, newThresh)
                    },
                    valueRange = 0.05f..0.25f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("pose_threshold_slider")
                )
            }

            // Action Buttons: Start/Stop Live Movement Tracking + Calibrate Center
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleTracking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTrackingActive) KineticCoral else EmeraldPulse,
                        contentColor = if (isTrackingActive) Color.White else Color(0xFF022C22)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("toggle_movement_tracking_button")
                ) {
                    Icon(
                        imageVector = if (isTrackingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isTrackingActive) "Stop Pose/Motion" else "Start Pose/Motion",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRecalibrateCenter,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("calibrate_pose_center_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Calibrate Center",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun AxisStatePill(
    axisTitle: String,
    state: AxisLockState,
    neutralProgressMs: Long,
    neutralRequiredMs: Long,
    modifier: Modifier = Modifier
) {
    val isLocked = state == AxisLockState.WAITING_FOR_NEUTRAL
    val borderColor = if (isLocked) AmberWarning else EmeraldPulse

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1422))
            .border(1.dp, borderColor.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = axisTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (isLocked) {
                "WAITING_FOR_NEUTRAL (${neutralProgressMs}/${neutralRequiredMs}ms)"
            } else {
                "READY"
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            color = borderColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
