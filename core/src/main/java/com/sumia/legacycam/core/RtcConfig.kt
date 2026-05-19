package com.sumia.legacycam.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class RtcIceServerConfig(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class RtcConfigResponse(
    @SerialName("ice_servers") val iceServers: List<RtcIceServerConfig> = emptyList(),
)

object RtcConfigDefaults {
    val iceServers: List<RtcIceServerConfig> = listOf(
        RtcIceServerConfig(urls = listOf("stun:stun.l.google.com:19302")),
        RtcIceServerConfig(urls = listOf("stun:stun1.l.google.com:19302")),
    )
}

object RtcConfigFetcher {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(serverUrl: String): List<RtcIceServerConfig> {
        val endpoint = buildRtcConfigUrl(serverUrl)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RtcConfigDefaults.iceServers
                }

                val body = response.body?.string().orEmpty()
                val payload = json.decodeFromString<RtcConfigResponse>(body)
                payload.iceServers.filter { it.urls.isNotEmpty() }.ifEmpty { RtcConfigDefaults.iceServers }
            }
        }.getOrDefault(RtcConfigDefaults.iceServers)
    }

    private fun buildRtcConfigUrl(serverUrl: String): String {
        return when {
            serverUrl.startsWith("wss://") -> serverUrl.replaceFirst("wss://", "https://")
            serverUrl.startsWith("ws://") -> serverUrl.replaceFirst("ws://", "http://")
            else -> serverUrl
        }.replace(Regex("/ws/?$"), "/api/rtc-config")
    }
}
