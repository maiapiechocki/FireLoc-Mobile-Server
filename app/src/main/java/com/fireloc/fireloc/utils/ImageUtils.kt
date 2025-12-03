package com.fireloc.fireloc.utils // Ensure this package name is correct

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.util.Base64 // Android's Base64
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val BASE64_JPEG_QUALITY = 75 // Quality for Base64 encoding

    @SuppressLint("UnsafeOptInUsageError")
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        // ... (imageProxyToBitmap function is unchanged) ...
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) { Log.e(TAG, "Unsupported image format: ${image.format}. Expected YUV_420_888."); return null }
        return try {
            val nv21Bytes = yuv420ThreePlanesToNV21(image.planes, imageProxy.width, imageProxy.height) ?: return null.also { Log.e(TAG, "NV21 conversion failed.") }
            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, out) // High quality for intermediate
            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null.also { Log.e(TAG, "BitmapFactory decode failed.") }
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix(); matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it != bitmap) bitmap.recycle() }
            } else { bitmap }
        } catch (e: Exception) { Log.e(TAG, "Error converting YUV to bitmap: ${e.message}"); null }
    }

    private fun yuv420ThreePlanesToNV21(planes: Array<Image.Plane>, width: Int, height: Int): ByteArray? {
        // ... (yuv420ThreePlanesToNV21 function is unchanged) ...
        val imageSize = width * height; val nv21Bytes = ByteArray(imageSize + 2 * (imageSize / 4)); val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]; val yBuffer: ByteBuffer = yPlane.buffer; val uBuffer: ByteBuffer = uPlane.buffer; val vBuffer: ByteBuffer = vPlane.buffer; val yRowStride = yPlane.rowStride; val uRowStride = uPlane.rowStride; val vRowStride = vPlane.rowStride; val uPixelStride = uPlane.pixelStride; val vPixelStride = vPlane.pixelStride; var yOffset = 0; if (yRowStride == width) { yBuffer.get(nv21Bytes, 0, imageSize); yOffset = imageSize } else { for (row in 0 until height) { yBuffer.position(row * yRowStride); yBuffer.get(nv21Bytes, yOffset, width); yOffset += width } }; var vuOffset = yOffset; val vBytes = ByteArray(vBuffer.remaining()); vBuffer.get(vBytes); val uBytes = ByteArray(uBuffer.remaining()); uBuffer.get(uBytes); val vuHeight = height / 2; val vuWidth = width / 2; if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == uRowStride) { for (row in 0 until vuHeight) { val vRowOffset = row * vRowStride; val uRowOffset = row * uRowStride; for (col in 0 until vuWidth) { val vIndex = vRowOffset + col * vPixelStride; val uIndex = uRowOffset + col * uPixelStride; if (vIndex < vBytes.size && uIndex < uBytes.size && vuOffset + 1 < nv21Bytes.size) { nv21Bytes[vuOffset++] = vBytes[vIndex]; nv21Bytes[vuOffset++] = uBytes[uIndex] } else { Log.w(TAG, "VU Interleaving bounds issue (semi-planar)"); break } }; if(vuOffset >= nv21Bytes.size) break } } else if (vPixelStride == 1 && uPixelStride == 1) { for (row in 0 until vuHeight) { val uRowStart = row * uRowStride; val vRowStart = row * vRowStride; for (col in 0 until vuWidth) { val uIndex = uRowStart + col; val vIndex = vRowStart + col; if (vIndex < vBytes.size && uIndex < uBytes.size && vuOffset + 1 < nv21Bytes.size) { nv21Bytes[vuOffset++] = vBytes[vIndex]; nv21Bytes[vuOffset++] = uBytes[uIndex] } else { Log.w(TAG, "VU Interleaving bounds issue (planar)"); break } }; if(vuOffset >= nv21Bytes.size) break } } else { Log.e(TAG, "Unsupported YUV plane config: V stride $vPixelStride, U stride $uPixelStride"); return null }; return nv21Bytes
    }

    // **** ADDED: Function to encode Bitmap to Base64 JPEG ****
    /**
     * Compresses a Bitmap to JPEG and encodes it as a Base64 string.
     * Returns null if the bitmap is null or compression/encoding fails.
     */
    fun bitmapToBase64(bitmap: Bitmap?, quality: Int = BASE64_JPEG_QUALITY): String? {
        if (bitmap == null) return null
        var base64String: String? = null
        ByteArrayOutputStream().use { outputStream -> // Use 'use' for auto-closing
            try {
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
                    val imageBytes = outputStream.toByteArray()
                    base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                } else {
                    Log.e(TAG, "Bitmap.compress returned false.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error encoding bitmap to Base64", e)
            }
        }
        return base64String
    }
}