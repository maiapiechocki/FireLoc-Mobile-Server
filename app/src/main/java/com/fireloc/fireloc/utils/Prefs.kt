package com.fireloc.fireloc.utils // Make sure this package matches the directory

import android.content.Context
import java.util.UUID

object Prefs {

    private const val PREFS_NAME = "com.fireloc.fireloc.prefs"
    private const val KEY_DEVICE_ID = "device_unique_id"

    // Gets the unique device ID. Generates and saves one if it doesn't exist.
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }
}