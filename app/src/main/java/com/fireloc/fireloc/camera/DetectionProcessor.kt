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
import com.fireloc.fireloc.utils.ImageUtils // Ensure correct import
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DetectionProcessor(
    private val context: Context,
    // **** MODIFIED CALLBACK SIGNATURE ****
    private val onPotentialDetection: (
        detections: List<Detection>, // Local detections (normalized coords)
        sourceBitmap: Bitmap?,      // Bitmap that triggered detection (null if no detection met criteria)
        imageWidth: Int,            // Original image width
        imageHeight: Int            // Original image height
    ) -> Unit
) {
    companion object {
        private const val TAG = "FireLocDetectionProc"
        private const val DETECTION_THRESHOLD = 0.40f // Local model threshold
    }

    private val model: YoloModel = YoloModel(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var lastProcessingTimeMs: Long = 0
    private var frameCounter = 0
    private val skipFrames = 4
    private val isProcessing = AtomicBoolean(false)

    @SuppressLint("UnsafeOptInUsageError")
    fun processImageProxy(imageProxy: ImageProxy) {
        frameCounter++
        if (frameCounter % (skipFrames + 1) != 0) {
            imageProxy.close(); return
        }
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close(); return
        }

        val startTime = SystemClock.elapsedRealtime()
        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height

        // Convert to Bitmap now, before passing to background thread
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        imageProxy.close() // Close proxy right after conversion

        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to bitmap for frame $frameCounter")
            isProcessing.set(false)
            // Post empty result (no bitmap)
            Handler(Looper.getMainLooper()).post {
                onPotentialDetection(emptyList(), null, imageWidth, imageHeight)
            }
            return
        }

        executor.execute {
            val inferenceStartTime = SystemClock.elapsedRealtime()
            var localDetections: List<Detection> = emptyList()
            var bitmapToPass: Bitmap? = null // Will hold bitmap only if needed

            try {
                val yoloDetections = model.detect(bitmap)
                localDetections = filterDetections(yoloDetections) // Filter local results

                val endTime = SystemClock.elapsedRealtime()
                lastProcessingTimeMs = endTime - inferenceStartTime
                // Optional: Log performance periodically
                if (frameCounter % ((skipFrames + 1) * 3) == 0) { Log.d(TAG, "Frame ${frameCounter}: Found ${localDetections.size} local detections. Inference Time: $lastProcessingTimeMs ms.") }

                // **** Decide if bitmap needs to be passed to the callback ****
                if (localDetections.isNotEmpty()) {
                    bitmapToPass = bitmap // Keep reference to pass to MainActivity
                    Log.d(TAG, "Local detection above threshold found. Passing bitmap.")
                } else {
                    // Bitmap not needed, consider recycling if safe
                    // if (!bitmap.isRecycled) bitmap.recycle() // Use with caution
                }

                // Post results (detections and potentially the bitmap) to main thread
                Handler(Looper.getMainLooper()).post {
                    onPotentialDetection(localDetections, bitmapToPass, imageWidth, imageHeight)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during model detection/processing frame $frameCounter", e)
                // Consider recycling bitmap on error if not passed
                // if (bitmapToPass == null && !bitmap.isRecycled) bitmap.recycle()
                Handler(Looper.getMainLooper()).post { // Post empty on error
                    onPotentialDetection(emptyList(), null, imageWidth, imageHeight)
                }
            } finally {
                isProcessing.set(false) // Release processing flag
            }
        }
    }

    // filterDetections remains the same...
    private fun filterDetections(rawDetections: List<YoloModel.YoloDetection>): List<Detection> { /* ... unchanged ... */ return rawDetections.filter{it.confidence >= DETECTION_THRESHOLD}.mapNotNull{yoloDetection->val isValidBox=yoloDetection.left in 0.0f..1.0f&&yoloDetection.top in 0.0f..1.0f&&yoloDetection.right in 0.0f..1.0f&&yoloDetection.bottom in 0.0f..1.0f&&yoloDetection.left<yoloDetection.right&&yoloDetection.top<yoloDetection.bottom;if(!isValidBox){Log.w(TAG,"Skipping invalid norm coords: $yoloDetection");return@mapNotNull null};val type:FireDetectionType?=when(yoloDetection.classId){1->FireDetectionType.FIRE;0->FireDetectionType.SMOKE;else->null};type?.let{Detection(type=it,confidence=yoloDetection.confidence,boundingBox=RectF(yoloDetection.left,yoloDetection.top,yoloDetection.right,yoloDetection.bottom))}} }

    // release remains the same...
    fun release() { /* ... unchanged ... */ try { if (!executor.isShutdown) executor.shutdown(); model.close(); Log.d(TAG, "DetectionProcessor resources released.") } catch (e: Exception) { Log.e(TAG, "Error releasing DetectionProcessor resources: ${e.message}") } }

    // getLastProcessingTimeMs remains the same...
    fun getLastProcessingTimeMs(): Long { /* ... unchanged ... */ return lastProcessingTimeMs }
}