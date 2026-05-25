package com.sumia.legacycam.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcManager(
    private val context: Context,
    private val iceServerConfigs: List<RtcIceServerConfig> = RtcConfigDefaults.iceServers,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalDescription(sessionDescription: SessionDescription)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onError(message: String)
    }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var preferFrontCamera: Boolean = false
    private var cameraHealthy: Boolean = false
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var currentCameraName: String? = null
    private var currentCameraSupportsTorch: Boolean = false
    private var torchEnabled: Boolean = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(true)
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
        if (localRenderer === renderer) {
            localRenderer = null
        }
        renderer.clearImage()
        renderer.release()
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(false)
        renderer.setMirror(false)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setZOrderMediaOverlay(false)
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(renderer)
        if (remoteRenderer === renderer) {
            remoteRenderer = null
        }
        renderer.clearImage()
        renderer.release()
    }

    fun start(role: AppRole) {
        if (role == AppRole.CAMERA) {
            restartPeerSession(role)
            if (!hasHealthyLocalCapture()) {
                startLocalVideo()
            } else {
                attachExistingLocalTrack()
            }
            return
        }

        endSession()
        ensurePeerConnection()
        ensureMonitorVideoReceiver()
    }

    fun hasHealthyLocalCapture(): Boolean {
        return videoCapturer != null && videoSource != null && localVideoTrack != null && cameraHealthy
    }

    fun restartPeerSession(role: AppRole) {
        closePeerConnection()
        ensurePeerConnection()
        if (role == AppRole.CAMERA) {
            attachExistingLocalTrack()
        } else {
            ensureMonitorVideoReceiver()
        }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(
            SimpleSdpObserver(
                onCreateSuccess = { description ->
                    setLocalDescription(description)
                },
                onFailure = { reason ->
                    listener.onError("Gagal membuat offer: $reason")
                },
            ),
            constraints,
        )
    }

    fun handleRemoteOffer(sdp: String) {
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(
                onSetSuccess = { createAnswer() },
                onFailure = { reason -> listener.onError("Gagal set remote offer: $reason") },
            ),
            description,
        )
    }

    fun handleRemoteAnswer(sdp: String) {
        val description = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(
                onFailure = { reason -> listener.onError("Gagal set remote answer: $reason") },
            ),
            description,
        )
    }

    fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun endSession() {
        closePeerConnection()
        disableTorchSilently()

        localVideoTrack?.setEnabled(false)
        localVideoTrack = null
        cameraHealthy = false

        videoSource?.dispose()
        videoSource = null

        videoCapturer?.runCatching {
            stopCapture()
            dispose()
        }
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    fun switchCamera() {
        val capturer = videoCapturer as? CameraVideoCapturer ?: run {
            listener.onError("Perangkat ini tidak mendukung switch kamera.")
            return
        }

        capturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    preferFrontCamera = isFrontCamera
                    updateActiveCameraSelection(isFrontCamera)
                    localRenderer?.setMirror(isFrontCamera)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    listener.onError(errorDescription ?: "Gagal memindahkan kamera.")
                }
            },
        )
    }

    fun toggleFlash(): Result<Boolean> {
        val cameraName = currentCameraName
            ?: return Result.failure(IllegalStateException("Kamera aktif belum siap untuk flash."))
        if (preferFrontCamera) {
            return Result.failure(IllegalStateException("Flash hanya tersedia saat kamera belakang aktif."))
        }
        if (!currentCameraSupportsTorch) {
            return Result.failure(IllegalStateException("Perangkat ini tidak mendukung flash pada kamera aktif."))
        }
        val manager = cameraManager
            ?: return Result.failure(IllegalStateException("Torch manager tidak tersedia di perangkat ini."))

        return runCatching {
            val nextState = !torchEnabled
            manager.setTorchMode(cameraName, nextState)
            torchEnabled = nextState
            nextState
        }
    }

    fun isFlashEnabled(): Boolean = torchEnabled

    fun release() {
        endSession()
        localRenderer?.release()
        remoteRenderer?.release()
        eglBase.release()
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(
            SimpleSdpObserver(
                onCreateSuccess = { description ->
                    setLocalDescription(description)
                },
                onFailure = { reason ->
                    listener.onError("Gagal membuat answer: $reason")
                },
            ),
            constraints,
        )
    }

    private fun setLocalDescription(description: SessionDescription) {
        peerConnection?.setLocalDescription(
            SimpleSdpObserver(
                onSetSuccess = { listener.onLocalDescription(description) },
                onFailure = { reason -> listener.onError("Gagal set local description: $reason") },
            ),
            description,
        )
    }

    private fun ensurePeerConnection() {
        if (peerConnection != null) {
            return
        }

        val iceServers = iceServerConfigs
            .filter { it.urls.isNotEmpty() }
            .map { config ->
                PeerConnection.IceServer.builder(config.urls)
                    .apply {
                        if (!config.username.isNullOrBlank()) {
                            setUsername(config.username)
                        }
                        if (!config.credential.isNullOrBlank()) {
                            setPassword(config.credential)
                        }
                    }
                    .createIceServer()
            }
            .ifEmpty {
                RtcConfigDefaults.iceServers.map { config ->
                    PeerConnection.IceServer.builder(config.urls).createIceServer()
                }
            }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

                override fun onIceCandidate(candidate: IceCandidate) {
                    listener.onLocalIceCandidate(candidate)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

                override fun onAddStream(stream: org.webrtc.MediaStream) {
                    bindRemoteTrack(stream.videoTracks.firstOrNull())
                }

                override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                    remoteRenderer?.let { renderer -> remoteVideoTrack?.removeSink(renderer) }
                    remoteVideoTrack = null
                }

                override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit

                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    mediaStreams: Array<out org.webrtc.MediaStream>,
                ) {
                    bindRemoteTrack(receiver.track() as? VideoTrack)
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    bindRemoteTrack(transceiver.receiver.track() as? VideoTrack)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    listener.onConnectionStateChanged(newState)
                }
            },
        )
    }

    private fun closePeerConnection() {
        remoteVideoTrack?.setEnabled(false)
        remoteRenderer?.let { renderer -> remoteVideoTrack?.removeSink(renderer) }
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection = null
    }

    private fun startLocalVideo() {
        val selection = createVideoCapturer() ?: run {
            listener.onError("Kamera perangkat tidak ditemukan.")
            return
        }
        val capturer = selection.capturer
        preferFrontCamera = selection.isFrontFacing
        currentCameraName = selection.cameraName
        currentCameraSupportsTorch = selection.supportsTorch
        torchEnabled = false
        cameraHealthy = true

        val videoSource = factory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create("legacycam_capture", eglBase.eglBaseContext)

        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 24)

        val videoTrack = factory.createVideoTrack("legacycam_video_track", videoSource)
        videoTrack.setEnabled(true)
        localRenderer?.let(videoTrack::addSink)

        ensureCameraVideoSender(videoTrack)

        videoCapturer = capturer
        surfaceTextureHelper = helper
        this.videoSource = videoSource
        localVideoTrack = videoTrack
    }

    private fun attachExistingLocalTrack() {
        val track = localVideoTrack ?: return
        track.setEnabled(true)
        localRenderer?.let(track::addSink)
        ensureCameraVideoSender(track)
    }

    private fun bindRemoteTrack(track: VideoTrack?) {
        val videoTrack = track ?: return
        remoteVideoTrack = videoTrack
        videoTrack.setEnabled(true)
        remoteRenderer?.let(videoTrack::addSink)
    }

    private fun ensureMonitorVideoReceiver() {
        val connection = peerConnection ?: return
        val existing = connection.transceivers.firstOrNull {
            !it.isStopped && it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }

        if (existing != null) {
            existing.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            return
        }

        connection.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
    }

    private fun ensureCameraVideoSender(track: VideoTrack) {
        val connection = peerConnection ?: return
        val existing = connection.transceivers.firstOrNull {
            !it.isStopped && it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }

        if (existing != null) {
            existing.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            val sender: RtpSender = existing.sender
            sender.setTrack(track, false)
            sender.setStreams(listOf("legacycam_stream"))
            return
        }

        connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf("legacycam_stream"),
            ),
        )
    }

    private fun createVideoCapturer(): CapturerSelection? {
        if (Camera2Enumerator.isSupported(context)) {
            val camera2 = Camera2Enumerator(context)
            val preferred = chooseDeviceName(
                deviceNames = camera2.deviceNames.toList(),
                isFrontFacing = { camera2.isFrontFacing(it) },
            )
            if (preferred != null) {
                val capturer = camera2.createCapturer(preferred, createCameraEventsHandler())
                if (capturer != null) {
                    return CapturerSelection(
                        capturer = capturer,
                        cameraName = preferred,
                        isFrontFacing = camera2.isFrontFacing(preferred),
                        supportsTorch = cameraSupportsTorch(preferred),
                    )
                }
            }
        }

        val camera1 = Camera1Enumerator(false)
        val preferred = chooseDeviceName(
            deviceNames = camera1.deviceNames.toList(),
            isFrontFacing = { camera1.isFrontFacing(it) },
        ) ?: return null
        val capturer = camera1.createCapturer(preferred, createCameraEventsHandler()) ?: return null
        return CapturerSelection(
            capturer = capturer,
            cameraName = preferred,
            isFrontFacing = camera1.isFrontFacing(preferred),
            supportsTorch = false,
        )
    }

    private fun createCameraEventsHandler(): CameraVideoCapturer.CameraEventsHandler {
        return object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String) {
                cameraHealthy = false
                listener.onError(errorDescription.ifBlank { "Kamera mengalami error." })
            }

            override fun onCameraDisconnected() {
                cameraHealthy = false
                listener.onError("Kamera terputus dari sistem Android.")
            }

            override fun onCameraFreezed(errorDescription: String) {
                cameraHealthy = false
                listener.onError(errorDescription.ifBlank { "Kamera berhenti merespons." })
            }

            override fun onCameraOpening(cameraName: String) {
                cameraHealthy = true
            }

            override fun onFirstFrameAvailable() {
                cameraHealthy = true
            }

            override fun onCameraClosed() {
                cameraHealthy = false
            }
        }
    }

    private fun chooseDeviceName(
        deviceNames: List<String>,
        isFrontFacing: (String) -> Boolean,
    ): String? {
        val desired = deviceNames.firstOrNull { isFrontFacing(it) == preferFrontCamera }
        return desired ?: deviceNames.firstOrNull()
    }

    private fun updateActiveCameraSelection(isFrontCamera: Boolean) {
        val previousCameraName = currentCameraName
        currentCameraName = resolveCameraName(isFrontCamera)
        currentCameraSupportsTorch = currentCameraName?.let(::cameraSupportsTorch) == true
        if (isFrontCamera || !currentCameraSupportsTorch) {
            disableTorchSilently(previousCameraName)
        }
    }

    private fun resolveCameraName(isFrontCamera: Boolean): String? {
        if (!Camera2Enumerator.isSupported(context)) {
            return currentCameraName
        }
        val enumerator = Camera2Enumerator(context)
        val desired = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == isFrontCamera }
        return desired ?: currentCameraName
    }

    private fun cameraSupportsTorch(cameraName: String): Boolean {
        val manager = cameraManager ?: return false
        return runCatching {
            manager.getCameraCharacteristics(cameraName)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    }

    private fun disableTorchSilently(cameraNameOverride: String? = null) {
        val cameraName = cameraNameOverride ?: currentCameraName ?: return
        if (!torchEnabled) {
            return
        }
        runCatching {
            cameraManager?.setTorchMode(cameraName, false)
        }
        torchEnabled = false
    }
}

private data class CapturerSelection(
    val capturer: VideoCapturer,
    val cameraName: String,
    val isFrontFacing: Boolean,
    val supportsTorch: Boolean,
)

private class SimpleSdpObserver(
    private val onCreateSuccess: ((SessionDescription) -> Unit)? = null,
    private val onSetSuccess: (() -> Unit)? = null,
    private val onFailure: ((String) -> Unit)? = null,
) : org.webrtc.SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription) {
        onCreateSuccess?.invoke(sessionDescription)
    }

    override fun onSetSuccess() {
        onSetSuccess?.invoke()
    }

    override fun onCreateFailure(reason: String) {
        onFailure?.invoke(reason)
    }

    override fun onSetFailure(reason: String) {
        onFailure?.invoke(reason)
    }
}
