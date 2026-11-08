package com.example.feature.swipe.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.feature.swipe.domain.model.GestureStrokePoint
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KineticCoral
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GestureTrajectoryCanvas(
    latestStroke: GestureStrokePoint?,
    latestDirectionPair: Pair<String, Long>?,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(1f) }

    LaunchedEffect(latestDirectionPair) {
        if (latestDirectionPair != null) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (latestStroke?.durationMs ?: 220L).toInt().coerceIn(150, 500),
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF060A10))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .testTag("gesture_trajectory_canvas")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GESTURE VECTOR VIEWPORT",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
            val badgeText = if (latestStroke != null && latestDirectionPair != null) {
                "${latestDirectionPair.first} • (${latestStroke.startX.toInt()},${latestStroke.startY.toInt()}) → (${latestStroke.endX.toInt()},${latestStroke.endY.toInt()})"
            } else {
                "1080×2400 VIRTUAL COORDS"
            }
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF102030), Color(0xFF070B12))
                    )
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridColor = Color(0xFF1E293B)

                // Draw tactical grid lines
                val cols = 6
                val rows = 4
                for (i in 1 until cols) {
                    val x = (w / cols) * i
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f
                    )
                }
                for (j in 1 until rows) {
                    val y = (h / rows) * j
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                // Draw crosshair center
                val cx = w / 2f
                val cy = h / 2f
                drawCircle(
                    color = ElectricCyan.copy(alpha = 0.2f),
                    radius = 22f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )

                val direction = latestDirectionPair?.first
                if (latestStroke != null && direction != null) {
                    val animVal = progress.value
                    if (latestStroke.isDoubleTap) {
                        val pulseRadius1 = 16f + (36f * animVal)
                        val pulseRadius2 = 8f + (24f * animVal)
                        drawCircle(
                            color = KineticCoral.copy(alpha = 1f - animVal * 0.6f),
                            radius = pulseRadius1,
                            center = Offset(cx, cy),
                            style = Stroke(width = 4f)
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = pulseRadius2,
                            center = Offset(cx, cy),
                            style = Stroke(width = 4f)
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = 7f,
                            center = Offset(cx, cy)
                        )
                    } else {
                        val spanX = w * 0.32f
                        val spanY = h * 0.62f
                        val (startOffset, endOffset) = when (direction) {
                            "UP" -> Offset(cx, cy + spanY / 2f) to Offset(cx, cy - spanY / 2f)
                            "DOWN" -> Offset(cx, cy - spanY / 2f) to Offset(cx, cy + spanY / 2f)
                            "LEFT" -> Offset(cx + spanX / 2f, cy) to Offset(cx - spanX / 2f, cy)
                            "RIGHT" -> Offset(cx - spanX / 2f, cy) to Offset(cx + spanX / 2f, cy)
                            else -> Offset(cx, cy) to Offset(cx, cy)
                        }

                        val currentEnd = Offset(
                            x = startOffset.x + (endOffset.x - startOffset.x) * animVal,
                            y = startOffset.y + (endOffset.y - startOffset.y) * animVal
                        )

                        // Start touch anchor ring
                        drawCircle(
                            color = KineticCoral,
                            radius = 8f,
                            center = startOffset
                        )

                        // Glowing stroke path
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(KineticCoral, ElectricCyan),
                                start = startOffset,
                                end = currentEnd
                            ),
                            start = startOffset,
                            end = currentEnd,
                            strokeWidth = 8f,
                            cap = StrokeCap.Round
                        )

                        // Arrowhead at currentEnd
                        val angle = atan2(
                            (currentEnd.y - startOffset.y).toDouble(),
                            (currentEnd.x - startOffset.x).toDouble()
                        )
                        val arrowLength = 20f
                        val arrowAngle = Math.toRadians(28.0)

                        val x1 = currentEnd.x - (arrowLength * cos(angle - arrowAngle)).toFloat()
                        val y1 = currentEnd.y - (arrowLength * sin(angle - arrowAngle)).toFloat()
                        val x2 = currentEnd.x - (arrowLength * cos(angle + arrowAngle)).toFloat()
                        val y2 = currentEnd.y - (arrowLength * sin(angle + arrowAngle)).toFloat()

                        val arrowPath = Path().apply {
                            moveTo(currentEnd.x, currentEnd.y)
                            lineTo(x1, y1)
                            lineTo(x2, y2)
                            close()
                        }
                        drawPath(path = arrowPath, color = ElectricCyan)
                    }
                }
            }
        }
    }
}
