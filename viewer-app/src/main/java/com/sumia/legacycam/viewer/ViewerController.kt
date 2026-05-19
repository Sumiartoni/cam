package com.sumia.legacycam.viewer

import android.content.Context
import com.sumia.legacycam.core.AppRole
import com.sumia.legacycam.core.ConnectedDevice
import com.sumia.legacycam.core.SignalingClient
import com.sumia.legacycam.core.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

object ViewerController {
    private data class DesiredSession(
        val context: Context,
        val serverUrl: String,
        val token: String,
    )

    private val mutableState = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = mutableState.asStateFlow()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private var currentSessionId: Long = 0
    private var startedToken: String = ""
    private var startedServerUrl: String = ""
    private var desiredSession: DesiredSession? = null
    private var reconnectJob: Job? = null
    private var manualStopRequested: Boolean = false
    private var reconnectAttempt: Int = 0

    fun ensureStarted(context: Context, serverUrl: String, token: String, forceRestart: Boolean = false) {
        if (serverUrl.isBlank() || token.isBlank()) {
            updateState { copy(errorMessage = "Token atau server URL viewer belum valid.") }
            return
        }

        if (!forceRestart && state.value.isRunning && startedToken == token && startedServerUrl == serverUrl && signalingClient != null) {
            return
        }

        startedToken = token
        startedServerUrl = serverUrl
        manualStopRequested = false
        if (forceRestart) {
            reconnectAttempt = 0
        }
        desiredSession = DesiredSession(
            context = context.applicationContext,
            serverUrl = serverUrl,
            token = token,
        )
        cancelReconnect()
        startDesiredSession(
            initialStatus = if (forceRestart) "Viewer memuat ulang koneksi."
            else "Viewer memulihkan sesi token tersimpan.",
        )
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
        rtcManager?.restartPeerSession(AppRole.MONITOR)
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

    private fun startDesiredSession(initialStatus: String) {
        val session = desiredSession ?: return

        currentSessionId += 1
        val sessionId = currentSessionId
        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null

        val manager = WebRtcManager(
            session.context,
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
            serverUrl = session.serverUrl,
            token = session.token,
            role = AppRole.MONITOR,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    if (!isCurrentSession(sessionId)) return
                    reconnectAttempt = 0
                    updateState {
                        copy(
                            isRunning = true,
                            token = session.token,
                            serverUrl = session.serverUrl,
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
                            token = session.token,
                            serverUrl = session.serverUrl,
                            status = "Viewer standby pada token ${session.token}.",
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
                        rtcManager?.restartPeerSession(AppRole.MONITOR)
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
                        rtcManager?.restartPeerSession(AppRole.MONITOR)
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
                    scheduleReconnect(reason, sessionId)
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
                token = session.token,
                serverUrl = session.serverUrl,
                errorMessage = null,
                status = initialStatus,
            )
        }
    }

    private fun updateState(transform: ViewerState.() -> ViewerState) {
        mutableState.update(transform)
    }

    private fun scheduleReconnect(reason: String, sessionId: Long) {
        if (manualStopRequested || !isCurrentSession(sessionId) || desiredSession == null) {
            return
        }

        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectAttempt += 1
        val delayMs = (1500L * reconnectAttempt).coerceAtMost(8000L)
        updateState {
            copy(
                isRunning = true,
                errorMessage = null,
                status = "Viewer terputus dari server. Sistem mencoba lagi dalam ${delayMs / 1000} detik.",
            )
        }

        reconnectJob = controllerScope.launch {
            delay(delayMs)
            if (!manualStopRequested && isCurrentSession(sessionId) && desiredSession != null) {
                startDesiredSession(initialStatus = "Viewer menghubungkan ulang ke server.")
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun isCurrentSession(sessionId: Long): Boolean = currentSessionId == sessionId
}
