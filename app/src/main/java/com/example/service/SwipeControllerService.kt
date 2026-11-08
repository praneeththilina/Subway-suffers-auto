package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Production-grade Android AccessibilityService (API 24+) that dispatches directional swipe
 * and double-tap gestures via [GestureDescription] and provides an interactive TYPE_ACCESSIBILITY_OVERLAY
 * floating Milestone Test widget capable of triggering performSwipe("UP") every 3 seconds over
 * external games such as Subway Surfers.
 */
class SwipeControllerService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var floatingOverlayView: View? = null
    private var overlayStatusBadge: TextView? = null
    private var overlayActionBtn: TextView? = null
    private var overlayCountdownText: TextView? = null

    private var isAutoJumpRunningInternal = false
    private var nextTickTimestampMs = 0L

    private val countdownTicker = object : Runnable {
        override fun run() {
            if (!isAutoJumpRunningInternal) return
            val now = System.currentTimeMillis()
            val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
            val remaining = nextTickTimestampMs - now

            if (remaining <= 0L) {
                performSwipeWithSource(DIRECTION_UP, "Milestone 3s Auto-Jump")
                SwipeTelemetryRepository.incrementAutoJumpCount()
                nextTickTimestampMs = now + intervalMs
                SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)
                updateFloatingOverlayUi()
                mainHandler.postDelayed(this, 100L)
            } else {
                SwipeTelemetryRepository.updateAutoJumpCountdown(remaining)
                updateFloatingOverlayCountdown(remaining)
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        SwipeTelemetryRepository.setServiceConnected(true)
        Log.i(TAG, "SwipeControllerService connected and ready to dispatch gestures.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Optional window/click telemetry hook; primary responsibility is GestureDescription dispatch.
    }

    override fun onInterrupt() {
        stopAutoJumpLoopInternal()
        Log.w(TAG, "SwipeControllerService interrupted.")
    }

    override fun onDestroy() {
        stopAutoJumpLoopInternal()
        removeFloatingOverlayInternal()
        SwipeTelemetryRepository.setServiceConnected(false)
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    /**
     * Builds and dispatches a [GestureDescription] for UP, DOWN, LEFT, RIGHT, or DOUBLE_TAP.
     */
    internal fun dispatchDirectionGesture(direction: String, source: String): Boolean {
        val normalized = direction.trim().uppercase()
        val (screenWidth, screenHeight) = getScreenDimensions()
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f

        val config = SwipeTelemetryRepository.tuningConfig.value
        val swipeSpanX = screenWidth * config.swipeAmplitudeFraction
        val swipeSpanY = screenHeight * config.swipeAmplitudeFraction
        val swipeDuration = config.swipeDurationMs.coerceIn(50L, 600L)

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        val gestureDescription: GestureDescription

        when (normalized) {
            DIRECTION_UP -> {
                startX = centerX
                startY = centerY + (swipeSpanY / 2f)
                endX = centerX
                endY = (centerY - (swipeSpanY / 2f)).coerceAtLeast(1f)
                gestureDescription = buildSingleStrokeGesture(startX, startY, endX, endY, swipeDuration)
            }
            DIRECTION_DOWN -> {
                startX = centerX
                startY = centerY - (swipeSpanY / 2f)
                endX = centerX
                endY = (centerY + (swipeSpanY / 2f)).coerceAtMost(screenHeight - 1f)
                gestureDescription = buildSingleStrokeGesture(startX, startY, endX, endY, swipeDuration)
            }
            DIRECTION_LEFT -> {
                startX = centerX + (swipeSpanX / 2f)
                startY = centerY
                endX = (centerX - (swipeSpanX / 2f)).coerceAtLeast(1f)
                endY = centerY
                gestureDescription = buildSingleStrokeGesture(startX, startY, endX, endY, swipeDuration)
            }
            DIRECTION_RIGHT -> {
                startX = centerX - (swipeSpanX / 2f)
                startY = centerY
                endX = (centerX + (swipeSpanX / 2f)).coerceAtMost(screenWidth - 1f)
                endY = centerY
                gestureDescription = buildSingleStrokeGesture(startX, startY, endX, endY, swipeDuration)
            }
            DIRECTION_DOUBLE_TAP -> {
                startX = centerX
                startY = centerY
                endX = centerX
                endY = centerY
                gestureDescription = buildDoubleTapGesture(centerX, centerY)
            }
            else -> {
                Log.w(TAG, "Unsupported swipe direction: $direction")
                SwipeTelemetryRepository.recordGestureEvent(
                    direction = normalized,
                    startX = centerX,
                    startY = centerY,
                    endX = centerX,
                    endY = centerY,
                    durationMs = 0L,
                    dispatchedViaService = false,
                    statusMessage = "Rejected unknown direction '$direction'",
                    source = source
                )
                return false
            }
        }

        val dispatched = dispatchGesture(
            gestureDescription,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Gesture $normalized completed ($source)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture $normalized cancelled ($source)")
                }
            },
            mainHandler
        )

        val effectiveDuration = if (normalized == DIRECTION_DOUBLE_TAP) {
            DOUBLE_TAP_TOTAL_DURATION_MS
        } else {
            swipeDuration
        }

        SwipeTelemetryRepository.recordGestureEvent(
            direction = normalized,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = effectiveDuration,
            dispatchedViaService = dispatched,
            statusMessage = if (dispatched) {
                "GestureDescription dispatched via AccessibilityService"
            } else {
                "AccessibilityService dispatchGesture returned false"
            },
            source = source
        )
        return dispatched
    }

    private fun buildSingleStrokeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): GestureDescription {
        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(swipePath, 0L, durationMs)
        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    private fun buildDoubleTapGesture(x: Float, y: Float): GestureDescription {
        val tapPathFirst = Path().apply { moveTo(x, y) }
        val tapPathSecond = Path().apply { moveTo(x, y) }

        val firstTapStroke = GestureDescription.StrokeDescription(
            tapPathFirst,
            0L,
            TAP_STROKE_DURATION_MS
        )
        val secondTapStroke = GestureDescription.StrokeDescription(
            tapPathSecond,
            TAP_STROKE_DURATION_MS + DOUBLE_TAP_GAP_MS,
            TAP_STROKE_DURATION_MS
        )

        return GestureDescription.Builder()
            .addStroke(firstTapStroke)
            .addStroke(secondTapStroke)
            .build()
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as? WindowManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            val w = bounds.width()
            val h = bounds.height()
            if (w > 0 && h > 0) return w to h
        }
        val metrics: DisplayMetrics = resources.displayMetrics
        val width = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val height = metrics.heightPixels.takeIf { it > 0 } ?: 2400
        return width to height
    }

    internal fun startAutoJumpLoopInternal() {
        mainHandler.post {
            if (isAutoJumpRunningInternal) return@post
            isAutoJumpRunningInternal = true
            SwipeTelemetryRepository.setAutoJumpRunning(true)

            // Trigger an immediate UP jump and schedule every 3 seconds (or configured interval)
            performSwipeWithSource(DIRECTION_UP, "Milestone 3s Auto-Jump")
            SwipeTelemetryRepository.incrementAutoJumpCount()

            val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
            nextTickTimestampMs = System.currentTimeMillis() + intervalMs
            SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)
            updateFloatingOverlayUi()

            mainHandler.removeCallbacks(countdownTicker)
            mainHandler.postDelayed(countdownTicker, 100L)
        }
    }

    internal fun stopAutoJumpLoopInternal() {
        mainHandler.post {
            isAutoJumpRunningInternal = false
            mainHandler.removeCallbacks(countdownTicker)
            SwipeTelemetryRepository.setAutoJumpRunning(false)
            updateFloatingOverlayUi()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    internal fun showFloatingOverlayInternal(): Boolean {
        if (floatingOverlayView != null) {
            SwipeTelemetryRepository.setFloatingOverlayVisible(true)
            updateFloatingOverlayUi()
            return true
        }

        val wm = windowManager ?: return false
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(180)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#EE0D1117"))
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
            elevation = dpToPx(8).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = "SWIPE OVERLAY"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dpToPx(10), 0)
        }
        overlayStatusBadge = badge

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), dpToPx(2), dpToPx(4), dpToPx(2))
            setOnClickListener {
                stopAutoJumpLoopInternal()
                removeFloatingOverlayInternal()
            }
        }

        headerRow.addView(badge)
        headerRow.addView(closeBtn)

        val countdownTv = TextView(this).apply {
            text = "3.0s Interval Ready"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dpToPx(4), 0, dpToPx(6))
        }
        overlayCountdownText = countdownTv

        val actionBtn = TextView(this).apply {
            text = "▶ AUTO JUMP (3s)"
            setTextColor(Color.parseColor("#081018"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                if (isAutoJumpRunningInternal) {
                    stopAutoJumpLoopInternal()
                } else {
                    startAutoJumpLoopInternal()
                }
            }
        }
        overlayActionBtn = actionBtn

        val singleSwipeBtn = TextView(this).apply {
            text = "⚡ SWIPE UP NOW"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(4))
            setOnClickListener {
                performSwipeWithSource(DIRECTION_UP, "Floating Overlay Manual")
            }
        }

        container.addView(headerRow)
        container.addView(countdownTv)
        container.addView(actionBtn)
        container.addView(singleSwipeBtn)

        // Enable smooth dragging of the floating overlay anywhere on screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        runCatching { wm.updateViewLayout(container, layoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }

        return try {
            wm.addView(container, layoutParams)
            floatingOverlayView = container
            SwipeTelemetryRepository.setFloatingOverlayVisible(true)
            updateFloatingOverlayUi()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach TYPE_ACCESSIBILITY_OVERLAY floating button", e)
            false
        }
    }

    internal fun removeFloatingOverlayInternal() {
        mainHandler.post {
            val view = floatingOverlayView ?: return@post
            runCatching { windowManager?.removeView(view) }
            floatingOverlayView = null
            overlayStatusBadge = null
            overlayActionBtn = null
            overlayCountdownText = null
            SwipeTelemetryRepository.setFloatingOverlayVisible(false)
        }
    }

    private fun updateFloatingOverlayUi() {
        val actionBtn = overlayActionBtn ?: return
        val badge = overlayStatusBadge ?: return
        val countdownTv = overlayCountdownText ?: return

        if (isAutoJumpRunningInternal) {
            badge.text = "● AUTO-JUMPING"
            badge.setTextColor(Color.parseColor("#FF6B4A"))
            actionBtn.text = "■ STOP 3s JUMP"
            actionBtn.setTextColor(Color.WHITE)
            actionBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#FF5252"))
            }
        } else {
            badge.text = "SWIPE OVERLAY"
            badge.setTextColor(Color.parseColor("#00E5FF"))
            countdownTv.text = "Ready • UP every 3.0s"
            actionBtn.text = "▶ AUTO JUMP (3s)"
            actionBtn.setTextColor(Color.parseColor("#081018"))
            actionBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
        }
    }

    private fun updateFloatingOverlayCountdown(remainingMs: Long) {
        val countdownTv = overlayCountdownText ?: return
        val seconds = remainingMs / 1000f
        val count = SwipeTelemetryRepository.autoJumpTotalCount.value
        countdownTv.text = String.format("Next UP in %.1fs (#%d)", seconds, count)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "SwipeControllerService"

        const val DIRECTION_UP = "UP"
        const val DIRECTION_DOWN = "DOWN"
        const val DIRECTION_LEFT = "LEFT"
        const val DIRECTION_RIGHT = "RIGHT"
        const val DIRECTION_DOUBLE_TAP = "DOUBLE_TAP"

        private const val TAP_STROKE_DURATION_MS = 45L
        private const val DOUBLE_TAP_GAP_MS = 65L
        private const val DOUBLE_TAP_TOTAL_DURATION_MS =
            (TAP_STROKE_DURATION_MS * 2) + DOUBLE_TAP_GAP_MS

        @Volatile
        private var instanceRef: WeakReference<SwipeControllerService>? = null

        /**
         * Returns true if [SwipeControllerService] is currently bound and active.
         */
        @JvmStatic
        fun isServiceActive(): Boolean = instanceRef?.get() != null

        /**
         * Public static method required by specification:
         * Dispatches UP, DOWN, LEFT, RIGHT, or DOUBLE_TAP gestures using [GestureDescription]
         * on Android API 24+.
         *
         * @param direction One of "UP", "DOWN", "LEFT", "RIGHT", or "DOUBLE_TAP" (case-insensitive).
         * @return true if the gesture was dispatched by the active AccessibilityService, false otherwise.
         */
        @JvmStatic
        fun performSwipe(direction: String): Boolean {
            return performSwipeWithSource(direction, "performSwipe API")
        }

        @JvmStatic
        fun performSwipeWithSource(direction: String, source: String): Boolean {
            val normalized = direction.trim().uppercase()
            val service = instanceRef?.get()
            if (service != null) {
                return service.dispatchDirectionGesture(normalized, source)
            }

            // If AccessibilityService is not yet enabled by the user in Settings, record the
            // exact geometric stroke in telemetry so in-app Milestone Sandbox and Trajectory Canvas
            // still visualize and react to the call while guiding the user to enable the service.
            val config = SwipeTelemetryRepository.tuningConfig.value
            val fallbackWidth = 1080f
            val fallbackHeight = 2400f
            val cx = fallbackWidth / 2f
            val cy = fallbackHeight / 2f
            val spanX = fallbackWidth * config.swipeAmplitudeFraction
            val spanY = fallbackHeight * config.swipeAmplitudeFraction

            val (sx, sy, ex, ey) = when (normalized) {
                DIRECTION_UP -> listOf(cx, cy + spanY / 2f, cx, cy - spanY / 2f)
                DIRECTION_DOWN -> listOf(cx, cy - spanY / 2f, cx, cy + spanY / 2f)
                DIRECTION_LEFT -> listOf(cx + spanX / 2f, cy, cx - spanX / 2f, cy)
                DIRECTION_RIGHT -> listOf(cx - spanX / 2f, cy, cx + spanX / 2f, cy)
                DIRECTION_DOUBLE_TAP -> listOf(cx, cy, cx, cy)
                else -> {
                    Log.w(TAG, "Unsupported direction '$direction' when service inactive.")
                    return false
                }
            }

            SwipeTelemetryRepository.recordGestureEvent(
                direction = normalized,
                startX = sx,
                startY = sy,
                endX = ex,
                endY = ey,
                durationMs = if (normalized == DIRECTION_DOUBLE_TAP) {
                    DOUBLE_TAP_TOTAL_DURATION_MS
                } else {
                    config.swipeDurationMs
                },
                dispatchedViaService = false,
                statusMessage = "Simulated in-app (Enable AccessibilityService for cross-app OS dispatch)",
                source = source
            )
            return false
        }

        @JvmStatic
        fun toggleFloatingMilestoneButton(): Boolean {
            val service = instanceRef?.get() ?: return false
            val currentlyVisible = SwipeTelemetryRepository.isFloatingOverlayVisible.value
            return if (currentlyVisible) {
                service.removeFloatingOverlayInternal()
                false
            } else {
                service.showFloatingOverlayInternal()
            }
        }

        @JvmStatic
        fun setAutoJumpLoopActive(active: Boolean) {
            val service = instanceRef?.get()
            if (service != null) {
                if (active) {
                    service.startAutoJumpLoopInternal()
                } else {
                    service.stopAutoJumpLoopInternal()
                }
            } else {
                SwipeTelemetryRepository.setAutoJumpRunning(active)
            }
        }
    }
}
