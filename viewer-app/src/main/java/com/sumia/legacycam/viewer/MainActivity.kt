package com.sumia.legacycam.viewer

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.sumia.legacycam.core.AppRole
import com.sumia.legacycam.core.SignalingClient
import com.sumia.legacycam.core.TokenGenerator
import com.sumia.legacycam.core.WebRtcManager
import com.sumia.legacycam.viewer.databinding.ActivityMainBinding
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var rtcManager: WebRtcManager

    private var signalingClient: SignalingClient? = null
    private var activeToken: String = TokenGenerator.create()
    private var serverUrl: String = "wss://cam.zienix.me/ws"

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
                        showStatus("Viewer mengirim answer ke device camera.")
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

        rtcManager.attachRemoteRenderer(binding.remoteView)
        setupInputs()
        setupActions()
        renderToken()
        showStatus("Viewer siap. Buat token lalu aktifkan channel monitor.")
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        rtcManager.release()
    }

    private fun setupInputs() {
        binding.serverUrlInput.doAfterTextChanged {
            serverUrl = it?.toString().orEmpty()
        }
    }

    private fun setupActions() {
        binding.generateTokenButton.setOnClickListener {
            activeToken = TokenGenerator.create()
            renderToken()
            showStatus("Token viewer diperbarui. Bagikan token baru ke aplikasi camera.")
            hideError()
        }

        binding.connectButton.setOnClickListener {
            connectViewer()
        }

        binding.disconnectButton.setOnClickListener {
            stopViewer("Viewer dimatikan.")
        }
    }

    private fun connectViewer() {
        if (serverUrl.isBlank()) {
            showError(
                "Isi URL signaling valid, contoh: wss://cam.zienix.me/ws",
                "URL signaling viewer belum valid.",
            )
            return
        }

        stopViewer("Viewer memulai sesi baru.")
        rtcManager.start(AppRole.MONITOR)
        signalingClient = SignalingClient(
            serverUrl = serverUrl.trim(),
            token = activeToken,
            role = AppRole.MONITOR,
            listener = object : SignalingClient.Listener {
                override fun onSocketOpen() {
                    showStatus("Viewer terhubung ke signaling server.")
                }

                override fun onRegistered() {
                    showStatus("Viewer standby pada token $activeToken.")
                }

                override fun onPeerReady() {
                    showStatus("Device camera masuk. Viewer menunggu offer video.")
                }

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

                override fun onPeerLeft() {
                    rtcManager.start(AppRole.MONITOR)
                    showStatus("Camera keluar. Viewer tetap aktif menunggu camera lain dengan token yang sama.")
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

    private fun stopViewer(status: String) {
        signalingClient?.disconnect()
        signalingClient = null
        rtcManager.endSession()
        showStatus(status)
        hideError()
    }

    private fun renderToken() {
        binding.tokenValue.text = activeToken
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
