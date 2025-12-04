package com.fireloc.fireloc.network

import com.google.gson.annotations.SerializedName

// --- Request Bodies ---

data class DeviceRegistrationRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String? = null
)

/**
 * Payload sent from the mobile device to the Firebase /detect cloud function.
 * Matches the structure consumed by detect.js.
 */
data class DetectRequest(
    @SerializedName("deviceId") val deviceId: String,
    // The image must be non-null and encoded.
    @SerializedName("image_base64") val imageBase64: String,
    @SerializedName("timestamp_ms") val timestampMs: Long,
    // LocationData must be provided for localization.
    @SerializedName("location") val location: LocationData,
    @SerializedName("mobile_detected") val mobileDetected: Boolean
)

// --- Response Bodies ---

data class DeviceRegistrationResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?
)

data class DetectResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("detected") val detected: Boolean?,
    @SerializedName("results") val results: List<DetectionResult>?,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
)

// --- Nested Data Classes ---

/**
 * Contains all the GPS, altitude, and camera orientation data required by the
 * backend (fireTri.js) for ray-casting localization.
 */
data class LocationData(
    // GPS Latitude and Longitude are mandatory for any location request.
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,

    // Altitude, Heading, Pitch, and FOV are collected by SensorFusionManager and CameraManager
    // and are mandatory for the localization algorithm (TopoMono/FireTri).
    @SerializedName("altitude") val altitude: Double,
    @SerializedName("heading") val heading: Float,
    @SerializedName("pitch") val pitch: Float,
    @SerializedName("verticalFov") val verticalFov: Float,
    @SerializedName("horizontalFov") val horizontalFov: Float,

    // Accuracy is optional sensor data, safe to keep as nullable.
    @SerializedName("accuracy") val accuracy: Float? = null
)

/**
 * Represents a single detection result returned by the cloud's ONNX model.
 */
data class DetectionResult(
    @SerializedName("class_id") val classId: Int,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("box_normalized") val boxNormalized: List<Float>
)

// NOTE: Removed legacy models (FireAlertData, LocationModel, MetadataModel) for simplicity.
// Re-add them if they are used by other API endpoints or internal components.