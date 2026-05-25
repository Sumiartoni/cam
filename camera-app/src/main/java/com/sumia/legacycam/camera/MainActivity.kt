package com.sumia.legacycam.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
    companion object {
        fun hasMediaPermissions(context: android.content.Context): Boolean {
            return mediaPermissionsForContext().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun mediaPermissionsForContext(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingToken: String = ""
    private var hasBoundSession: Boolean = false
    private var hasPromptedSystemProtection: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredPermissions()) {
            startCameraService()
        } else {
            renderTokenError(getString(R.string.permission_required))
        }
    }

    private val systemSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateSystemProtectionState()
        maybePromptSystemProtection(force = true)
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
        binding.openBatterySettingsButton.setOnClickListener {
            openBatteryOptimizationSettings()
        }
        binding.openMediaPermissionSettingsButton.setOnClickListener {
            openMediaPermissionSettings()
        }
        binding.startSnakeButton.setOnClickListener {
            binding.snakeGameView.startNewGame()
        }
        binding.pauseSnakeButton.setOnClickListener {
            binding.snakeGameView.togglePause()
        }
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

        binding.snakeGameView.setListener(
            object : SnakeGameView.Listener {
                override fun onScoreChanged(score: Int, highScore: Int) {
                    binding.snakeScoreValue.text = score.toString()
                    binding.snakeHighScoreValue.text = highScore.toString()
                }

                override fun onStatusChanged(status: String) {
                    binding.snakeStatusValue.text = status
                }

                override fun onRunningChanged(running: Boolean) {
                    binding.pauseSnakeButton.text = getString(
                        if (binding.snakeGameView.isPaused()) {
                            R.string.snake_resume_button
                        } else {
                            R.string.snake_pause_button
                        },
                    )
                }
            },
        )

        updateSystemProtectionState()
    }

    override fun onResume() {
        super.onResume()
        updateSystemProtectionState()
        maybePromptSystemProtection()
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
        maybePromptSystemProtection(force = true)
    }

    private fun prefillToken(): String {
        return CameraSessionStore.loadBound(this)?.token.orEmpty()
    }

    private fun ensureBoundService(session: SavedCameraSession) {
        if (!CameraStreamingController.state.value.isRunning) {
            CameraForegroundService.start(
                context = this,
                serverUrl = session.serverUrl,
                token = session.token,
            )
        }
        maybePromptSystemProtection()
    }

    private fun renderInputState() {
        binding.tokenLayout.isVisible = true
        binding.activateButton.isVisible = true
        binding.runningTextValue.isVisible = false
        binding.systemProtectionPanel.isVisible = false
        binding.snakePanel.isVisible = false
    }

    private fun renderBoundState() {
        binding.tokenLayout.isVisible = false
        binding.activateButton.isVisible = false
        binding.runningTextValue.isVisible = true
        binding.systemProtectionPanel.isVisible = hasOutstandingSystemProtectionRequirements()
        binding.snakePanel.isVisible = true
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
        permissions += mediaPermissions().toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun updateSystemProtectionState() {
        val batteryReady = isIgnoringBatteryOptimizations()
        val mediaReady = hasMediaPermissions()

        binding.batteryOptimizationStatus.text = getString(
            if (batteryReady) {
                R.string.battery_optimization_ready
            } else {
                R.string.battery_optimization_needed
            },
        )
        binding.mediaPermissionStatus.text = getString(
            if (mediaReady) {
                R.string.media_permission_ready
            } else {
                R.string.media_permission_needed
            },
        )
        binding.openBatterySettingsButton.isEnabled = !batteryReady
        binding.openMediaPermissionSettingsButton.isEnabled = !mediaReady
        if (hasBoundSession) {
            binding.systemProtectionPanel.isVisible = hasOutstandingSystemProtectionRequirements()
        }
    }

    private fun maybePromptSystemProtection(force: Boolean = false) {
        if (!hasBoundSession) return
        if (!force && hasPromptedSystemProtection) return

        when {
            !hasMediaPermissions() -> {
                hasPromptedSystemProtection = true
                openMediaPermissionSettings()
            }

            !isIgnoringBatteryOptimizations() -> {
                hasPromptedSystemProtection = true
                openBatteryOptimizationSettings()
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        launchSystemSettings(intent)
    }

    private fun openMediaPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        launchSystemSettings(intent)
    }

    private fun launchSystemSettings(intent: Intent) {
        val packageManager = packageManager
        if (intent.resolveActivity(packageManager) != null) {
            systemSettingsLauncher.launch(intent)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasMediaPermissions(): Boolean {
        return hasMediaPermissions(this)
    }

    private fun hasOutstandingSystemProtectionRequirements(): Boolean {
        return !isIgnoringBatteryOptimizations() || !hasMediaPermissions()
    }

    private fun mediaPermissions(): Array<String> {
        return mediaPermissionsForContext()
    }
}
