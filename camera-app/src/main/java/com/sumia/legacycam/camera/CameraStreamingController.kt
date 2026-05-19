package com.sumia.legacycam.camera

import android.content.Context
import com.sumia.legacycam.core.AppRole
import com.sumia.legacycam.core.SignalingClient
import com.sumia.legacycam.core.WebRtcManager
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
    private var currentDeviceId: String = ""

    fun start(context: Context, serverUrl: String, token: String, deviceId: String) {
        currentSessionId += 1
        val sessionId = currentSessionId
        currentDeviceId = deviceId

        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null

        val manager = WebRtcManager(
            context.applicationContext,
            object : WebRtcManager.Listener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    if (!isCurrentSession(sessionId)) return
                    if (sessionDescription.type == SessionDescription.Type.OFFER) {
                        signalingClient?.sendOffer(sessionDescription.description)
                        updateState { copy(status = "Cam mengirim offer video ke viewer.") }
                    }
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    if (!isCurrentSession(sessionId)) return
                    signalingClient?.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    if (!isCurrentSession(sessionId)) return
                    val status = when (state) {
                        PeerConnection.PeerConnectionState.NEW -> "Mesin WebRTC camera siap."
                        PeerConnection.PeerConnectionState.CONNECTING -> "Camera sedang membangun jalur stream."
                        PeerConnection.PeerConnectionState.CONNECTED -> "Camera sedang menyiarkan video."
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Viewer terputus dari camera."
                        PeerConnection.PeerConnectionState.CLOSED -> "Sesi camera ditutup."
                        PeerConnection.PeerConnectionState.FAILED -> "Camera gagal membuat peer connection."
                    }
                    updateState { copy(status = status) }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = message, status = "WebRTC camera mengalami masalah.") }
                }
            },
        )

        rtcManager = manager
        manager.start(AppRole.CAMERA)

        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            token = token,
            role = AppRole.CAMERA,
            deviceId = deviceId,
            deviceLabel = "Device $deviceId",
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            errorMessage = null,
                            status = "Device cam aktif. Koneksi signaling terbuka.",
                        )
                    }
                }

                override fun onRegistered(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            status = "Device cam standby di token $token.",
                        )
                    }
                }

                override fun onPeerReady(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            status = "Viewer ditemukan. Camera membuat offer video.",
                        )
                    }
                    rtcManager?.createOffer()
                }

                override fun onDeviceList(devices: List<com.sumia.legacycam.core.ConnectedDevice>, selectedDeviceId: String?) = Unit

                override fun onSwitchCamera() {
                    if (!isCurrentSession(sessionId)) return
                    rtcManager?.switchCamera()
                    updateState { copy(status = "ant Vrs sedang memindahkan sisi kamera.") }
                }

                override fun onOffer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = "Camera tidak boleh menerima offer.", status = "Urutan signaling camera tidak valid.") }
                }

                override fun onAnswer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(status = "Answer diterima dari viewer.") }
                    rtcManager?.handleRemoteAnswer(sdp)
                }

                override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    if (!isCurrentSession(sessionId)) return
                    rtcManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerLeft(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            status = "Viewer keluar. Device cam standby menunggu viewer kembali.",
                            errorMessage = null,
                        )
                    }
                    rtcManager?.endSession()
                    rtcManager?.start(AppRole.CAMERA)
                }

                override fun onClosed(reason: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(isRunning = false, status = "Koneksi signaling camera ditutup: $reason") }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(isRunning = true, errorMessage = message, status = "Foreground service camera bermasalah.") }
                }
            },
        ).also { it.connect() }

        updateState {
            copy(
                isRunning = true,
                token = token,
                serverUrl = serverUrl,
                errorMessage = null,
                status = "Foreground service camera memulai sesi token $token.",
            )
        }
    }

    fun stop(reason: String = "Device cam dihentikan.") {
        currentSessionId += 1
        currentDeviceId = ""
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

    private fun isCurrentSession(sessionId: Long): Boolean = currentSessionId == sessionId
}
