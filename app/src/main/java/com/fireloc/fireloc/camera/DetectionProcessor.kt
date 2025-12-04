package com.fireloc.fireloc.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.fireloc.fireloc.model.YoloModel
import com.fireloc.fireloc.utils.ImageUtils
import com.fireloc.fireloc.utils.SensorFusionManager
import com.fireloc.fireloc.network.CloudVerificationService
import com.fireloc.fireloc.network.DetectRequest
import com.fireloc.fireloc.network.LocationData
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DetectionProcessor(
    private val context: Context,
    private val sensorFusionManager: SensorFusionManager,
    private val cameraManager: CameraManager,
    private val cloudVerificationService: CloudVerificationService,
    private val deviceId: String,
    private val onPotentialDetection: (
        detections: List<Detection>,
        sourceBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int
    ) -> Unit
) {
    companion object {
        private const val TAG = "FireLocDetectionProc"
        private const val DETECTION_THRESHOLD = 0.40f
    }

    private val model: YoloModel = YoloModel(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var lastProcessingTimeMs: Long = 0
    private var frameCounter = 0
    private val skipFrames = 4
    private val isProcessing = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("UnsafeOptInUsageError")
    fun processImageProxy(imageProxy: ImageProxy) {
        frameCounter++
        if (frameCounter % (skipFrames + 1) != 0) {
            imageProxy.close(); return
        }
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close(); return
        }

        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height

        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        imageProxy.close()

        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to bitmap for frame $frameCounter")
            isProcessing.set(false)
            Handler(Looper.getMainLooper()).post {
                onPotentialDetection(emptyList(), null, imageWidth, imageHeight)
            }
            return
        }

        executor.execute {
            val inferenceStartTime = SystemClock.elapsedRealtime()
            var localDetections: List<Detection> = emptyList()
            var bitmapToPass: Bitmap? = null

            try {
                val yoloDetections = model.detect(bitmap)
                localDetections = filterDetections(yoloDetections)

                lastProcessingTimeMs = SystemClock.elapsedRealtime() - inferenceStartTime

                if (localDetections.isNotEmpty()) {
                    bitmapToPass = bitmap
                    Log.d(TAG, "Local detection found. Triggering cloud verification for localization.")
                    triggerCloudVerification(bitmap)
                }

                Handler(Looper.getMainLooper()).post {
                    onPotentialDetection(localDetections, bitmapToPass, imageWidth, imageHeight)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during model detection/processing frame $frameCounter", e)
                Handler(Looper.getMainLooper()).post {
                    onPotentialDetection(emptyList(), null, imageWidth, imageHeight)
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun triggerCloudVerification(bitmap: Bitmap) {
        val location = sensorFusionManager.lastKnownLocation.get()
        val (heading, pitch) = sensorFusionManager.lastKnownOrientation.get()

        if (location == null) {
            Log.e(TAG, "Cannot send detection: Location (GPS) data is missing.")
            return
        }

        Log.d(TAG, "Payload Data READY: Lat=${location.latitude}, Heading=$heading, VFOV=${cameraManager.verticalFov}")

        // 1. Encode Bitmap to Base64 String
        val imageBase64: String = ImageUtils.bitmapToBase64(bitmap) ?: run {
            Log.e(TAG, "Failed to encode image to Base64. Aborting cloud verification.")
            return
        }

        // 2. Build the Location Data object
        val locationData = LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            heading = heading,
            pitch = pitch,
            verticalFov = cameraManager.verticalFov,
            horizontalFov = cameraManager.horizontalFov
        )

        // 3. Build the final request object
        val requestData = DetectRequest(
            deviceId = deviceId,
            imageBase64 = imageBase64, // <-- FIX 1: Changed to Kotlin parameter name
            timestampMs = System.currentTimeMillis(), // <-- FIX 2: Changed to Kotlin parameter name
            location = locationData,
            mobileDetected = true // <-- FIX 3: Changed to Kotlin parameter name
        )

        // 4. Send the authenticated request asynchronously
        scope.launch {
            val response = cloudVerificationService.sendDetectionRequest(requestData)

            if (response != null && response.detected == true) { // Changed to use safe call for Boolean?
                Log.i(TAG, "Cloud confirmed fire detected. Confidence: ${response.results?.firstOrNull()?.confidence}")
            } else if (response != null) {
                Log.d(TAG, "Cloud verification complete, no fire confirmed by cloud model.")
            } else {
                Log.w(TAG, "Cloud verification failed due to token or network error.")
            }
        }
    }


    private fun filterDetections(rawDetections: List<YoloModel.YoloDetection>): List<Detection> {
        return rawDetections.filter{it.confidence >= DETECTION_THRESHOLD}.mapNotNull{yoloDetection->val isValidBox=yoloDetection.left in 0.0f..1.0f&&yoloDetection.top in 0.0f..1.0f&&yoloDetection.right in 0.0f..1.0f&&yoloDetection.bottom in 0.0f..1.0f&&yoloDetection.left<yoloDetection.right&&yoloDetection.top<yoloDetection.bottom;if(!isValidBox){Log.w(TAG,"Skipping invalid norm coords: $yoloDetection");return@mapNotNull null};val type:FireDetectionType?=when(yoloDetection.classId){1->FireDetectionType.FIRE;0->FireDetectionType.SMOKE;else->null};type?.let{Detection(type=it,confidence=yoloDetection.confidence,boundingBox=RectF(yoloDetection.left,yoloDetection.top,yoloDetection.right,yoloDetection.bottom))}}
    }

    fun release() {
        try {
            scope.cancel()
            if (!executor.isShutdown) executor.shutdown()
            model.close()
            sensorFusionManager.stopListening()
            Log.d(TAG, "DetectionProcessor resources released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing DetectionProcessor resources: ${e.message}")
        }
    }

    fun getLastProcessingTimeMs(): Long {
        return lastProcessingTimeMs
    }
}