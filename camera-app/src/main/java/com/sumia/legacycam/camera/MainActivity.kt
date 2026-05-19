package com.sumia.legacycam.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sumia.legacycam.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var pendingToken: String = ""
    private var hasBoundSession: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredPermissions()) {
            startCameraService()
        } else {
            renderTokenError(getString(R.string.permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val boundSession = CameraSessionStore.loadBound(this)
        if (boundSession != null) {
            hasBoundSession = true
            pendingToken = boundSession.token
            ensureBoundService(boundSession)
            renderBoundState()
        } else {
            renderInputState()
        }

        binding.tokenInput.setText(prefillToken())
        binding.tokenInput.setSelection(binding.tokenInput.text?.length ?: 0)
        binding.activateButton.setOnClickListener {
            val tokenValue = binding.tokenInput.text?.toString().orEmpty().trim().uppercase()
            if (tokenValue.isBlank()) {
                renderTokenError(getString(R.string.token_required))
                return@setOnClickListener
            }

            binding.tokenLayout.error = null
            pendingToken = tokenValue
            requestPermissionsAndStart()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CameraStreamingController.state.collectLatest { state ->
                    if (hasBoundSession || state.isRunning) {
                        renderBoundState()
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        if (hasRequiredPermissions()) {
            startCameraService()
            return
        }

        permissionLauncher.launch(requiredPermissions())
    }

    private fun startCameraService() {
        CameraSessionStore.saveBinding(this, getString(R.string.default_server_url), pendingToken)
        hasBoundSession = true
        CameraForegroundService.start(
            context = this,
            serverUrl = getString(R.string.default_server_url),
            token = pendingToken,
        )
        renderBoundState()
    }

    private fun prefillToken(): String {
        return CameraSessionStore.loadBound(this)?.token.orEmpty()
    }

    private fun ensureBoundService(session: SavedCameraSession) {
        if (!CameraSessionStore.isMarkedActive(this)) {
            CameraForegroundService.start(
                context = this,
                serverUrl = session.serverUrl,
                token = session.token,
            )
        }
    }

    private fun renderInputState() {
        binding.tokenLayout.isVisible = true
        binding.activateButton.isVisible = true
        binding.runningTextValue.isVisible = false
    }

    private fun renderBoundState() {
        binding.tokenLayout.isVisible = false
        binding.activateButton.isVisible = false
        binding.runningTextValue.isVisible = true
    }

    private fun renderTokenError(message: String) {
        binding.tokenLayout.error = message
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }
}
