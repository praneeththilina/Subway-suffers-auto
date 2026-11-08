package com.example

import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local unit tests verifying SwipeControllerService.performSwipe(direction)
 * for UP, DOWN, LEFT, RIGHT, and DOUBLE_TAP gestures.
 */
class ExampleUnitTest {

    @Before
    fun setUp() {
        SwipeTelemetryRepository.clearLogs()
    }

    @Test
    fun performSwipe_recordsAllFiveRequiredDirections() {
        val directions = listOf("UP", "DOWN", "LEFT", "RIGHT", "DOUBLE_TAP")
        directions.forEach { dir ->
            SwipeControllerService.performSwipe(dir)
        }

        val logs = SwipeTelemetryRepository.telemetryLogs.value
        assertEquals(5, logs.size)
        assertEquals("DOUBLE_TAP", logs[0].direction)
        assertEquals("RIGHT", logs[1].direction)
        assertEquals("LEFT", logs[2].direction)
        assertEquals("DOWN", logs[3].direction)
        assertEquals("UP", logs[4].direction)

        // Verify UP swipe moves upward on screen (endY < startY)
        val upEvent = logs.first { it.direction == "UP" }
        assertTrue(upEvent.endY < upEvent.startY)

        // Verify DOWN swipe moves downward on screen (endY > startY)
        val downEvent = logs.first { it.direction == "DOWN" }
        assertTrue(downEvent.endY > downEvent.startY)
    }
}
