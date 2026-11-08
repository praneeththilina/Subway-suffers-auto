package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.feature.swipe.domain.model.RunnerLane
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Production-grade Android AccessibilityService (API 24+) that:
 * 1. Dispatches directional swipe and double-tap gestures via [GestureDescription].
 * 2. Enforces 3-Column (LEFT = -1, MIDDLE = 0, RIGHT = +1) player position tracking so the player
 *    starts in MIDDLE, never swipes LEFT again when already in LEFT, and never swipes RIGHT again
 *    when already in RIGHT (allowing up to 2 steps back across to the opposite boundary).
 * 3. Reads the active display in real time using [takeScreenshot] (API 30+) to detect obstacles
 *    in the Left, Middle, and Right columns and automate gameplay in Subway Surfers.
 */
class SwipeControllerService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private var windowManager: WindowManager? = null
    private var floatingOverlayView: View? = null
    private var overlayStatusBadge: TextView? = null
    private var overlayActionBtn: TextView? = null
    private var overlayCountdownText: TextView? = null

    private var isAutoJumpRunningInternal = false
    private var nextTickTimestampMs = 0L
    private var lastDisplayAutopilotSwipeMs = 0L

    private val countdownTicker = object : Runnable {
        override fun run() {
            if (!isAutoJumpRunningInternal) return
            val now = System.currentTimeMillis()
            val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs

            // If Screen Vision Display Reader Autopilot is enabled on API 30+, read the actual display
            // every 350ms to detect obstacles across the 3 columns and steer automatically!
            if (SwipeTelemetryRepository.screenVisionState.value.isAutopilotEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                captureAndAnalyzeDisplayForAutopilot(now)
            }

            val remaining = nextTickTimestampMs - now
            if (remaining <= 0L) {
                performSwipeWithSource(DIRECTION_UP, "Milestone 3s Auto-Jump")
                SwipeTelemetryRepository.incrementAutoJumpCount()
                nextTickTimestampMs = now + intervalMs
                SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)
                updateFloatingOverlayUi()
                mainHandler.postDelayed(this, 120L)
            } else {
                SwipeTelemetryRepository.updateAutoJumpCountdown(remaining)
                updateFloatingOverlayCountdown(remaining)
                mainHandler.postDelayed(this, 120L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        SwipeTelemetryRepository.setServiceConnected(true)
        SwipeTelemetryRepository.resetLaneToMiddle()
        Log.i(TAG, "SwipeControllerService connected. Player initialized at MIDDLE column (0).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // When Subway Surfers launches or resets window state, ensure screen reader knows game is active
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.contains("subwaysurf", ignoreCase = true)) {
                Log.d(TAG, "Detected Subway Surfers foreground window ($pkg)")
            }
        }
    }

    override fun onInterrupt() {
        stopAutoJumpLoopInternal()
        Log.w(TAG, "SwipeControllerService interrupted.")
    }

    override fun onDestroy() {
        stopAutoJumpLoopInternal()
        removeFloatingOverlayInternal()
        screenshotExecutor.shutdown()
        SwipeTelemetryRepository.setServiceConnected(false)
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    /**
     * Reads the live screen pixels on Android 11+ (API 30+) via AccessibilityService.takeScreenshot,
     * divides the track area into 3 columns (LEFT, MIDDLE, RIGHT), detects high-contrast train/barrier
     * obstacles ahead of the player and the player's current column, and triggers automated swipes.
     */
    private fun captureAndAnalyzeDisplayForAutopilot(nowMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (nowMs - lastDisplayAutopilotSwipeMs < 380L) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    try {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (hwBitmap != null) {
                            val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                            if (softBitmap != null) {
                                analyzeGameFrameAndTriggerAutopilot(softBitmap, nowMs)
                                softBitmap.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Display reader frame analysis error: ${e.message}")
                    } finally {
                        hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Rate-limited or transient screenshot error; next tick will retry
                }
            }
        )
    }

    private fun analyzeGameFrameAndTriggerAutopilot(bitmap: Bitmap, nowMs: Long) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) return

        // Scan the 3 track columns in the lookahead zone (Y from 40% to 62% of screen height)
        val scanTopY = (h * 0.42f).toInt()
        val scanBottomY = (h * 0.60f).toInt()

        val colCentersX = listOf(
            (w * 0.24f).toInt(), // Left column (-1)
            (w * 0.50f).toInt(), // Middle column (0)
            (w * 0.76f).toInt()  // Right column (+1)
        )

        val hazards = FloatArray(3)
        var obstacleType = "TRAIN"

        for (colIdx in 0..2) {
            val cx = colCentersX[colIdx]
            var edgeEnergy = 0f
            var redOrangeBarrierScore = 0f
            var samples = 0

            for (y in scanTopY until scanBottomY step 8) {
                for (dx in -24..24 step 12) {
                    val x = (cx + dx).coerceIn(1, w - 2)
                    val pixel = bitmap.getPixel(x, y)
                    val prevPixel = bitmap.getPixel(x, (y - 6).coerceAtLeast(0))

                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    val pr = Color.red(prevPixel)
                    val pg = Color.green(prevPixel)
                    val pb = Color.blue(prevPixel)

                    val lumaDiff = (abs(r - pr) + abs(g - pg) + abs(b - pb)) / (3f * 255f)
                    edgeEnergy += lumaDiff

                    // Detect bright red/orange/yellow Subway Surfers barriers & train fronts
                    if (r > 165 && r > b + 45) {
                        redOrangeBarrierScore += 1f
                    }
                    samples++
                }
            }

            if (samples > 0) {
                val avgEdge = (edgeEnergy / samples) * 3.2f
                val avgBarrier = (redOrangeBarrierScore / samples) * 1.8f
                hazards[colIdx] = ((avgEdge * 0.55f) + (avgBarrier * 0.45f)).coerceIn(0f, 1f)
                if (avgBarrier > 0.45f) {
                    obstacleType = "LOW_BARRIER"
                }
            }
        }

        val actionToTake = SwipeTelemetryRepository.updateScreenVisionReadingAndDecideAction(
            leftHazard = hazards[0],
            middleHazard = hazards[1],
            rightHazard = hazards[2],
            detectedPlayerColumn = null,
            obstacleType = obstacleType
        )

        if (actionToTake != null && nowMs - lastDisplayAutopilotSwipeMs >= 420L) {
            lastDisplayAutopilotSwipeMs = nowMs
            mainHandler.post {
                performSwipeWithSource(actionToTake, "Display Reader Autopilot")
            }
        }
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
            SwipeTelemetryRepository.setAutopilotEnabled(true)

            performSwipeWithSource(DIRECTION_UP, "Milestone 3s Auto-Jump")
            SwipeTelemetryRepository.incrementAutoJumpCount()

            val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
            nextTickTimestampMs = System.currentTimeMillis() + intervalMs
            SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)
            updateFloatingOverlayUi()

            mainHandler.removeCallbacks(countdownTicker)
            mainHandler.postDelayed(countdownTicker, 120L)
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
        val windowLayoutParams = WindowManager.LayoutParams(
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
            val lane = SwipeTelemetryRepository.screenVisionState.value.currentLane
            text = "Lane: ${lane.shortCode} • Ready"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dpToPx(4), 0, dpToPx(6))
        }
        overlayCountdownText = countdownTv

        val actionBtn = TextView(this).apply {
            text = "▶ AUTO PLAY / JUMP"
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

        val resetMiddleBtn = TextView(this).apply {
            text = "↺ RESET MIDDLE (0)"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(4))
            setOnClickListener {
                SwipeTelemetryRepository.resetLaneToMiddle()
                updateFloatingOverlayUi()
            }
        }

        container.addView(headerRow)
        container.addView(countdownTv)
        container.addView(actionBtn)
        container.addView(resetMiddleBtn)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowLayoutParams.x
                    initialY = windowLayoutParams.y
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
                        windowLayoutParams.x = initialX + dx
                        windowLayoutParams.y = initialY + dy
                        runCatching { wm.updateViewLayout(container, windowLayoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }

        return try {
            wm.addView(container, windowLayoutParams)
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
        val lane = SwipeTelemetryRepository.screenVisionState.value.currentLane

        if (isAutoJumpRunningInternal) {
            badge.text = "● AUTOPILOT (${lane.shortCode})"
            badge.setTextColor(Color.parseColor("#FF6B4A"))
            actionBtn.text = "■ STOP AUTOPILOT"
            actionBtn.setTextColor(Color.WHITE)
            actionBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#FF5252"))
            }
        } else {
            badge.text = "SWIPE OVERLAY"
            badge.setTextColor(Color.parseColor("#00E5FF"))
            countdownTv.text = "Lane: ${lane.shortCode} (${lane.remainingStepsLeft}L/${lane.remainingStepsRight}R)"
            actionBtn.text = "▶ AUTO PLAY / JUMP"
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
        val lane = SwipeTelemetryRepository.screenVisionState.value.currentLane
        countdownTv.text = String.format("Lane %s • Next %.1fs", lane.shortCode, seconds)
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

        @JvmStatic
        fun isServiceActive(): Boolean = instanceRef?.get() != null

        /**
         * Public static method required by specification:
         * Dispatches UP, DOWN, LEFT, RIGHT, or DOUBLE_TAP gestures using [GestureDescription]
         * on Android API 24+, enforcing 3-Column boundary tracking (LEFT = -1, MIDDLE = 0, RIGHT = +1).
         */
        @JvmStatic
        fun performSwipe(direction: String): Boolean {
            return performSwipeWithSource(direction, "performSwipe API")
        }

        @JvmStatic
        fun performSwipeWithSource(direction: String, source: String): Boolean {
            val normalized = direction.trim().uppercase()

            // Validate against the 3-Column state machine (start from MIDDLE = 0):
            // - If in LEFT (-1), block another LEFT! Allow up to 2 RIGHT.
            // - If in RIGHT (+1), block another RIGHT! Allow up to 2 LEFT.
            val allowedByLane = SwipeTelemetryRepository.validateAndAdvanceColumnForSwipe(
                direction = normalized,
                source = source
            )
            if (!allowedByLane) {
                runCatching { Log.i(TAG, "Blocked out-of-bounds swipe '$normalized' from $source") }
                return false
            }

            val service = instanceRef?.get()
            if (service != null) {
                return service.dispatchDirectionGesture(normalized, source)
            }

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
                statusMessage = "Dispatched in-app (Enable AccessibilityService for cross-app OS dispatch)",
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
