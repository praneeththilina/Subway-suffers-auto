package com.example

import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.domain.model.AxisLockState
import com.example.service.SwipeControllerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local unit tests verifying:
 * 1. SwipeControllerService.performSwipe(direction) for UP, DOWN, LEFT, RIGHT, and DOUBLE_TAP.
 * 2. Real-time action label mapping (JUMP, ROLL, LEFT, RIGHT, HOVERBOARD) displayed by OverlayService.
 * 3. WAITING_FOR_NEUTRAL state machine: locks out horizontal swipes until the body returns inside
 *    the center "Neutral Box" for at least 100ms, preventing accidental opposite-direction swipes.
 * 4. Jump-Cancel Support: vertical triggers (UP/DOWN) fire immediately even while horizontal
 *    movement is locked in WAITING_FOR_NEUTRAL or horizontal cooldown.
 */
class ExampleUnitTest {

    @Before
    fun setUp() {
        SwipeTelemetryRepository.clearLogs()
        SwipeTelemetryRepository.calibratePoseCenter(0.5f, 0.5f)
        SwipeTelemetryRepository.updatePoseThresholds(
            thresholdX = 0.10f,
            thresholdY = 0.10f,
            debounceCooldownMs = 250L,
            neutralHoldRequiredMs = 100L
        )
    }

    @Test
    fun performSwipe_recordsAllFiveRequiredDirections_andUpdatesActionLabel() {
        SwipeControllerService.performSwipe("UP")
        assertEquals("JUMP", SwipeTelemetryRepository.lastActionLabel.value)

        SwipeControllerService.performSwipe("DOWN")
        assertEquals("ROLL", SwipeTelemetryRepository.lastActionLabel.value)

        SwipeControllerService.performSwipe("LEFT")
        assertEquals("LEFT", SwipeTelemetryRepository.lastActionLabel.value)

        SwipeControllerService.performSwipe("RIGHT")
        assertEquals("RIGHT", SwipeTelemetryRepository.lastActionLabel.value)

        SwipeControllerService.performSwipe("DOUBLE_TAP")
        assertEquals("HOVERBOARD", SwipeTelemetryRepository.lastActionLabel.value)

        val logs = SwipeTelemetryRepository.telemetryLogs.value
        assertEquals(5, logs.size)

        val upEvent = logs.first { it.direction == "UP" }
        assertTrue(upEvent.endY < upEvent.startY)

        val downEvent = logs.first { it.direction == "DOWN" }
        assertTrue(downEvent.endY > downEvent.startY)
    }

    @Test
    fun waitingForNeutral_locksOutHorizontalUntil100msInNeutralBox_andPreventsOppositeReturnSwipe() {
        val t0 = 20_000L

        // 1. Lean LEFT at t0 -> triggers "LEFT" and enters WAITING_FOR_NEUTRAL on horizontal axis
        val dirLeft = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.28f,
            noseY = 0.50f,
            leftShoulderX = 0.18f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.38f,
            rightShoulderY = 0.50f,
            nowMs = t0
        )
        assertEquals("LEFT", dirLeft)
        assertEquals(
            AxisLockState.WAITING_FOR_NEUTRAL,
            SwipeTelemetryRepository.poseCalibrationState.value.horizontalState
        )

        // 2. Swing straight across to RIGHT without holding inside Neutral Box for 100ms
        // Even 300ms later (past 250ms debounce), horizontal MUST remain locked out!
        val accidentalOppositeSwing = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.72f,
            noseY = 0.50f,
            leftShoulderX = 0.62f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.82f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 300L
        )
        assertNull(accidentalOppositeSwing)
        assertEquals(
            AxisLockState.WAITING_FOR_NEUTRAL,
            SwipeTelemetryRepository.poseCalibrationState.value.horizontalState
        )

        // 3. Return inside the center Neutral Box at t0 + 350ms (0ms elapsed in neutral)
        SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.50f,
            noseY = 0.50f,
            leftShoulderX = 0.45f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.55f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 350L
        )
        // After only 60ms in Neutral Box (< 100ms required), still WAITING_FOR_NEUTRAL
        SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.50f,
            noseY = 0.50f,
            leftShoulderX = 0.45f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.55f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 410L
        )
        assertEquals(
            AxisLockState.WAITING_FOR_NEUTRAL,
            SwipeTelemetryRepository.poseCalibrationState.value.horizontalState
        )

        // 4. Hold inside Neutral Box for 110ms total (t0 + 460ms >= 100ms) -> unlocks to READY!
        SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.50f,
            noseY = 0.50f,
            leftShoulderX = 0.45f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.55f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 460L
        )
        assertEquals(
            AxisLockState.READY,
            SwipeTelemetryRepository.poseCalibrationState.value.horizontalState
        )

        // 5. Now leaning RIGHT at t0 + 500ms fires "RIGHT" cleanly
        val intentionalRight = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.72f,
            noseY = 0.50f,
            leftShoulderX = 0.62f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.82f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 500L
        )
        assertEquals("RIGHT", intentionalRight)
    }

    @Test
    fun jumpCancelSupport_allowsImmediateVerticalJumpOrRoll_whileHorizontalLockedInCooldown() {
        val t0 = 50_000L

        // 1. Player leans LEFT at t0 -> triggers "LEFT", horizontal is now WAITING_FOR_NEUTRAL
        val dirLeft = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.25f,
            noseY = 0.50f,
            leftShoulderX = 0.15f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.35f,
            rightShoulderY = 0.50f,
            nowMs = t0
        )
        assertEquals("LEFT", dirLeft)
        assertEquals(
            AxisLockState.WAITING_FOR_NEUTRAL,
            SwipeTelemetryRepository.poseCalibrationState.value.horizontalState
        )

        // 2. Only 40ms later (while still leaning left AND inside horizontal 250ms cooldown),
        // player jumps upward! Jump-Cancel must fire "UP" immediately!
        val jumpMidLaneChange = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.25f,
            noseY = 0.26f,
            leftShoulderX = 0.15f,
            leftShoulderY = 0.32f,
            rightShoulderX = 0.35f,
            rightShoulderY = 0.32f,
            nowMs = t0 + 40L
        )
        assertEquals("UP", jumpMidLaneChange)
        assertTrue(SwipeTelemetryRepository.poseCalibrationState.value.jumpCancelActive)
    }
}
