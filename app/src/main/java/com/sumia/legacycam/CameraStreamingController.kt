package com.sumia.legacycam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

object CameraStreamingController {
    private val mutableState = MutableStateFlow(CameraServiceState())
    val state: StateFlow<CameraServiceState> = mutableState.asStateFlow()

    private var rtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private var currentSessionId: Long = 0

    fun start(context: Context, serverUrl: String, token: String) {
        currentSessionId += 1
        val sessionId = currentSessionId

        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null

        val appContext = context.applicationContext
        val manager = WebRtcManager(
            appContext,
            object : WebRtcManager.Listener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    if (!isCurrentSession(sessionId)) return
                    when (sessionDescription.type) {
                        SessionDescription.Type.OFFER -> {
                            signalingClient?.sendOffer(sessionDescription.description)
                            updateState { copy(status = "Offer video terkirim ke monitor.") }
                        }

                        SessionDescription.Type.ANSWER -> Unit
                        else -> Unit
                    }
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    if (!isCurrentSession(sessionId)) return
                    signalingClient?.sendIceCandidate(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                    )
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    if (!isCurrentSession(sessionId)) return
                    val status = when (state) {
                        PeerConnection.PeerConnectionState.NEW -> "Peer connection kamera dibuat."
                        PeerConnection.PeerConnectionState.CONNECTING -> "Kamera sedang menghubungkan stream."
                        PeerConnection.PeerConnectionState.CONNECTED -> "Kamera sedang menyiarkan video."
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Monitor terputus dari kamera."
                        PeerConnection.PeerConnectionState.CLOSED -> "Peer connection kamera ditutup."
                        PeerConnection.PeerConnectionState.FAILED -> "Peer connection kamera gagal."
                    }
                    updateState { copy(status = status) }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = message, status = "Terjadi masalah pada WebRTC kamera.") }
                }
            },
        )

        rtcManager = manager
        manager.start(AppRole.CAMERA)

        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            token = token,
            role = AppRole.CAMERA,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            errorMessage = null,
                            status = "Foreground service kamera aktif. WebSocket signaling terbuka.",
                        )
                    }
                }

                override fun onRegistered() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            status = "Kamera terdaftar pada room token $token.",
                        )
                    }
                }

                override fun onPeerReady() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            status = "Monitor ditemukan. Kamera membuat offer video.",
                        )
                    }
                    rtcManager?.createOffer()
                }

                override fun onOffer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = "Kamera tidak seharusnya menerima offer.", status = "Urutan signaling tidak valid.") }
                }

                override fun onAnswer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(status = "Answer diterima dari monitor.") }
                    rtcManager?.handleRemoteAnswer(sdp)
                }

                override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    if (!isCurrentSession(sessionId)) return
                    rtcManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerLeft() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            status = "Monitor terputus. Stream kamera dihentikan sampai pairing baru dimulai.",
                            errorMessage = "Monitor keluar dari room. Aktifkan ulang kamera setelah monitor membuat token baru.",
                        )
                    }
                    rtcManager?.endSession()
                }

                override fun onClosed(reason: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = false,
                            status = "Koneksi signaling kamera ditutup: $reason",
                        )
                    }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            errorMessage = message,
                            status = "Foreground service kamera mengalami masalah signaling.",
                        )
                    }
                }
            },
        ).also { it.connect() }

        updateState {
            copy(
                isRunning = true,
                token = token,
                serverUrl = serverUrl,
                errorMessage = null,
                status = "Foreground service kamera sedang memulai pairing dengan token $token.",
            )
        }
    }

    fun stop(reason: String = "Mode kamera dihentikan.") {
        currentSessionId += 1
        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null
        mutableState.value = CameraServiceState(status = reason)
    }

    fun attachPreviewRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.attachLocalRenderer(renderer)
    }

    fun detachPreviewRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.detachLocalRenderer(renderer)
    }

    private fun updateState(transform: CameraServiceState.() -> CameraServiceState) {
        mutableState.update(transform)
    }

    private fun isCurrentSession(sessionId: Long): Boolean {
        return sessionId == currentSessionId
    }
}
