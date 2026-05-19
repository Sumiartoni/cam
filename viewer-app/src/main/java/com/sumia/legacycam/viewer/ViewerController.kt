package com.sumia.legacycam.viewer

import android.content.Context
import com.sumia.legacycam.core.AppRole
import com.sumia.legacycam.core.ConnectedDevice
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

object ViewerController {
    private val mutableState = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = mutableState.asStateFlow()

    private var rtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private var currentSessionId: Long = 0
    private var startedToken: String = ""
    private var startedServerUrl: String = ""

    fun ensureStarted(context: Context, serverUrl: String, token: String, forceRestart: Boolean = false) {
        if (serverUrl.isBlank() || token.isBlank()) {
            updateState { copy(errorMessage = "Token atau server URL viewer belum valid.") }
            return
        }

        if (!forceRestart && state.value.isRunning && startedToken == token && startedServerUrl == serverUrl && signalingClient != null) {
            return
        }

        currentSessionId += 1
        val sessionId = currentSessionId
        startedToken = token
        startedServerUrl = serverUrl

        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null

        val manager = WebRtcManager(
            context.applicationContext,
            object : WebRtcManager.Listener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    if (!isCurrentSession(sessionId)) return
                    if (sessionDescription.type == SessionDescription.Type.ANSWER) {
                        signalingClient?.sendAnswer(sessionDescription.description)
                        updateState { copy(status = "Viewer mengirim answer ke device cam terpilih.") }
                    }
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    if (!isCurrentSession(sessionId)) return
                    signalingClient?.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    if (!isCurrentSession(sessionId)) return
                    val status = when (state) {
                        PeerConnection.PeerConnectionState.NEW -> "Viewer standby."
                        PeerConnection.PeerConnectionState.CONNECTING -> "Viewer sedang membangun sesi video."
                        PeerConnection.PeerConnectionState.CONNECTED -> "Live feed aktif."
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Koneksi viewer terputus."
                        PeerConnection.PeerConnectionState.CLOSED -> "Sesi viewer ditutup."
                        PeerConnection.PeerConnectionState.FAILED -> "Viewer gagal membangun peer connection."
                    }
                    updateState { copy(status = status) }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = message, status = "Viewer mengalami error WebRTC.") }
                }
            },
        )

        rtcManager = manager
        manager.start(AppRole.MONITOR)

        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            token = token,
            role = AppRole.MONITOR,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = token,
                            serverUrl = serverUrl,
                            errorMessage = null,
                            status = "Viewer terhubung ke signaling server.",
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
                            status = "Viewer standby pada token $token.",
                        )
                    }
                }

                override fun onPeerReady(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            selectedDeviceId = deviceId ?: selectedDeviceId,
                            status = "Device cam terpilih siap. Viewer menunggu offer video.",
                            errorMessage = null,
                        )
                    }
                }

                override fun onDeviceList(devices: List<ConnectedDevice>, selectedDeviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        val nextSelected = when {
                            selectedDeviceId != null -> selectedDeviceId
                            devices.any { it.deviceId == this.selectedDeviceId } -> this.selectedDeviceId
                            else -> null
                        }
                        copy(
                            devices = devices,
                            selectedDeviceId = nextSelected,
                            errorMessage = if (devices.isEmpty()) null else errorMessage,
                        )
                    }

                    if (selectedDeviceId == null && devices.none { it.deviceId == state.value.selectedDeviceId }) {
                        rtcManager?.endSession()
                        rtcManager?.start(AppRole.MONITOR)
                    }
                }

                override fun onSwitchCamera() = Unit

                override fun onOffer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(status = "Offer diterima. Viewer membuat answer.") }
                    rtcManager?.handleRemoteOffer(sdp)
                }

                override fun onAnswer(sdp: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            errorMessage = "Viewer tidak seharusnya menerima answer.",
                            status = "Urutan signaling viewer tidak valid.",
                        )
                    }
                }

                override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    if (!isCurrentSession(sessionId)) return
                    rtcManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerLeft(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    if (deviceId == null || deviceId == state.value.selectedDeviceId) {
                        rtcManager?.endSession()
                        rtcManager?.start(AppRole.MONITOR)
                    }
                    updateState {
                        copy(
                            selectedDeviceId = if (deviceId == selectedDeviceId) null else selectedDeviceId,
                            status = "Device cam keluar. Viewer tetap aktif menunggu device berikutnya.",
                            errorMessage = null,
                        )
                    }
                }

                override fun onClosed(reason: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(isRunning = false, status = "Koneksi viewer ditutup: $reason") }
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState { copy(errorMessage = message, status = "Viewer gagal pada lapisan signaling.") }
                }
            },
        ).also { it.connect() }

        updateState {
            copy(
                isRunning = true,
                token = token,
                serverUrl = serverUrl,
                errorMessage = null,
                status = if (forceRestart) "Viewer memuat ulang koneksi."
                else "Viewer memulihkan sesi token tersimpan.",
            )
        }
    }

    fun reload(context: Context, serverUrl: String, token: String) {
        ensureStarted(
            context = context,
            serverUrl = serverUrl,
            token = token,
            forceRestart = true,
        )
    }

    fun selectDevice(deviceId: String) {
        rtcManager?.endSession()
        rtcManager?.start(AppRole.MONITOR)
        signalingClient?.selectCamera(deviceId)
        updateState { copy(selectedDeviceId = deviceId, status = "Viewer meminta live feed dari device cam terpilih.", errorMessage = null) }
    }

    fun switchCamera() {
        signalingClient?.sendSwitchCamera()
        updateState { copy(status = "Viewer mengirim perintah pindah kamera ke device cam terpilih.", errorMessage = null) }
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.detachRemoteRenderer(renderer)
    }

    fun getSelectedDevice(): ConnectedDevice? {
        val selectedId = state.value.selectedDeviceId ?: return null
        return state.value.devices.firstOrNull { it.deviceId == selectedId }
    }

    private fun updateState(transform: ViewerState.() -> ViewerState) {
        mutableState.update(transform)
    }

    private fun isCurrentSession(sessionId: Long): Boolean = currentSessionId == sessionId
}
