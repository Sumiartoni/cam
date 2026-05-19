package com.sumia.legacycam.viewer

import com.sumia.legacycam.core.ConnectedDevice

data class ViewerState(
    val token: String = "",
    val serverUrl: String = "",
    val devices: List<ConnectedDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val status: String = "",
    val errorMessage: String? = null,
    val isRunning: Boolean = false,
)
