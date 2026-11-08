package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Before
    fun setUp() {
        SwipeTelemetryRepository.clearLogs()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SwipeController", appName)
    }

    @Test
    fun `performSwipe records UP DOWN LEFT RIGHT and DOUBLE_TAP gestures`() {
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

        // Verify UP swipe moves upward (endY < startY)
        val upEvent = logs.first { it.direction == "UP" }
        assertTrue(upEvent.endY < upEvent.startY)
    }
}
