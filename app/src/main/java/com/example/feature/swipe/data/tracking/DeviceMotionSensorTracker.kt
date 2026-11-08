package com.example.feature.swipe.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService

/**
 * Option B: Device Motion Tracker using Android's [Sensor.TYPE_ACCELEROMETER] and [Sensor.TYPE_GYROSCOPE].
 *
 * Tracks physical tilting or flicking of the phone relative to a calibrated baseline orientation
 * and triggers [onDirectionDetected] (which invokes [SwipeControllerService.performSwipe]) with
 * "UP", "DOWN", "LEFT", or "RIGHT" when movement exceeds the threshold, enforcing a 250ms debounce cooldown.
 */
class DeviceMotionSensorTracker(
    context: Context,
    private val onDirectionDetected: (String) -> Unit = { direction ->
        SwipeControllerService.performSwipeWithSource(direction, "Device Motion Sensor")
    }
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var isListening = false
    private var hasBaseline = false
    private var baselineX = 0f
    private var baselineY = 5.0f

    fun startListening() {
        if (isListening || sensorManager == null) return
        hasBaseline = false
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        isListening = true
        SwipeTelemetryRepository.updatePoseModelStatus(
            if (accelerometer != null) {
                "Accelerometer + Gyroscope Active (250ms Debounce)"
            } else {
                "Sensor Interactive Mode Ready (250ms Debounce)"
            }
        )
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    fun recalibrateBaseline() {
        hasBaseline = false
        SwipeTelemetryRepository.recalibrateToCurrentPose()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        if (!SwipeTelemetryRepository.isMovementTrackingActive.value) return

        if (sensorEvent.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val rawAx = sensorEvent.values[0] // Tilt left (+) / right (-)
            val rawAy = sensorEvent.values[1] // Tilt forward/up / backward/down

            if (!hasBaseline) {
                baselineX = rawAx
                baselineY = rawAy
                hasBaseline = true
                SwipeTelemetryRepository.calibratePoseCenter(0.5f, 0.5f)
            }

            // Map accelerometer deltas (-6..+6 m/s^2) into normalized [0.0 .. 1.0] pose space
            val normalizedX = (0.5f - ((rawAx - baselineX) / 14f)).coerceIn(0.05f, 0.95f)
            val normalizedY = (0.5f + ((rawAy - baselineY) / 14f)).coerceIn(0.05f, 0.95f)

            val detectedDirection = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
                noseX = normalizedX,
                noseY = normalizedY - 0.08f,
                leftShoulderX = (normalizedX - 0.12f).coerceIn(0.02f, 0.98f),
                leftShoulderY = normalizedY + 0.06f,
                rightShoulderX = (normalizedX + 0.12f).coerceIn(0.02f, 0.98f),
                rightShoulderY = normalizedY + 0.06f,
                nowMs = System.currentTimeMillis()
            )

            if (detectedDirection != null) {
                onDirectionDetected(detectedDirection)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
