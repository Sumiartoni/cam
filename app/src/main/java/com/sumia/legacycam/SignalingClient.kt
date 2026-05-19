package com.sumia.legacycam

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
    private val listener: Listener,
) {
    interface Listener {
        fun onSocketOpen()
        fun onRegistered()
        fun onPeerReady()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onPeerLeft()
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onSocketOpen()
                    send(
                        SignalingMessage(
                            type = "register",
                            token = token,
                            role = role.name.lowercase(),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncoming(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(reason.ifBlank { "Koneksi ditutup." })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onError(t.message ?: "Koneksi signaling gagal.")
                }
            },
        )
    }

    fun disconnect() {
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

    private fun handleIncoming(payload: String) {
        val message = runCatching { json.decodeFromString<SignalingMessage>(payload) }
            .getOrElse {
                listener.onError("Payload signaling tidak valid.")
                return
            }

        when (message.type) {
            "registered" -> listener.onRegistered()
            "peer-ready" -> listener.onPeerReady()
            "offer" -> listener.onOffer(message.sdp.orEmpty())
            "answer" -> listener.onAnswer(message.sdp.orEmpty())
            "ice" -> {
                val candidate = message.candidate
                val index = message.sdpMLineIndex
                if (candidate != null && index != null) {
                    listener.onIceCandidate(candidate, message.sdpMid, index)
                }
            }
            "peer-left" -> listener.onPeerLeft()
            "error" -> listener.onError(message.reason ?: "Terjadi error signaling.")
            else -> listener.onError("Pesan signaling tidak dikenali: ${message.type}")
        }
    }

    private fun send(message: SignalingMessage) {
        socket?.send(json.encodeToString(SignalingMessage.serializer(), message))
    }
}
