package com.sumia.legacycam.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectedDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_label") val deviceLabel: String,
)

@Serializable
data class SignalingMessage(
    val type: String,
    val token: String? = null,
    val role: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    val sdp: String? = null,
    @SerialName("sdp_type") val sdpType: String? = null,
    val candidate: String? = null,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    val reason: String? = null,
    val devices: List<ConnectedDevice> = emptyList(),
)
