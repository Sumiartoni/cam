package com.sumia.legacycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.sumia.legacycam.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiState = MutableStateFlow(UiState())
    private var cameraPreviewAttached = false

    private var activeRole: AppRole? = null
    private var signalingClient: SignalingClient? = null

    private lateinit var monitorRtcManager: WebRtcManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            connectCameraInternal()
        } else {
            updateState {
                copy(
                    errorMessage = "Izin kamera dibutuhkan agar HP lama bisa menjadi CCTV.",
                    status = "Izin kamera ditolak.",
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        monitorRtcManager = WebRtcManager(
            applicationContext,
            object : WebRtcManager.Listener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    when (sessionDescription.type) {
                        SessionDescription.Type.ANSWER -> {
                            signalingClient?.sendAnswer(sessionDescription.description)
                            updateState { copy(status = "Answer terkirim ke kamera.") }
                        }

                        else -> Unit
                    }
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    signalingClient?.sendIceCandidate(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                    )
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    val status = when (state) {
                        PeerConnection.PeerConnectionState.NEW -> "Peer connection dibuat."
                        PeerConnection.PeerConnectionState.CONNECTING -> "Menghubungkan stream video."
                        PeerConnection.PeerConnectionState.CONNECTED -> "Stream video aktif."
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Peer terputus."
                        PeerConnection.PeerConnectionState.CLOSED -> "Peer connection ditutup."
                        PeerConnection.PeerConnectionState.FAILED -> "Peer connection gagal."
                    }
                    updateState { copy(status = status) }
                }

                override fun onError(message: String) {
                    updateState { copy(errorMessage = message, status = "Terjadi masalah pada WebRTC.") }
                }
            },
        )

        monitorRtcManager.attachRemoteRenderer(binding.monitorRemoteView)

        bindInputs()
        bindActions()
        lifecycleScope.launch {
            combine(uiState, CameraStreamingController.state) { ui, cameraService ->
                ui to cameraService
            }.collectLatest { (ui, cameraService) ->
                render(ui, cameraService)
            }
        }
    }

    override fun onDestroy() {
        if (cameraPreviewAttached) {
            CameraStreamingController.detachPreviewRenderer(binding.cameraLocalView)
            cameraPreviewAttached = false
        }
        super.onDestroy()
        signalingClient?.disconnect()
        monitorRtcManager.release()
    }

    private fun bindInputs() {
        binding.monitorServerUrlInput.doAfterTextChanged {
            updateState { copy(serverUrl = it?.toString().orEmpty()) }
        }

        binding.cameraServerUrlInput.doAfterTextChanged {
            updateState { copy(serverUrl = it?.toString().orEmpty()) }
        }

        binding.cameraTokenInput.doAfterTextChanged {
            updateState { copy(cameraToken = it?.toString().orEmpty().uppercase()) }
        }
    }

    private fun bindActions() {
        binding.openMonitorButton.setOnClickListener {
            updateState {
                copy(
                    screen = Screen.MONITOR,
                    monitorToken = monitorToken.ifBlank { TokenGenerator.create() },
                    errorMessage = null,
                    status = "Monitor siap. Bagikan token ini ke HP kamera.",
                )
            }
        }

        binding.openCameraButton.setOnClickListener {
            updateState {
                copy(
                    screen = Screen.CAMERA,
                    errorMessage = null,
                    status = "Masukkan token dari HP monitor lalu aktifkan kamera.",
                )
            }
        }

        binding.regenerateTokenButton.setOnClickListener {
            updateState {
                copy(
                    monitorToken = TokenGenerator.create(),
                    errorMessage = null,
                    status = "Token pairing diganti. Gunakan token baru di HP kamera.",
                )
            }
        }

        binding.connectMonitorButton.setOnClickListener {
            connectMonitor()
        }

        binding.connectCameraButton.setOnClickListener {
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            if (permission == PackageManager.PERMISSION_GRANTED) {
                connectCameraInternal()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.disconnectMonitorButton.setOnClickListener {
            stopSession("Sesi monitor dihentikan.")
        }

        binding.disconnectCameraButton.setOnClickListener {
            stopCameraService("Sesi kamera dihentikan.")
        }

        binding.backFromMonitorButton.setOnClickListener {
            stopSession("Kembali ke pemilihan mode.")
            updateState { copy(screen = Screen.HOME) }
        }

        binding.backFromCameraButton.setOnClickListener {
            stopCameraService("Kembali ke pemilihan mode.")
            updateState { copy(screen = Screen.HOME) }
        }
    }

    private fun connectMonitor() {
        val state = uiState.value
        val token = state.monitorToken.ifBlank { TokenGenerator.create() }
        val serverUrl = state.serverUrl.trim()

        if (serverUrl.isBlank() || serverUrl.contains("YOUR_SERVER_IP")) {
            updateState {
                copy(
                    monitorToken = token,
                    errorMessage = "Ganti URL signaling dengan alamat server yang benar, misalnya ws://192.168.1.20:8080/ws",
                    status = "URL signaling belum valid.",
                )
            }
            return
        }

        stopSession("Menginisialisasi monitor baru.")
        activeRole = AppRole.MONITOR
        monitorRtcManager.start(AppRole.MONITOR)
        signalingClient = createSignalingClient(serverUrl, token, AppRole.MONITOR).also { it.connect() }

        updateState {
            copy(
                monitorToken = token,
                errorMessage = null,
                status = "Monitor menghubungkan diri ke signaling server.",
            )
        }
    }

    private fun connectCameraInternal() {
        val state = uiState.value
        val token = state.cameraToken.trim().uppercase()
        val serverUrl = state.serverUrl.trim()

        if (token.isBlank()) {
            updateState {
                copy(
                    errorMessage = "Token monitor wajib diisi di HP kamera.",
                    status = "Token belum diisi.",
                )
            }
            return
        }

        if (serverUrl.isBlank() || serverUrl.contains("YOUR_SERVER_IP")) {
            updateState {
                copy(
                    errorMessage = "Ganti URL signaling dengan alamat server yang benar, misalnya ws://192.168.1.20:8080/ws",
                    status = "URL signaling belum valid.",
                )
            }
            return
        }

        CameraForegroundService.start(this, serverUrl, token)

        updateState {
            copy(
                cameraToken = token,
                errorMessage = null,
                status = "Foreground service kamera sedang dinyalakan dengan token $token.",
            )
        }
    }

    private fun createSignalingClient(
        serverUrl: String,
        token: String,
        role: AppRole,
    ): SignalingClient {
        return SignalingClient(
            serverUrl = serverUrl,
            token = token,
            role = role,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    updateState { copy(status = "WebSocket signaling terbuka.") }
                }

                override fun onRegistered() {
                    updateState { copy(status = "Perangkat terdaftar pada room token $token.") }
                }

                override fun onPeerReady() {
                    if (role == AppRole.CAMERA) {
                        updateState { copy(status = "Mode kamera sekarang dijalankan dari foreground service.") }
                    } else {
                        updateState { copy(status = "Kamera berhasil pairing. Menunggu offer video.") }
                    }
                }

                override fun onOffer(sdp: String) {
                    updateState { copy(status = "Offer diterima. Membuat answer.") }
                    monitorRtcManager.handleRemoteOffer(sdp)
                }

                override fun onAnswer(sdp: String) {
                    updateState { copy(status = "Answer diterima dari monitor.") }
                    monitorRtcManager.handleRemoteAnswer(sdp)
                }

                override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    monitorRtcManager.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerLeft() {
                    if (activeRole == AppRole.MONITOR) {
                        monitorRtcManager.start(AppRole.MONITOR)
                        updateState {
                            copy(
                                status = "Kamera terputus. Monitor tetap standby menunggu kamera lain dengan token yang sama.",
                            )
                        }
                    } else {
                        stopSession("Monitor memutuskan sesi.")
                        updateState {
                            copy(
                                errorMessage = "Monitor keluar dari room. Pairing harus diulang dari awal.",
                                status = "Monitor terputus.",
                            )
                        }
                    }
                }

                override fun onClosed(reason: String) {
                    updateState { copy(status = "Koneksi signaling ditutup: $reason") }
                }

                override fun onError(message: String) {
                    updateState { copy(errorMessage = message, status = "Koneksi signaling bermasalah.") }
                }
            },
        )
    }

    private fun stopSession(status: String) {
        signalingClient?.disconnect()
        signalingClient = null
        monitorRtcManager.endSession()
        activeRole = null
        updateState { copy(errorMessage = null, status = status) }
    }

    private fun stopCameraService(status: String) {
        CameraForegroundService.stop(this)
        updateState { copy(errorMessage = null, status = status) }
    }

    private fun render(state: UiState, cameraService: CameraServiceState) {
        binding.launcherCard.isVisible = state.screen == Screen.HOME
        binding.monitorCard.isVisible = state.screen == Screen.MONITOR
        binding.cameraCard.isVisible = state.screen == Screen.CAMERA

        binding.monitorTokenView.text = state.monitorToken.ifBlank { "------" }
        val effectiveStatus = if (state.screen == Screen.CAMERA && cameraService.status.isNotBlank()) {
            cameraService.status
        } else {
            state.status
        }
        val effectiveError = if (state.screen == Screen.CAMERA) {
            cameraService.errorMessage
        } else {
            state.errorMessage
        }
        binding.statusText.text = effectiveStatus
        binding.errorText.isVisible = !effectiveError.isNullOrBlank()
        binding.errorText.text = effectiveError.orEmpty()

        setTextIfDifferent(binding.monitorServerUrlInput, state.serverUrl)
        setTextIfDifferent(binding.cameraServerUrlInput, state.serverUrl)
        setTextIfDifferent(binding.cameraTokenInput, state.cameraToken)
        binding.connectCameraButton.text = if (cameraService.isRunning) {
            getString(R.string.camera_running)
        } else {
            getString(R.string.start_camera)
        }

        if (state.screen == Screen.CAMERA && cameraService.isRunning && !cameraPreviewAttached) {
            CameraStreamingController.attachPreviewRenderer(binding.cameraLocalView)
            cameraPreviewAttached = true
        }

        if (!cameraService.isRunning) {
            cameraPreviewAttached = false
        }
    }

    private fun setTextIfDifferent(editText: EditText, value: String) {
        if (editText.text?.toString() != value) {
            editText.setText(value)
            editText.setSelection(editText.text?.length ?: 0)
        }
    }

    private fun updateState(transform: UiState.() -> UiState) {
        uiState.update(transform)
    }
}

private data class UiState(
    val screen: Screen = Screen.HOME,
    val serverUrl: String = "ws://YOUR_SERVER_IP:8080/ws",
    val monitorToken: String = "",
    val cameraToken: String = "",
    val status: String = "Pilih mode perangkat terlebih dahulu.",
    val errorMessage: String? = null,
)

private enum class Screen {
    HOME,
    MONITOR,
    CAMERA,
}
