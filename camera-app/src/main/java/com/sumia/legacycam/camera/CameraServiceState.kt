package com.sumia.legacycam.camera

data class CameraServiceState(
    val isRunning: Boolean = false,
    val token: String = "",
    val serverUrl: String = "",
    val status: String = "Device cam belum aktif.",
    val errorMessage: String? = null,
)
