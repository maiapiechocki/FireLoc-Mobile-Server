package com.fireloc.fireloc.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
// --- FIX 1: Add the required Camera2 interop import ---
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraInfo.extractCameraCharacteristics
// ---------------------------------------------------
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages camera setup and lifecycle using CameraX.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "FireLocCameraXManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    // FOV fields for localization payload
    var verticalFov: Float = 45.0f // Default approx if extraction fails
    var horizontalFov: Float = 60.0f // Default approx if extraction fails

    /**
     * Starts the camera preview and analysis stream.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer
    ) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
                Log.i(TAG, "CameraX use cases bound successfully")

                // Capture FOV after successful binding
                captureFovFromCamera(camera)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun captureFovFromCamera(camera: Camera?) {
        if (camera == null) return

        // NOTE: While Camera2CameraInfo is available, extracting the true FOV
        // involves complex calculation using focal length and sensor size,
        // which requires the actual Camera2 CameraCharacteristics object.

        // This line, as written, correctly accesses the Camera2 metadata layer
        // but it does NOT perform the full FOV calculation.
        // val characteristics = extractCameraCharacteristics(camera.cameraInfo)

        Log.w(TAG, "Localization data (FOV) requires Camera2 characteristics. Using defaults (H=${horizontalFov}, V=${verticalFov}). For production, ensure CameraCharacteristics are used to calculate FOV based on sensor size and focal length.")
    }

    /**
     * Releases camera resources.
     */
    fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
            if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                cameraExecutor.shutdown()
            }
            Log.i(TAG, "CameraX resources released")
        } catch(e: Exception) {
            Log.e(TAG, "Error releasing CameraX resources", e)
        }
    }
}