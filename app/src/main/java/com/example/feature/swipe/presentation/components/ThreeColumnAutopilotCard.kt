package com.example.feature.swipe.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.feature.swipe.domain.model.RunnerLane
import com.example.feature.swipe.domain.model.ScreenVisionAutopilotState
import com.example.service.SwipeControllerService
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral

/**
 * Displays the 3-Column Player Position Tracker (LEFT = -1, MIDDLE = 0, RIGHT = +1)
 * and Live Display Reader Autopilot that reads the game screen and automates obstacle avoidance.
 */
@Composable
fun ThreeColumnAutopilotCard(
    visionState: ScreenVisionAutopilotState,
    onToggleAutopilot: () -> Unit,
    onResetToMiddle: () -> Unit,
    onStepColumn: (String) -> Unit,
    onSimulateObstacleScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLane = visionState.currentLane

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (visionState.isAutopilotEnabled) EmeraldPulse else ElectricCyan.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp)
            )
            .testTag("three_column_autopilot_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
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
                                if (visionState.isAutopilotEnabled) EmeraldPulse.copy(alpha = 0.2f)
                                else ElectricCyan.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (visionState.isAutopilotEnabled) EmeraldPulse else ElectricCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "3-Column Tracker & Display Autopilot",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Starts in MIDDLE (0) • Blocks redundant boundary swipes",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = ElectricCyan
                        )
                    }
                }
            }

            // 3-Column Visual Track Map (LEFT -1 | MIDDLE 0 | RIGHT +1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF070C15))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ColumnTrackSlot(
                    lane = RunnerLane.LEFT,
                    isCurrentPlayerLane = currentLane == RunnerLane.LEFT,
                    hazardLevel = visionState.leftLaneHazard,
                    allowedHint = if (currentLane == RunnerLane.LEFT) "AT LEFT (No Left, 2 Right)" else "Column -1",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("column_slot_left")
                )

                ColumnTrackSlot(
                    lane = RunnerLane.MIDDLE,
                    isCurrentPlayerLane = currentLane == RunnerLane.MIDDLE,
                    hazardLevel = visionState.middleLaneHazard,
                    allowedHint = if (currentLane == RunnerLane.MIDDLE) "START (1 Left, 1 Right)" else "Column 0",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("column_slot_middle")
                )

                ColumnTrackSlot(
                    lane = RunnerLane.RIGHT,
                    isCurrentPlayerLane = currentLane == RunnerLane.RIGHT,
                    hazardLevel = visionState.rightLaneHazard,
                    allowedHint = if (currentLane == RunnerLane.RIGHT) "AT RIGHT (No Right, 2 Left)" else "Column +1",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("column_slot_right")
                )
            }

            // Allowed Moves Status Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D1626))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (currentLane.canMoveLeft) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Default.Block,
                        contentDescription = null,
                        tint = if (currentLane.canMoveLeft) ElectricCyan else KineticCoral,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (currentLane.canMoveLeft) {
                            "Can go ${currentLane.remainingStepsLeft} LEFT"
                        } else {
                            "LEFT LOCKED (At Left)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = if (currentLane.canMoveLeft) ElectricCyan else KineticCoral
                    )
                }

                Text(
                    text = "PLAYER: ${currentLane.shortCode}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = EmeraldPulse,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (currentLane.canMoveRight) {
                            "Can go ${currentLane.remainingStepsRight} RIGHT"
                        } else {
                            "RIGHT LOCKED (At Right)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = if (currentLane.canMoveRight) ElectricCyan else KineticCoral
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (currentLane.canMoveRight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.Block,
                        contentDescription = null,
                        tint = if (currentLane.canMoveRight) ElectricCyan else KineticCoral,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Boundary Block Alert or Last Autopilot Decision
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (visionState.blockedBoundaryMoveMessage != null) KineticCoral.copy(alpha = 0.16f)
                        else Color(0xFF0A1422)
                    )
                    .border(
                        1.dp,
                        if (visionState.blockedBoundaryMoveMessage != null) KineticCoral else ElectricCyan.copy(alpha = 0.35f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("autopilot_decision_banner")
            ) {
                Text(
                    text = visionState.blockedBoundaryMoveMessage ?: visionState.lastAutopilotDecision,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = JetBrainsMonoFamily,
                    color = if (visionState.blockedBoundaryMoveMessage != null) KineticCoral else ElectricCyan
                )
            }

            // Step Left / Reset Middle / Step Right interactive controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onStepColumn(SwipeControllerService.DIRECTION_LEFT) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (currentLane.canMoveLeft) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF2A1215),
                        contentColor = if (currentLane.canMoveLeft) ElectricCyan else KineticCoral
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("step_left_column_button")
                ) {
                    Icon(
                        imageVector = if (currentLane.canMoveLeft) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (currentLane.canMoveLeft) "Go Left (${currentLane.remainingStepsLeft})" else "Left Blocked",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onResetToMiddle,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("reset_middle_column_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset to Middle Column",
                        tint = EmeraldPulse,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Middle (0)",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldPulse
                    )
                }

                FilledTonalButton(
                    onClick = { onStepColumn(SwipeControllerService.DIRECTION_RIGHT) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (currentLane.canMoveRight) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF2A1215),
                        contentColor = if (currentLane.canMoveRight) ElectricCyan else KineticCoral
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("step_right_column_button")
                ) {
                    Text(
                        text = if (currentLane.canMoveRight) "Go Right (${currentLane.remainingStepsRight})" else "Right Blocked",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (currentLane.canMoveRight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Primary Display Reader Autopilot Toggle + Test Obstacle Scan Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleAutopilot,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (visionState.isAutopilotEnabled) KineticCoral else EmeraldPulse,
                        contentColor = if (visionState.isAutopilotEnabled) Color.White else Color(0xFF022C22)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("toggle_display_autopilot_button")
                ) {
                    Icon(
                        imageVector = if (visionState.isAutopilotEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (visionState.isAutopilotEnabled) {
                            "Stop Display Autopilot"
                        } else {
                            "Start Display Autopilot"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onSimulateObstacleScan,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("simulate_obstacle_evade_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Test Auto-Evade",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnTrackSlot(
    lane: RunnerLane,
    isCurrentPlayerLane: Boolean,
    hazardLevel: Float,
    allowedHint: String,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isCurrentPlayerLane -> EmeraldPulse
        hazardLevel >= 0.62f -> KineticCoral
        else -> Color(0xFF1E293B)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrentPlayerLane) EmeraldPulse.copy(alpha = 0.14f)
                else Color(0xFF0F172A)
            )
            .border(
                width = if (isCurrentPlayerLane) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = lane.shortCode,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = JetBrainsMonoFamily,
            color = if (isCurrentPlayerLane) EmeraldPulse else ElectricCyan,
            fontWeight = FontWeight.Bold
        )

        // Player Avatar Indicator inside the active column
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrentPlayerLane) EmeraldPulse else Color(0xFF1E293B)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isCurrentPlayerLane) "🏃" else "•",
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrentPlayerLane) Color(0xFF022C22) else Color(0xFF64748B)
            )
        }

        // Obstacle Hazard Bar read from Display
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Hazard ${(hazardLevel * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = if (hazardLevel >= 0.62f) KineticCoral else Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { hazardLevel.coerceIn(0f, 1f) },
                color = if (hazardLevel >= 0.62f) KineticCoral else AmberWarning,
                trackColor = Color(0xFF1E293B),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }

        Text(
            text = allowedHint,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrentPlayerLane) EmeraldPulse else Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
