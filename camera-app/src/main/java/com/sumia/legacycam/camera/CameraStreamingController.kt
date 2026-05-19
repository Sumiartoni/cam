package com.sumia.legacycam.camera

import android.content.Context
import com.sumia.legacycam.core.AppRole
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

object CameraStreamingController {
    private data class DesiredSession(
        val context: Context,
        val serverUrl: String,
        val token: String,
        val deviceId: String,
    )

    private val mutableState = MutableStateFlow(CameraServiceState())
    val state: StateFlow<CameraServiceState> = mutableState.asStateFlow()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private var currentSessionId: Long = 0
    private var currentDeviceId: String = ""
    private var desiredSession: DesiredSession? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var manualStopRequested: Boolean = false
    private var reconnectAttempt: Int = 0
    private var lastNetworkAvailable: Boolean = true

    fun start(context: Context, serverUrl: String, token: String, deviceId: String) {
        manualStopRequested = false
        reconnectAttempt = 0
        desiredSession = DesiredSession(
            context = context.applicationContext,
            serverUrl = serverUrl,
            token = token,
            deviceId = deviceId,
        )
        cancelReconnect()
        ensureWatchdog()
        startDesiredSession(initialStatus = "Foreground service camera memulai sesi token $token.")
    }

    fun stop(reason: String = "Device cam dihentikan.") {
        manualStopRequested = true
        desiredSession = null
        reconnectAttempt = 0
        cancelReconnect()
        cancelWatchdog()
        currentSessionId += 1
        currentDeviceId = ""
        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null
        mutableState.value = CameraServiceState(status = reason)
    }

    fun onNetworkAvailable() {
        lastNetworkAvailable = true
        val session = desiredSession ?: return
        if (manualStopRequested) return

        reconnectAttempt = 0
        cancelReconnect()
        startDesiredSession(initialStatus = "Jaringan device cam kembali online. ant Vrs memulihkan koneksi camera.")
        updateState {
            copy(
                isRunning = true,
                token = session.token,
                serverUrl = session.serverUrl,
                errorMessage = null,
                status = "Jaringan device cam kembali online. ant Vrs memulihkan koneksi camera.",
            )
        }
    }

    fun onNetworkLost() {
        lastNetworkAvailable = false
        val session = desiredSession ?: return
        updateState {
            copy(
                isRunning = true,
                token = session.token,
                serverUrl = session.serverUrl,
                errorMessage = null,
                status = "Jaringan device cam terputus. ant Vrs menunggu koneksi kembali.",
            )
        }
    }

    fun attachPreviewRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.attachLocalRenderer(renderer)
    }

    fun detachPreviewRenderer(renderer: SurfaceViewRenderer) {
        rtcManager?.detachLocalRenderer(renderer)
    }

    private fun startDesiredSession(initialStatus: String) {
        val session = desiredSession ?: return

        currentSessionId += 1
        val sessionId = currentSessionId
        currentDeviceId = session.deviceId

        signalingClient?.disconnect()
        signalingClient = null
        rtcManager?.release()
        rtcManager = null

        val manager = WebRtcManager(
            session.context,
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
                    if (shouldRecoverCamera(message)) {
                        scheduleReconnect(message, sessionId)
                        return
                    }
                    updateState { copy(errorMessage = message, status = "WebRTC camera mengalami masalah.") }
                }
            },
        )

        rtcManager = manager
        manager.start(AppRole.CAMERA)

        signalingClient = SignalingClient(
            serverUrl = session.serverUrl,
            token = session.token,
            role = AppRole.CAMERA,
            deviceId = session.deviceId,
            deviceLabel = "Device ${session.deviceId}",
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
                            status = "Device cam aktif. Koneksi signaling terbuka.",
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
                            status = "Device cam standby di token ${session.token}.",
                        )
                    }
                }

                override fun onPeerReady(deviceId: String?) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = session.token,
                            serverUrl = session.serverUrl,
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
                    rtcManager?.restartPeerSession(AppRole.CAMERA)
                }

                override fun onClosed(reason: String) {
                    if (!isCurrentSession(sessionId)) return
                    scheduleReconnect(reason, sessionId)
                }

                override fun onError(message: String) {
                    if (!isCurrentSession(sessionId)) return
                    updateState {
                        copy(
                            isRunning = true,
                            token = session.token,
                            serverUrl = session.serverUrl,
                            errorMessage = message,
                            status = "Foreground service camera tetap berjalan, tetapi ada error signaling.",
                        )
                    }
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

    private fun updateState(transform: CameraServiceState.() -> CameraServiceState) {
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
                status = "Koneksi camera ke server terputus. ant Vrs mencoba lagi dalam ${delayMs / 1000} detik.",
            )
        }

        reconnectJob = controllerScope.launch {
            delay(delayMs)
            if (!manualStopRequested && isCurrentSession(sessionId) && desiredSession != null) {
                startDesiredSession(initialStatus = "ant Vrs menghubungkan ulang camera ke server.")
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) {
            return
        }

        watchdogJob = controllerScope.launch {
            while (true) {
                delay(12000L)
                val session = desiredSession ?: continue
                if (manualStopRequested) {
                    continue
                }

                val manager = rtcManager
                val captureHealthy = manager?.hasHealthyLocalCapture() == true
                val signalingHealthy = signalingClient?.isConnected() == true
                if (!lastNetworkAvailable) {
                    continue
                }
                if (signalingClient == null || !signalingHealthy || !mutableState.value.isRunning || !captureHealthy) {
                    reconnectAttempt = 0
                    startDesiredSession(initialStatus = "ant Vrs memulihkan camera di background.")
                    continue
                }

                updateState {
                    copy(
                        isRunning = true,
                        token = session.token,
                        serverUrl = session.serverUrl,
                    )
                }
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun shouldRecoverCamera(message: String): Boolean {
        val normalized = message.lowercase()
        return "kamera" in normalized ||
            "camera" in normalized ||
            "captur" in normalized ||
            "freeze" in normalized ||
            "disconnect" in normalized
    }

    private fun isCurrentSession(sessionId: Long): Boolean = currentSessionId == sessionId
}
