package com.example

import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local unit tests verifying SwipeControllerService.performSwipe(direction)
 * for UP, DOWN, LEFT, RIGHT, and DOUBLE_TAP gestures and real-time action label mapping
 * (JUMP, ROLL, LEFT, RIGHT, HOVERBOARD) displayed by OverlayService.
 */
class ExampleUnitTest {

    @Before
    fun setUp() {
        SwipeTelemetryRepository.clearLogs()
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

        // Verify UP swipe moves upward on screen (endY < startY)
        val upEvent = logs.first { it.direction == "UP" }
        assertTrue(upEvent.endY < upEvent.startY)

        // Verify DOWN swipe moves downward on screen (endY > startY)
        val downEvent = logs.first { it.direction == "DOWN" }
        assertTrue(downEvent.endY > downEvent.startY)
    }
}
