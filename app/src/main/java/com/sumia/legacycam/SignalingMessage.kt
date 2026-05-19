package com.sumia.legacycam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignalingMessage(
    val type: String,
    val token: String? = null,
    val role: String? = null,
    val sdp: String? = null,
    @SerialName("sdp_type") val sdpType: String? = null,
    val candidate: String? = null,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    val reason: String? = null,
)
