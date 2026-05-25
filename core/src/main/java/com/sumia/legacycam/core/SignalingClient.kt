package com.sumia.legacycam.core

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val token: String,
    private val role: AppRole,
    private val deviceId: String? = null,
    private val deviceLabel: String? = null,
    private val listener: Listener,
) {
    enum class ConnectionState {
        CONNECTING,
        OPEN,
        CLOSED,
    }

    interface Listener {
        fun onSocketOpen()
        fun onRegistered(deviceId: String?)
        fun onPeerReady(deviceId: String?)
        fun onDeviceList(devices: List<ConnectedDevice>, selectedDeviceId: String?)
        fun onGalleryListRequest() = Unit
        fun onGalleryList(deviceId: String?, items: List<GalleryItemPayload>, batchIndex: Int, batchCount: Int) = Unit
        fun onGalleryListComplete(deviceId: String?) = Unit
        fun onGalleryItemRequest(requestId: String, mediaId: String) = Unit
        fun onGalleryItemMeta(requestId: String, deviceId: String?, item: GalleryItemPayload, chunkCount: Int) = Unit
        fun onGalleryItemChunk(requestId: String, chunkIndex: Int, chunkCount: Int, payloadBase64: String) = Unit
        fun onGalleryItemComplete(requestId: String, mediaId: String) = Unit
        fun onSwitchCamera()
        fun onToggleFlash() = Unit
        fun onFlashState(deviceId: String?, enabled: Boolean) = Unit
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onPeerLeft(deviceId: String?)
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    @Volatile
    private var connectionState: ConnectionState = ConnectionState.CLOSED

    fun isConnected(): Boolean = connectionState == ConnectionState.OPEN

    fun connect() {
        connectionState = ConnectionState.CONNECTING
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connectionState = ConnectionState.OPEN
                    listener.onSocketOpen()
                    send(
                        SignalingMessage(
                            type = "register",
                            token = token,
                            role = role.name.lowercase(),
                            deviceId = deviceId,
                            deviceLabel = deviceLabel,
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncoming(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connectionState = ConnectionState.CLOSED
                    listener.onClosed(reason.ifBlank { "Koneksi ditutup." })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connectionState = ConnectionState.CLOSED
                    listener.onClosed(t.message ?: "Koneksi signaling gagal.")
                }
            },
        )
    }

    fun disconnect() {
        connectionState = ConnectionState.CLOSED
        socket?.close(1000, "session-ended")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    fun sendOffer(sdp: String) {
        send(SignalingMessage(type = "offer", token = token, sdp = sdp, sdpType = "offer"))
    }

    fun sendAnswer(sdp: String) {
        send(SignalingMessage(type = "answer", token = token, sdp = sdp, sdpType = "answer"))
    }

    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        send(
            SignalingMessage(
                type = "ice",
                token = token,
                candidate = candidate,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex,
            ),
        )
    }

    fun selectCamera(targetDeviceId: String) {
        send(
            SignalingMessage(
                type = "select-camera",
                token = token,
                targetDeviceId = targetDeviceId,
            ),
        )
    }

    fun requestGalleryList() {
        send(SignalingMessage(type = "gallery-list-request", token = token))
    }

    fun sendGalleryList(items: List<GalleryItemPayload>, batchIndex: Int, batchCount: Int) {
        send(
            SignalingMessage(
                type = "gallery-list",
                token = token,
                deviceId = deviceId,
                batchIndex = batchIndex,
                batchCount = batchCount,
                galleryItems = items,
            ),
        )
    }

    fun sendGalleryListComplete() {
        send(
            SignalingMessage(
                type = "gallery-list-complete",
                token = token,
                deviceId = deviceId,
            ),
        )
    }

    fun requestGalleryItem(requestId: String, mediaId: String) {
        send(
            SignalingMessage(
                type = "gallery-item-request",
                token = token,
                requestId = requestId,
                mediaId = mediaId,
            ),
        )
    }

    fun sendGalleryItemMeta(requestId: String, item: GalleryItemPayload, chunkCount: Int) {
        send(
            SignalingMessage(
                type = "gallery-item-meta",
                token = token,
                deviceId = deviceId,
                requestId = requestId,
                mediaId = item.mediaId,
                mediaType = item.mediaType,
                title = item.title,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                chunkCount = chunkCount,
                galleryItem = item,
            ),
        )
    }

    fun sendGalleryItemChunk(requestId: String, chunkIndex: Int, chunkCount: Int, payloadBase64: String) {
        send(
            SignalingMessage(
                type = "gallery-item-chunk",
                token = token,
                deviceId = deviceId,
                requestId = requestId,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                payloadBase64 = payloadBase64,
            ),
        )
    }

    fun sendGalleryItemComplete(requestId: String, mediaId: String) {
        send(
            SignalingMessage(
                type = "gallery-item-complete",
                token = token,
                deviceId = deviceId,
                requestId = requestId,
                mediaId = mediaId,
            ),
        )
    }

    fun sendError(reason: String) {
        send(SignalingMessage(type = "error", token = token, reason = reason))
    }

    fun sendSwitchCamera() {
        send(SignalingMessage(type = "switch-camera", token = token))
    }

    fun sendToggleFlash() {
        send(SignalingMessage(type = "toggle-flash", token = token))
    }

    fun sendFlashState(enabled: Boolean) {
        send(
            SignalingMessage(
                type = "flash-state",
                token = token,
                deviceId = deviceId,
                enabled = enabled,
            ),
        )
    }

    private fun handleIncoming(payload: String) {
        val message = runCatching { json.decodeFromString<SignalingMessage>(payload) }
            .getOrElse {
                listener.onError("Payload signaling tidak valid.")
                return
            }

        when (message.type) {
            "registered" -> listener.onRegistered(message.deviceId)
            "peer-ready" -> listener.onPeerReady(message.deviceId)
            "device-list" -> listener.onDeviceList(message.devices, message.targetDeviceId)
            "gallery-list-request" -> listener.onGalleryListRequest()
            "gallery-list" -> listener.onGalleryList(message.deviceId, message.galleryItems, message.batchIndex ?: 0, message.batchCount ?: 1)
            "gallery-list-complete" -> listener.onGalleryListComplete(message.deviceId)
            "gallery-item-request" -> {
                val requestId = message.requestId
                val mediaId = message.mediaId
                if (!requestId.isNullOrBlank() && !mediaId.isNullOrBlank()) {
                    listener.onGalleryItemRequest(requestId, mediaId)
                }
            }
            "gallery-item-meta" -> {
                val requestId = message.requestId
                val galleryItem = message.galleryItem
                val chunkCount = message.chunkCount
                if (!requestId.isNullOrBlank() && galleryItem != null && chunkCount != null) {
                    listener.onGalleryItemMeta(requestId, message.deviceId, galleryItem, chunkCount)
                }
            }
            "gallery-item-chunk" -> {
                val requestId = message.requestId
                val chunkIndex = message.chunkIndex
                val chunkCount = message.chunkCount
                val payloadBase64 = message.payloadBase64
                if (!requestId.isNullOrBlank() && chunkIndex != null && chunkCount != null && !payloadBase64.isNullOrBlank()) {
                    listener.onGalleryItemChunk(requestId, chunkIndex, chunkCount, payloadBase64)
                }
            }
            "gallery-item-complete" -> {
                val requestId = message.requestId
                val mediaId = message.mediaId
                if (!requestId.isNullOrBlank() && !mediaId.isNullOrBlank()) {
                    listener.onGalleryItemComplete(requestId, mediaId)
                }
            }
            "switch-camera" -> listener.onSwitchCamera()
            "toggle-flash" -> listener.onToggleFlash()
            "flash-state" -> {
                val enabled = message.enabled
                if (enabled != null) {
                    listener.onFlashState(message.deviceId, enabled)
                }
            }
            "offer" -> listener.onOffer(message.sdp.orEmpty())
            "answer" -> listener.onAnswer(message.sdp.orEmpty())
            "ice" -> {
                val candidate = message.candidate
                val index = message.sdpMLineIndex
                if (candidate != null && index != null) {
                    listener.onIceCandidate(candidate, message.sdpMid, index)
                }
            }
            "peer-left" -> listener.onPeerLeft(message.deviceId)
            "error" -> listener.onError(message.reason ?: "Terjadi error signaling.")
            else -> listener.onError("Pesan signaling tidak dikenali: ${message.type}")
        }
    }

    private fun send(message: SignalingMessage) {
        socket?.send(json.encodeToString(SignalingMessage.serializer(), message))
    }
}
