package com.sumia.legacycam.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sumia.legacycam.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var serverUrl: String = "wss://cam.zienix.me/ws"
    private var token: String = ""
    private var previewAttached = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCameraService()
        } else {
            renderError("Izin kamera wajib diberikan untuk menjadikan device ini CCTV.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindInputs()
        bindActions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CameraStreamingController.state.collectLatest(::renderState)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (CameraStreamingController.state.value.isRunning && !previewAttached) {
            CameraStreamingController.attachPreviewRenderer(binding.previewView)
            previewAttached = true
        }
    }

    override fun onStop() {
        if (previewAttached) {
            CameraStreamingController.detachPreviewRenderer(binding.previewView)
            previewAttached = false
        }
        super.onStop()
    }

    private fun bindInputs() {
        binding.serverUrlInput.doAfterTextChanged {
            serverUrl = it?.toString().orEmpty()
        }
        binding.tokenInput.doAfterTextChanged {
            token = it?.toString().orEmpty().trim().uppercase()
        }
    }

    private fun bindActions() {
        binding.startCameraButton.setOnClickListener {
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            if (permission == PackageManager.PERMISSION_GRANTED) {
                startCameraService()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.stopCameraButton.setOnClickListener {
            CameraForegroundService.stop(this)
        }
    }

    private fun startCameraService() {
        if (token.isBlank()) {
            renderError("Masukkan token dari aplikasi viewer.")
            return
        }

        if (serverUrl.isBlank()) {
            renderError("Isi URL signaling valid, contoh: wss://cam.zienix.me/ws")
            return
        }

        hideError()
        CameraForegroundService.start(this, serverUrl.trim(), token)
        binding.statusValue.text = "Device cam menyalakan foreground service dengan token $token."
    }

    private fun renderState(state: CameraServiceState) {
        binding.statusValue.text = state.status
        binding.activeTokenValue.text = if (state.token.isBlank()) getString(R.string.no_token_yet) else state.token
        binding.startCameraButton.text = if (state.isRunning) getString(R.string.restart_camera) else getString(R.string.start_camera)
        binding.serviceBadge.text = if (state.isRunning) getString(R.string.service_on) else getString(R.string.service_off)
        binding.serviceBadge.setBackgroundResource(
            if (state.isRunning) R.drawable.camera_badge_on else R.drawable.camera_badge_off,
        )

        if (!state.errorMessage.isNullOrBlank()) {
            renderError(state.errorMessage)
        } else {
            hideError()
        }

        if (state.isRunning && !previewAttached) {
            CameraStreamingController.attachPreviewRenderer(binding.previewView)
            previewAttached = true
        }

        if (!state.isRunning && previewAttached) {
            CameraStreamingController.detachPreviewRenderer(binding.previewView)
            previewAttached = false
        }
    }

    private fun renderError(message: String) {
        binding.errorValue.isVisible = true
        binding.errorValue.text = message
    }

    private fun hideError() {
        binding.errorValue.isVisible = false
        binding.errorValue.text = ""
    }
}
