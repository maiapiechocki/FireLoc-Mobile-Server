package com.fireloc.fireloc.network // Ensure this package name is correct

import com.google.gson.annotations.SerializedName

// --- Request Bodies ---

// For POST /registerDevice
data class DeviceRegistrationRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String? = null // Optional device name
)

// For POST /detect
data class DetectRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("image_base64") val imageBase64: String, // Matching your original naming
    @SerializedName("timestamp_ms") val timestampMs: Long,   // Matching your original naming
    @SerializedName("location") val location: LocationData?, // Use the LocationData class below
    @SerializedName("mobile_detected") val mobileDetected: Boolean // Matching your original naming
)

// --- Response Bodies ---

// For POST /registerDevice **** ADDED THIS CLASS ****
data class DeviceRegistrationResponse(
    @SerializedName("status") val status: String, // e.g., "success", "error"
    @SerializedName("message") val message: String? // Optional confirmation or error message
)

// For POST /detect
data class DetectResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("detected") val detected: Boolean?,
    @SerializedName("results") val results: List<DetectionResult>?,
    // Using "message" for consistency with DeviceRegistrationResponse, but keeping "error" if backend uses it
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String? // Keep if backend might return 'error' field
)

// --- Nested Data Classes ---

// Used within DetectRequest
data class LocationData(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float? = null // Optional accuracy in meters
)

// Used within DetectResponse (represents one detected object by cloud)
data class DetectionResult(
    @SerializedName("class_id") val classId: Int, // Matching your original naming
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("box_normalized") val boxNormalized: List<Float> // [l,t,r,b] matching your original naming
)