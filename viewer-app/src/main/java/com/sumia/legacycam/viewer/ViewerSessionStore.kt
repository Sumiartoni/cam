package com.sumia.legacycam.viewer

import android.content.Context
import com.sumia.legacycam.core.TokenGenerator

data class ViewerSession(
    val token: String,
    val serverUrl: String,
    val isActive: Boolean,
)

object ViewerSessionStore {
    private const val PREFS_NAME = "ant_vrs_viewer_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_ACTIVE = "active"

    fun loadSession(context: Context, defaultServerUrl: String): ViewerSession {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().ifBlank {
            val generated = TokenGenerator.create()
            prefs.edit().putString(KEY_TOKEN, generated).apply()
            generated
        }
        val serverUrl = prefs.getString(KEY_SERVER_URL, defaultServerUrl).orEmpty().ifBlank { defaultServerUrl }
        val isActive = prefs.getBoolean(KEY_ACTIVE, false)
        return ViewerSession(token = token, serverUrl = serverUrl, isActive = isActive)
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun saveServerUrl(context: Context, serverUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .apply()
    }

    fun markActive(context: Context, isActive: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, isActive)
            .apply()
    }
}
