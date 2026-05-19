package com.sumia.legacycam

data class CameraServiceState(
    val isRunning: Boolean = false,
    val token: String = "",
    val serverUrl: String = "",
    val status: String = "Mode kamera belum aktif.",
    val errorMessage: String? = null,
)
