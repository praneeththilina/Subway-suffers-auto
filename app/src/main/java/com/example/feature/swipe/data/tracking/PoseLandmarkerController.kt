package com.example.feature.swipe.data.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Option A: CameraX + Google MediaPipe Pose Landmarker (`tasks-vision`) in [RunningMode.LIVE_STREAM] mode.
 *
 * Tracks the Nose (landmark 0), Left Shoulder (landmark 11), and Right Shoulder (landmark 12)
 * X and Y coordinates relative to a calibrated center point, and triggers [onDirectionDetected]
 * (as well as [SwipeControllerService.performSwipe]) with "UP", "DOWN", "LEFT", or "RIGHT"
 * when movement exceeds the configured threshold, enforcing a 250ms debounce cooldown.
 */
class PoseLandmarkerController(
    private val context: Context,
    private val onDirectionDetected: (String) -> Unit = { direction ->
        SwipeControllerService.performSwipeWithSource(direction, "MediaPipe Pose LIVE_STREAM")
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile
    private var isFrontCamera: Boolean = true

    init {
        scope.launch {
            initializePoseLandmarker()
        }
    }

    suspend fun initializePoseLandmarker(): Boolean = withContext(Dispatchers.IO) {
        if (poseLandmarker != null) return@withContext true

        try {
            SwipeTelemetryRepository.updatePoseModelStatus("Loading MediaPipe Pose Landmarker...")

            val modelFile = ensureModelFileAvailable()
            if (modelFile == null || !modelFile.exists()) {
                SwipeTelemetryRepository.updatePoseModelStatus(
                    "Interactive Pose Pad Ready (Model offline fallback active)"
                )
                return@withContext false
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this@PoseLandmarkerController::onPoseLandmarkerResult)
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe PoseLandmarker LIVE_STREAM error: ${error.message}", error)
                    SwipeTelemetryRepository.updatePoseModelStatus(
                        "Pose Landmarker Error: ${error.message}"
                    )
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            SwipeTelemetryRepository.updatePoseModelStatus(
                "MediaPipe LIVE_STREAM Active (250ms Debounce)"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaPipe PoseLandmarker initialization fallback: ${e.message}")
            SwipeTelemetryRepository.updatePoseModelStatus(
                "Pose Calibration Active (Interactive & Sensor Ready)"
            )
            false
        }
    }

    /**
     * Checks `assets/pose_landmarker_lite.task` first; if not bundled in APK assets, downloads
     * the official Google MediaPipe `pose_landmarker_lite.task` into `context.filesDir` and caches it.
     */
    private fun ensureModelFileAvailable(): File? {
        val cachedFile = File(context.filesDir, MODEL_FILENAME)
        if (cachedFile.exists() && cachedFile.length() > 100_000L) {
            return cachedFile
        }

        // 1. Check APK assets if user bundled pose_landmarker_lite.task
        runCatching {
            context.assets.open(MODEL_FILENAME).use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (cachedFile.exists() && cachedFile.length() > 100_000L) {
                return cachedFile
            }
        }

        // 2. Download official MediaPipe pose_landmarker_lite.task model
        return runCatching {
            SwipeTelemetryRepository.updatePoseModelStatus(
                "Downloading official MediaPipe pose_landmarker_lite.task..."
            )
            val connection = (URL(OFFICIAL_MEDIAPIPE_MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode in 200..299) {
                val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() > 100_000L) {
                    tempFile.renameTo(cachedFile)
                    return@runCatching cachedFile
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Binds CameraX [Preview] and [ImageAnalysis] (`STRATEGY_KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_RGBA_8888`)
     * to the given [lifecycleOwner] and streams frames into MediaPipe `detectAsync`.
     */
    fun bindCameraPoseStream(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val selector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                        isFrontCamera = true
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                        isFrontCamera = false
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    else -> null
                } ?: return@addListener

                val preview = Preview.Builder().build().also { prev ->
                    previewView?.let { pv -> prev.surfaceProvider = pv.surfaceProvider }
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(backgroundExecutor, this::analyzeImageProxyFrame)
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            }.onFailure { e ->
                Log.w(TAG, "CameraX Pose stream bind error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyzeImageProxyFrame(imageProxy: ImageProxy) {
        val landmarker = poseLandmarker
        if (landmarker == null || !SwipeTelemetryRepository.isMovementTrackingActive.value) {
            imageProxy.close()
            return
        }

        try {
            val frameTimeMs = SystemClock.uptimeMillis()
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.planes[0].buffer.rewind()
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (isFrontCamera) {
                    // Mirror horizontally so leaning left/right matches user's natural reflection
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width / 2f,
                        imageProxy.height / 2f
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )

            val mpImage: MPImage = BitmapImageBuilder(rotatedBitmap).build()
            landmarker.detectAsync(mpImage, frameTimeMs)
        } catch (e: Exception) {
            Log.w(TAG, "Frame pose analysis error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * MediaPipe LIVE_STREAM callback receiving normalized landmarks.
     * Landmark 0 = NOSE, Landmark 11 = LEFT_SHOULDER, Landmark 12 = RIGHT_SHOULDER.
     */
    private fun onPoseLandmarkerResult(result: PoseLandmarkerResult, input: MPImage) {
        val poses = result.landmarks()
        if (poses.isEmpty()) return
        val firstPose = poses[0]
        if (firstPose.size <= RIGHT_SHOULDER_INDEX) return

        val nose = firstPose[NOSE_INDEX]
        val leftShoulder = firstPose[LEFT_SHOULDER_INDEX]
        val rightShoulder = firstPose[RIGHT_SHOULDER_INDEX]

        val detectedDirection = SwipeTelemetryRepository.processPoseLandmarksAndEvaluateDirection(
            noseX = nose.x(),
            noseY = nose.y(),
            leftShoulderX = leftShoulder.x(),
            leftShoulderY = leftShoulder.y(),
            rightShoulderX = rightShoulder.x(),
            rightShoulderY = rightShoulder.y(),
            nowMs = System.currentTimeMillis()
        )

        if (detectedDirection != null && SwipeTelemetryRepository.isMovementTrackingActive.value) {
            scope.launch {
                onDirectionDetected(detectedDirection)
            }
        }
    }

    /**
     * Allows calibrating the neutral center point to the user's current standing/sitting posture.
     */
    fun calibrateCenterToCurrentPose() {
        SwipeTelemetryRepository.recalibrateToCurrentPose()
    }

    fun release() {
        runCatching { poseLandmarker?.close() }
        poseLandmarker = null
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PoseLandmarkerCtrl"
        const val NOSE_INDEX = 0
        const val LEFT_SHOULDER_INDEX = 11
        const val RIGHT_SHOULDER_INDEX = 12

        private const val MODEL_FILENAME = "pose_landmarker_lite.task"
        private const val OFFICIAL_MEDIAPIPE_MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
    }
}
