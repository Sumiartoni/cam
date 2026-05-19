package com.sumia.legacycam.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.sumia.legacycam.core.AppRole
import com.sumia.legacycam.core.ConnectedDevice
import com.sumia.legacycam.core.SignalingClient
import com.sumia.legacycam.core.WebRtcManager
import com.sumia.legacycam.viewer.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var rtcManager: WebRtcManager

    private var signalingClient: SignalingClient? = null
    private var activeToken: String = ""
    private var serverUrl: String = ""
    private var connectedDevices: List<ConnectedDevice> = emptyList()
    private var selectedDeviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rtcManager = WebRtcManager(
            applicationContext,
            object : WebRtcManager.Listener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    if (sessionDescription.type == SessionDescription.Type.ANSWER) {
                        signalingClient?.sendAnswer(sessionDescription.description)
                        showStatus("Viewer mengirim answer ke device cam terpilih.")
                    }
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    signalingClient?.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    val status = when (state) {
                        PeerConnection.PeerConnectionState.NEW -> "Viewer siap menunggu feed camera."
                        PeerConnection.PeerConnectionState.CONNECTING -> "Viewer sedang membangun sesi video."
                        PeerConnection.PeerConnectionState.CONNECTED -> "Feed CCTV tampil di viewer."
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Koneksi viewer terputus."
                        PeerConnection.PeerConnectionState.CLOSED -> "Viewer menutup sesi."
                        PeerConnection.PeerConnectionState.FAILED -> "Viewer gagal membangun peer connection."
                    }
                    showStatus(status)
                }

                override fun onError(message: String) {
                    showError(message, "Viewer mengalami error WebRTC.")
                }
            },
        )

        val session = ViewerSessionStore.loadSession(this, getString(R.string.default_server_url))
        activeToken = session.token
        serverUrl = session.serverUrl

        binding.serverUrlInput.setText(serverUrl)
        rtcManager.attachRemoteRenderer(binding.remoteView)
        setupInputs()
        setupActions()
        renderToken()
        renderSelectedDevice()
        renderDeviceList()
        showStatus("Viewer siap. Aktifkan monitor lalu pilih device cam yang ingin ditonton.")

        if (session.isActive) {
            connectViewer(autoReconnect = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        rtcManager.release()
    }

    private fun setupInputs() {
        binding.serverUrlInput.doAfterTextChanged {
            serverUrl = it?.toString().orEmpty()
            ViewerSessionStore.saveServerUrl(this, serverUrl)
        }
    }

    private fun setupActions() {
        binding.generateTokenButton.setOnClickListener {
            copyTokenToClipboard()
        }

        binding.connectButton.setOnClickListener {
            connectViewer()
        }

        binding.disconnectButton.setOnClickListener {
            stopViewer("Viewer dimatikan.")
        }

        binding.switchCameraButton.setOnClickListener {
            if (selectedDeviceId == null) {
                showError("Pilih device cam terlebih dulu.", "Viewer belum punya device aktif.")
                return@setOnClickListener
            }
            signalingClient?.sendSwitchCamera()
            showStatus("Viewer mengirim perintah pindah kamera ke device cam terpilih.")
        }
    }

    private fun connectViewer(autoReconnect: Boolean = false) {
        if (serverUrl.isBlank()) {
            showError(
                "Isi URL signaling valid, contoh: wss://cam.zienix.me/ws",
                "URL signaling viewer belum valid.",
            )
            return
        }

        ViewerSessionStore.saveToken(this, activeToken)
        ViewerSessionStore.saveServerUrl(this, serverUrl.trim())
        ViewerSessionStore.markActive(this, true)

        stopViewer(
            if (autoReconnect) "Viewer memulihkan sesi token tersimpan."
            else "Viewer memulai sesi baru.",
            preserveSession = true,
        )
        rtcManager.start(AppRole.MONITOR)
        signalingClient = SignalingClient(
            serverUrl = serverUrl.trim(),
            token = activeToken,
            role = AppRole.MONITOR,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    showStatus("Viewer terhubung ke signaling server.")
                }

                override fun onRegistered(deviceId: String?) {
                    showStatus("Viewer standby pada token $activeToken.")
                }

                override fun onPeerReady(deviceId: String?) {
                    selectedDeviceId = deviceId ?: selectedDeviceId
                    renderSelectedDevice()
                    renderDeviceList()
                    showStatus("Device cam terpilih siap. Viewer menunggu offer video.")
                }

                override fun onDeviceList(devices: List<ConnectedDevice>, selectedDeviceId: String?) {
                    connectedDevices = devices
                    if (selectedDeviceId != null) {
                        this@MainActivity.selectedDeviceId = selectedDeviceId
                    } else if (connectedDevices.none { it.deviceId == this@MainActivity.selectedDeviceId }) {
                        this@MainActivity.selectedDeviceId = null
                        rtcManager.endSession()
                    }
                    renderSelectedDevice()
                    renderDeviceList()
                }

                override fun onSwitchCamera() = Unit

                override fun onOffer(sdp: String) {
                    showStatus("Offer diterima. Viewer membuat answer.")
                    rtcManager.handleRemoteOffer(sdp)
                }

                override fun onAnswer(sdp: String) {
                    showError("Viewer tidak seharusnya menerima answer.", "Urutan signaling viewer tidak valid.")
                }

                override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    rtcManager.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onPeerLeft(deviceId: String?) {
                    if (deviceId == null || deviceId == selectedDeviceId) {
                        rtcManager.endSession()
                    }
                    showStatus("Device cam keluar. Viewer tetap aktif menunggu device berikutnya.")
                }

                override fun onClosed(reason: String) {
                    showStatus("Koneksi viewer ditutup: $reason")
                }

                override fun onError(message: String) {
                    showError(message, "Viewer gagal pada lapisan signaling.")
                }
            },
        ).also { it.connect() }
    }

    private fun stopViewer(status: String, preserveSession: Boolean = false) {
        if (!preserveSession) {
            ViewerSessionStore.markActive(this, false)
        }
        signalingClient?.disconnect()
        signalingClient = null
        rtcManager.endSession()
        connectedDevices = emptyList()
        selectedDeviceId = null
        renderSelectedDevice()
        renderDeviceList()
        showStatus(status)
        hideError()
    }

    private fun renderToken() {
        binding.tokenValue.text = activeToken
    }

    private fun renderSelectedDevice() {
        val selectedDevice = connectedDevices.firstOrNull { it.deviceId == selectedDeviceId }
        binding.selectedDeviceValue.text = selectedDevice?.deviceLabel ?: getString(R.string.no_device_selected)
    }

    private fun renderDeviceList() {
        binding.deviceListContainer.removeAllViews()
        binding.deviceEmptyValue.isVisible = connectedDevices.isEmpty()
        connectedDevices.forEach { device ->
            val button = MaterialButton(this).apply {
                text = device.deviceLabel
                isAllCaps = false
                setOnClickListener { selectDevice(device) }
                if (device.deviceId == selectedDeviceId) {
                    setIconResource(android.R.drawable.presence_online)
                }
            }
            binding.deviceListContainer.addView(button)
        }
    }

    private fun selectDevice(device: ConnectedDevice) {
        selectedDeviceId = device.deviceId
        renderSelectedDevice()
        renderDeviceList()
        hideError()
        rtcManager.start(AppRole.MONITOR)
        signalingClient?.selectCamera(device.deviceId)
        showStatus("Viewer meminta live feed dari ${device.deviceLabel}.")
    }

    private fun copyTokenToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LegacyCamToken", activeToken))
        showStatus("Token viewer disalin. Gunakan token ini pada device cam.")
        hideError()
    }

    private fun showStatus(message: String) {
        binding.statusValue.text = message
    }

    private fun showError(error: String, status: String) {
        binding.errorValue.isVisible = true
        binding.errorValue.text = error
        binding.statusValue.text = status
    }

    private fun hideError() {
        binding.errorValue.isVisible = false
        binding.errorValue.text = ""
    }
}
