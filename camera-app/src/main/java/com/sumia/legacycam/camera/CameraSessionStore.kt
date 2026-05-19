package com.sumia.legacycam.camera

import android.content.Context

data class SavedCameraSession(
    val serverUrl: String,
    val token: String,
    val deviceId: String,
)

object CameraSessionStore {
    private const val PREFS_NAME = "ant_vrs_camera_session"
    private const val KEY_ACTIVE = "active"
    private const val KEY_BOUND = "bound"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"

    fun saveBinding(context: Context, serverUrl: String, token: String) {
        val deviceId = getOrCreateDeviceId(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BOUND, true)
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun markActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
    }

    fun loadBound(context: Context): SavedCameraSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_BOUND, false)) return null

        val serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        val deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (serverUrl.isBlank() || token.isBlank() || deviceId.isBlank()) return null

        return SavedCameraSession(serverUrl = serverUrl, token = token, deviceId = deviceId)
    }

    fun loadActive(context: Context): SavedCameraSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        return loadBound(context)
    }

    fun isMarkedActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
    }

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) {
            return existing
        }

        val generated = java.util.UUID.randomUUID().toString().replace("-", "").takeLast(8).uppercase()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
