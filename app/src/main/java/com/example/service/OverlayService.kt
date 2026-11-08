package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service using [WindowManager] with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * and [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] so it sits on top of Subway Surfers without
 * stealing touch focus from the underlying game.
 *
 * Includes:
 * 1. A mini live CameraX preview (with graceful fallback badge if camera permission is off).
 * 2. A high-contrast real-time TextView displaying the last triggered action (JUMP, ROLL, LEFT, RIGHT).
 * 3. A Start/Stop tracking toggle button that controls the 3-second automated jump/gesture tracker.
 */
class OverlayService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayRootView: View? = null

    private var cameraContainer: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cameraStatusText: TextView? = null
    private var actionLabelTextView: TextView? = null
    private var subStatusTextView: TextView? = null
    private var startStopToggleButton: TextView? = null

    private var poseLandmarkerController: com.example.feature.swipe.data.tracking.PoseLandmarkerController? = null
    private var deviceMotionTracker: com.example.feature.swipe.data.tracking.DeviceMotionSensorTracker? = null

    private var localTrackingActive = false
    private var nextJumpTickMs = 0L

    private val trackingLoopRunnable = object : Runnable {
        override fun run() {
            if (!localTrackingActive) return
            val now = System.currentTimeMillis()
            val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
            val remaining = nextJumpTickMs - now

            if (remaining <= 0L) {
                SwipeControllerService.performSwipeWithSource(
                    SwipeControllerService.DIRECTION_UP,
                    "OverlayService Tracking"
                )
                SwipeTelemetryRepository.incrementAutoJumpCount()
                nextJumpTickMs = now + intervalMs
                SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)
                mainHandler.postDelayed(this, 100L)
            } else {
                SwipeTelemetryRepository.updateAutoJumpCountdown(remaining)
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        poseLandmarkerController = com.example.feature.swipe.data.tracking.PoseLandmarkerController(this) { dir ->
            SwipeControllerService.performSwipeWithSource(dir, "MediaPipe Pose Overlay")
        }
        deviceMotionTracker = com.example.feature.swipe.data.tracking.DeviceMotionSensorTracker(this) { dir ->
            SwipeControllerService.performSwipeWithSource(dir, "Device Motion Overlay")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        startForegroundWithNotification()
        showTranslucentOverlay()
        observeGestureTelemetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                stopTrackingLoop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CAMERA -> {
                val nextCamState = !SwipeTelemetryRepository.isCameraPreviewEnabledInOverlay.value
                SwipeTelemetryRepository.setCameraPreviewEnabledInOverlay(nextCamState)
                bindOrUpdateMiniCameraPreview()
            }
            ACTION_UPDATE_GESTURE_LABEL -> {
                val gestureName = intent.getStringExtra(EXTRA_GESTURE_NAME)
                if (!gestureName.isNullOrBlank()) {
                    updateDetectedActionBadge(gestureName)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTrackingLoop()
        deviceMotionTracker?.stopListening()
        poseLandmarkerController?.release()
        removeTranslucentOverlay()
        SwipeTelemetryRepository.setApplicationOverlayRunning(false)
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = NOTIFICATION_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SwipeController Translucent Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays real-time gesture detection and Start/Stop control over games"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val launchAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchAppIntent,
            pendingIntentFlags
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            pendingIntentFlags
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SwipeController Overlay Active")
            .setContentText("Translucent HUD running over game • Tap to configure")
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Close Overlay", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasCameraPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val fgType = if (hasCameraPerm) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, fgType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showTranslucentOverlay() {
        if (overlayRootView != null) return
        val wm = windowManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; cannot attach TYPE_APPLICATION_OVERLAY.")
            SwipeTelemetryRepository.setApplicationOverlayRunning(false)
            stopSelf()
            return
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Crucial flags:
        // - TYPE_APPLICATION_OVERLAY: Renders over Subway Surfers and other apps
        // - FLAG_NOT_FOCUSABLE: Never steals key/touch focus from the underlying game
        // - FLAG_NOT_TOUCH_MODAL: Passes all outside touches directly to the underlying game
        val windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(110)
        }

        // Translucent root pill card (#CC090D16 = 80% translucent obsidian with glowing cyan border)
        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#CC090D16"))
                setStroke(dpToPx(1), Color.parseColor("#9900E5FF"))
            }
            elevation = dpToPx(10).toFloat()
        }

        // Top Header Row: Drag Handle Title + Camera Toggle + Close Button
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBadge = TextView(this).apply {
            text = "⠿ SWIPE HUD"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dpToPx(8), 0)
        }

        val camToggleBtn = TextView(this).apply {
            text = "CAM"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#661E293B"))
            }
            setOnClickListener {
                val nextState = !SwipeTelemetryRepository.isCameraPreviewEnabledInOverlay.value
                SwipeTelemetryRepository.setCameraPreviewEnabledInOverlay(nextState)
                bindOrUpdateMiniCameraPreview()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(2), dpToPx(2), dpToPx(2))
            setOnClickListener {
                stopTrackingLoop()
                stopSelf()
            }
        }

        topHeader.addView(titleBadge)
        topHeader.addView(camToggleBtn)
        topHeader.addView(closeBtn)

        // Middle Content Row: Mini Camera Preview (Left) + Real-Time Detected Action Label (Right)
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }

        // 1. Mini Camera Preview Container (58dp x 72dp rounded viewport)
        val camFrame = FrameLayout(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(dpToPx(58), dpToPx(72)).apply {
                marginEnd = dpToPx(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#AA111827"))
                setStroke(dpToPx(1), Color.parseColor("#5500E5FF"))
            }
            clipToOutline = true
        }
        cameraContainer = camFrame

        val camPreview = PreviewView(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = camPreview

        val camStatusTv = TextView(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = "CAM\nOFF"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
        }
        cameraStatusText = camStatusTv

        camFrame.addView(camPreview)
        camFrame.addView(camStatusTv)

        // 2. Real-Time Action Label Column (JUMP, ROLL, LEFT, RIGHT) + Start/Stop Toggle
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val actionHeaderTv = TextView(this).apply {
            text = "LAST DETECTED"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val detectedActionTv = TextView(this).apply {
            text = SwipeTelemetryRepository.lastActionLabel.value
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dpToPx(1), 0, dpToPx(2))
        }
        actionLabelTextView = detectedActionTv

        val subStatusTv = TextView(this).apply {
            text = "Idle • Tap Start"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setPadding(0, 0, 0, dpToPx(6))
        }
        subStatusTextView = subStatusTv

        val toggleBtn = TextView(this).apply {
            text = "▶ START"
            setTextColor(Color.parseColor("#041318"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                toggleStartStopTracking()
            }
        }
        startStopToggleButton = toggleBtn

        rightColumn.addView(actionHeaderTv)
        rightColumn.addView(detectedActionTv)
        rightColumn.addView(subStatusTv)
        rightColumn.addView(toggleBtn)

        contentRow.addView(camFrame)
        contentRow.addView(rightColumn)

        // Quick Manual Action Bar (JUMP / ROLL / LEFT / RIGHT) for testing directly over the game
        val quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        listOf(
            "↑" to SwipeControllerService.DIRECTION_UP,
            "↓" to SwipeControllerService.DIRECTION_DOWN,
            "←" to SwipeControllerService.DIRECTION_LEFT,
            "→" to SwipeControllerService.DIRECTION_RIGHT
        ).forEach { (symbol, dir) ->
            val chip = TextView(this).apply {
                text = symbol
                setTextColor(Color.parseColor("#00E5FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                this.layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(28)).apply {
                    marginEnd = dpToPx(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#881E293B"))
                    setStroke(dpToPx(1), Color.parseColor("#4400E5FF"))
                }
                setOnClickListener {
                    SwipeControllerService.performSwipeWithSource(dir, "Overlay HUD Button")
                }
            }
            quickBar.addView(chip)
        }

        rootCard.addView(topHeader)
        rootCard.addView(contentRow)
        rootCard.addView(quickBar)

        // Draggable window touch listener on root card
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        rootCard.setOnTouchListener { _, event ->
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
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        isDragging = true
                        windowLayoutParams.x = initialX + dx
                        windowLayoutParams.y = initialY + dy
                        runCatching { wm.updateViewLayout(rootCard, windowLayoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }

        try {
            wm.addView(rootCard, windowLayoutParams)
            overlayRootView = rootCard
            SwipeTelemetryRepository.setApplicationOverlayRunning(true)
            bindOrUpdateMiniCameraPreview()
            syncToggleUi(SwipeTelemetryRepository.isAutoJumpRunning.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add TYPE_APPLICATION_OVERLAY view", e)
            SwipeTelemetryRepository.setApplicationOverlayRunning(false)
        }
    }

    private fun bindOrUpdateMiniCameraPreview() {
        val camContainer = cameraContainer ?: return
        val pv = previewView ?: return
        val statusTv = cameraStatusText ?: return

        val enabledByUser = SwipeTelemetryRepository.isCameraPreviewEnabledInOverlay.value
        if (!enabledByUser) {
            camContainer.visibility = View.GONE
            return
        }
        camContainer.visibility = View.VISIBLE

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            pv.visibility = View.GONE
            statusTv.visibility = View.VISIBLE
            statusTv.text = "NO CAM\nPERM"
            return
        }

        val controller = poseLandmarkerController
        if (controller != null) {
            controller.bindCameraPoseStream(this, pv)
            pv.visibility = View.VISIBLE
            statusTv.visibility = View.GONE
        } else {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = pv.surfaceProvider
                    }
                    val selector = when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        else -> null
                    }

                    if (selector != null) {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this, selector, preview)
                        pv.visibility = View.VISIBLE
                        statusTv.visibility = View.GONE
                    } else {
                        pv.visibility = View.GONE
                        statusTv.visibility = View.VISIBLE
                        statusTv.text = "CAM\nSIM"
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Mini camera preview fallback activated", e)
                    pv.visibility = View.GONE
                    statusTv.visibility = View.VISIBLE
                    statusTv.text = "CAM\nSIM"
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun observeGestureTelemetry() {
        // Observe last triggered action (JUMP, ROLL, LEFT, RIGHT)
        serviceScope.launch {
            SwipeTelemetryRepository.lastActionLabel.collectLatest { actionLabel ->
                updateDetectedActionBadge(actionLabel)
            }
        }

        // Observe tracking state and countdown
        serviceScope.launch {
            SwipeTelemetryRepository.isAutoJumpRunning.collectLatest { isRunning ->
                syncToggleUi(isRunning || SwipeTelemetryRepository.isMovementTrackingActive.value)
            }
        }

        serviceScope.launch {
            SwipeTelemetryRepository.isMovementTrackingActive.collectLatest { isPoseTracking ->
                if (isPoseTracking) {
                    deviceMotionTracker?.startListening()
                } else {
                    deviceMotionTracker?.stopListening()
                }
                syncToggleUi(isPoseTracking || SwipeTelemetryRepository.isAutoJumpRunning.value)
            }
        }

        serviceScope.launch {
            SwipeTelemetryRepository.autoJumpCountdownMs.collectLatest { remainingMs ->
                if (SwipeTelemetryRepository.isAutoJumpRunning.value) {
                    val count = SwipeTelemetryRepository.autoJumpTotalCount.value
                    subStatusTextView?.text = String.format("Next UP: %.1fs (#%d)", remainingMs / 1000f, count)
                }
            }
        }

        serviceScope.launch {
            SwipeTelemetryRepository.poseCalibrationState.collectLatest { poseState ->
                if (!SwipeTelemetryRepository.isAutoJumpRunning.value &&
                    SwipeTelemetryRepository.isMovementTrackingActive.value
                ) {
                    subStatusTextView?.text = when {
                        poseState.jumpCancelActive -> "⚡ JUMP-CANCEL!"
                        poseState.horizontalState == com.example.feature.swipe.domain.model.AxisLockState.WAITING_FOR_NEUTRAL ->
                            "X: WAIT_NEUTRAL (${poseState.horizontalNeutralProgressMs}ms)"
                        poseState.verticalState == com.example.feature.swipe.domain.model.AxisLockState.WAITING_FOR_NEUTRAL ->
                            "Y: WAIT_NEUTRAL (${poseState.verticalNeutralProgressMs}ms)"
                        else -> "NEUTRAL READY"
                    }
                }
            }
        }
    }

    private fun updateDetectedActionBadge(actionLabel: String) {
        val tv = actionLabelTextView ?: return
        tv.text = actionLabel
        val colorHex = when (actionLabel) {
            "JUMP" -> "#10B981" // Emerald
            "ROLL" -> "#F59E0B" // Amber
            "LEFT", "RIGHT" -> "#00E5FF" // Electric Cyan
            "HOVERBOARD" -> "#FF6B4A" // Kinetic Coral
            else -> "#00E5FF"
        }
        tv.setTextColor(Color.parseColor(colorHex))
    }

    private fun toggleStartStopTracking() {
        val currentlyTracking = SwipeTelemetryRepository.isMovementTrackingActive.value ||
            SwipeTelemetryRepository.isAutoJumpRunning.value
        val nextRunning = !currentlyTracking

        SwipeTelemetryRepository.setMovementTrackingActive(nextRunning)

        if (SwipeControllerService.isServiceActive()) {
            stopLocalLoopOnly()
            SwipeControllerService.setAutoJumpLoopActive(nextRunning)
        } else {
            if (nextRunning) {
                startTrackingLoop()
            } else {
                stopTrackingLoop()
            }
        }
    }

    private fun startTrackingLoop() {
        localTrackingActive = true
        SwipeTelemetryRepository.setAutoJumpRunning(true)

        // Immediate first UP gesture
        SwipeControllerService.performSwipeWithSource(
            SwipeControllerService.DIRECTION_UP,
            "OverlayService Tracking"
        )
        SwipeTelemetryRepository.incrementAutoJumpCount()

        val intervalMs = SwipeTelemetryRepository.tuningConfig.value.milestoneIntervalMs
        nextJumpTickMs = System.currentTimeMillis() + intervalMs
        SwipeTelemetryRepository.updateAutoJumpCountdown(intervalMs)

        mainHandler.removeCallbacks(trackingLoopRunnable)
        mainHandler.postDelayed(trackingLoopRunnable, 100L)
    }

    private fun stopTrackingLoop() {
        stopLocalLoopOnly()
        SwipeControllerService.setAutoJumpLoopActive(false)
        SwipeTelemetryRepository.setAutoJumpRunning(false)
    }

    private fun stopLocalLoopOnly() {
        localTrackingActive = false
        mainHandler.removeCallbacks(trackingLoopRunnable)
    }

    private fun syncToggleUi(isRunning: Boolean) {
        val btn = startStopToggleButton ?: return
        val subTv = subStatusTextView ?: return
        if (isRunning) {
            btn.text = "■ STOP"
            btn.setTextColor(Color.WHITE)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#FF5252"))
            }
        } else {
            btn.text = "▶ START"
            btn.setTextColor(Color.parseColor("#041318"))
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
            subTv.text = "Idle • Tap Start"
        }
    }

    private fun removeTranslucentOverlay() {
        val view = overlayRootView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayRootView = null
        cameraContainer = null
        previewView = null
        cameraStatusText = null
        actionLabelTextView = null
        subStatusTextView = null
        startStopToggleButton = null
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "swipe_controller_overlay_channel"
        private const val NOTIFICATION_ID = 4021

        const val ACTION_STOP_OVERLAY = "com.example.service.action.STOP_OVERLAY"
        const val ACTION_TOGGLE_CAMERA = "com.example.service.action.TOGGLE_CAMERA"
        const val ACTION_UPDATE_GESTURE_LABEL = "com.example.service.action.UPDATE_GESTURE_LABEL"
        const val EXTRA_GESTURE_NAME = "extra_gesture_name"

        @JvmStatic
        fun startOverlay(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stopOverlay(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            context.startService(intent)
        }
    }
}
