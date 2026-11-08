package com.example

import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.domain.model.AxisLockState
import com.example.feature.swipe.domain.model.RunnerLane
import com.example.service.SwipeControllerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local unit tests verifying:
 * 1. 3-Column Player Position State Machine (Starts in MIDDLE = 0, Left = -1, Right = +1):
 *    - Moving LEFT from MIDDLE goes to LEFT (-1) and blocks any further LEFT swipes, while allowing 2 RIGHT swipes.
 *    - Moving RIGHT from MIDDLE goes to RIGHT (+1) and blocks any further RIGHT swipes, while allowing 2 LEFT swipes.
 * 2. Display Reader Autopilot decision engine across the 3 columns.
 * 3. WAITING_FOR_NEUTRAL 100ms Neutral Box hold and Jump-Cancel support.
 */
class ExampleUnitTest {

    @Before
    fun setUp() {
        SwipeTelemetryRepository.clearLogs()
        SwipeTelemetryRepository.resetLaneToMiddle()
        SwipeTelemetryRepository.calibratePoseCenter(0.5f, 0.5f)
        SwipeTelemetryRepository.updatePoseThresholds(
            thresholdX = 0.10f,
            thresholdY = 0.10f,
            debounceCooldownMs = 250L,
            neutralHoldRequiredMs = 100L
        )
    }

    @Test
    fun threeColumnTracker_startsFromMiddle_blocksDuplicateBoundarySwipes_andAllowsTwoOppositeSwipes() {
        // 1. Starts in MIDDLE column (0)
        assertEquals(RunnerLane.MIDDLE, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        assertEquals(1, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsLeft)
        assertEquals(1, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsRight)

        // 2. Move LEFT from MIDDLE -> now in LEFT column (-1)
        SwipeControllerService.performSwipe("LEFT")
        assertEquals(RunnerLane.LEFT, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        assertEquals(0, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsLeft)
        assertEquals(2, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsRight)

        // 3. Try to go LEFT again while already in LEFT column -> MUST BE BLOCKED!
        val logCountBeforeBlockedLeft = SwipeTelemetryRepository.telemetryLogs.value.size
        val blockedLeftResult = SwipeControllerService.performSwipe("LEFT")
        assertFalse(blockedLeftResult)
        assertEquals(RunnerLane.LEFT, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        assertEquals(logCountBeforeBlockedLeft, SwipeTelemetryRepository.telemetryLogs.value.size)
        assertNotNull(SwipeTelemetryRepository.screenVisionState.value.blockedBoundaryMoveMessage)

        // 4. From LEFT (-1), player can go 2 RIGHT:
        // First RIGHT -> moves to MIDDLE (0)
        SwipeControllerService.performSwipe("RIGHT")
        assertEquals(RunnerLane.MIDDLE, SwipeTelemetryRepository.screenVisionState.value.currentLane)

        // Second RIGHT -> moves to RIGHT (+1)
        SwipeControllerService.performSwipe("RIGHT")
        assertEquals(RunnerLane.RIGHT, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        assertEquals(2, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsLeft)
        assertEquals(0, SwipeTelemetryRepository.screenVisionState.value.currentLane.remainingStepsRight)

        // 5. Try to go RIGHT again while already in RIGHT column (+1) -> MUST BE BLOCKED!
        val logCountBeforeBlockedRight = SwipeTelemetryRepository.telemetryLogs.value.size
        val blockedRightResult = SwipeControllerService.performSwipe("RIGHT")
        assertFalse(blockedRightResult)
        assertEquals(RunnerLane.RIGHT, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        assertEquals(logCountBeforeBlockedRight, SwipeTelemetryRepository.telemetryLogs.value.size)
        assertNotNull(SwipeTelemetryRepository.screenVisionState.value.blockedBoundaryMoveMessage)

        // 6. From RIGHT (+1), player can go 2 LEFT back to LEFT (-1):
        SwipeControllerService.performSwipe("LEFT")
        assertEquals(RunnerLane.MIDDLE, SwipeTelemetryRepository.screenVisionState.value.currentLane)
        SwipeControllerService.performSwipe("LEFT")
        assertEquals(RunnerLane.LEFT, SwipeTelemetryRepository.screenVisionState.value.currentLane)
    }

    @Test
    fun displayReaderAutopilot_readsThreeColumnHazards_andSteersToSafeColumnWithoutOutOfBounds() {
        // Start in MIDDLE (0): Obstacle detected in MIDDLE (0.85f), LEFT is clear (0.10f) -> recommends "LEFT"
        val decisionFromMiddle = SwipeTelemetryRepository.updateScreenVisionReadingAndDecideAction(
            leftHazard = 0.10f,
            middleHazard = 0.85f,
            rightHazard = 0.50f,
            obstacleType = "TRAIN"
        )
        assertEquals("LEFT", decisionFromMiddle)
        SwipeControllerService.performSwipe(decisionFromMiddle!!)
        assertEquals(RunnerLane.LEFT, SwipeTelemetryRepository.screenVisionState.value.currentLane)

        // Now player is in LEFT (-1): Obstacle detected in LEFT (0.90f), MIDDLE is clear (0.15f)
        // Autopilot NEVER recommends "LEFT" when in LEFT column; it recommends "RIGHT" back to MIDDLE!
        val decisionFromLeft = SwipeTelemetryRepository.updateScreenVisionReadingAndDecideAction(
            leftHazard = 0.90f,
            middleHazard = 0.15f,
            rightHazard = 0.20f,
            obstacleType = "TRAIN"
        )
        assertEquals("RIGHT", decisionFromLeft)
        SwipeControllerService.performSwipe(decisionFromLeft!!)
        assertEquals(RunnerLane.MIDDLE, SwipeTelemetryRepository.screenVisionState.value.currentLane)
    }

    @Test
    fun waitingForNeutral_locksOutHorizontalUntil100msInNeutralBox_andPreventsOppositeReturnSwipe() {
        val t0 = 20_000L

        // 1. Lean LEFT from MIDDLE at t0 -> triggers "LEFT"
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

        // 2. Swing straight across to RIGHT without holding inside Neutral Box for 100ms -> blocked
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

        // 3. Return inside Neutral Box for 110ms (t0 + 350ms to t0 + 460ms) -> unlocks to READY
        SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = 0.50f,
            noseY = 0.50f,
            leftShoulderX = 0.45f,
            leftShoulderY = 0.50f,
            rightShoulderX = 0.55f,
            rightShoulderY = 0.50f,
            nowMs = t0 + 350L
        )
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

        // 4. Now leaning RIGHT fires "RIGHT" cleanly
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
