package com.fireloc.fireloc.utils

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.getSystemService
import java.util.concurrent.atomic.AtomicReference

/**
 * Fusion Manager to collect Location (Lat/Lon/Alt) and Orientation (Heading/Pitch).
 */
class SensorFusionManager(private val context: Context) : SensorEventListener {

    private val TAG = "SensorFusionManager"
    private val sensorManager: SensorManager? = context.getSystemService()
    private val locationManager: LocationManager? = context.getSystemService()

    // --- State Storage ---
    // Stores the latest location data
    val lastKnownLocation: AtomicReference<Location?> = AtomicReference(null)
    // Stores the latest device orientation in degrees
    val lastKnownOrientation: AtomicReference<Pair<Float, Float>> = AtomicReference(Pair(0f, 0f)) // <heading, pitch>

    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // --- Location Listener ---
    private val locationListener = LocationListener { location ->
        lastKnownLocation.set(location)
        Log.d(TAG, "Location Updated: Lat=${location.latitude}, Alt=${location.altitude}")
    }

    @SuppressLint("MissingPermission") // Permissions must be handled in Activity/Fragment
    fun startListening() {
        // 1. Start Location Updates (Coarse/Fine location permission needed)
        try {
            // Request updates from the best provider (GPS or Network)
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L, // 2 second update interval
                5f,    // 5 meter minimal distance change
                locationListener
            )
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                2000L,
                5f,
                locationListener
            )
            // Get last known location immediately
            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnownLocation.set(lastGps ?: lastNet)

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied or missing.", e)
        }

        // 2. Start Sensor Updates (Accelerometer and Magnetic Field for Orientation)
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        Log.i(TAG, "Sensor listeners started.")
    }

    fun stopListening() {
        locationManager?.removeUpdates(locationListener)
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "Sensor and location listeners stopped.")
    }

    // --- Sensor Event Implementation ---
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetic, 0, event.values.size)
        }
        updateOrientation()
    }

    private fun updateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {
            // Get the orientation angles from the rotation matrix
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // orientationAngles[0] (Azimuth/Yaw) is the Heading (0 to 360, relative to True North)
            // Convert radians to degrees and normalize to 0-360 range
            val heading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat().let {
                (it + 360) % 360 // Normalize to 0-360
            }

            // orientationAngles[1] (Pitch) is the rotation around the X-axis (-90 to 90)
            val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

            lastKnownOrientation.set(Pair(heading, pitch))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used, but required by SensorEventListener
    }
}