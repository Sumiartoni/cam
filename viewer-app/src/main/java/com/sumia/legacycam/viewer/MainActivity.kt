package com.sumia.legacycam.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.sumia.legacycam.core.ConnectedDevice
import com.sumia.legacycam.viewer.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: ViewerSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = ViewerSessionStore.loadSession(this, getString(R.string.default_server_url))
        binding.tokenValue.text = session.token
        binding.tokenValue.setOnClickListener { copyTokenToClipboard() }
        binding.copyTokenButton.setOnClickListener { copyTokenToClipboard() }
        binding.reloadButton.setOnClickListener {
            ViewerController.reload(
                context = this,
                serverUrl = session.serverUrl,
                token = session.token,
            )
        }

        ViewerSessionStore.markActive(this, true)
        ViewerController.ensureStarted(
            context = this,
            serverUrl = session.serverUrl,
            token = session.token,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ViewerController.state.collectLatest(::renderState)
            }
        }
    }

    private fun renderState(state: ViewerState) {
        binding.deviceListContainer.removeAllViews()
        binding.deviceEmptyValue.isVisible = state.devices.isEmpty()
        state.devices.forEach { device ->
            binding.deviceListContainer.addView(createDeviceButton(device))
        }
        binding.errorValue.isVisible = !state.errorMessage.isNullOrBlank()
        binding.errorValue.text = state.errorMessage.orEmpty()
    }

    private fun createDeviceButton(device: ConnectedDevice): MaterialButton {
        return MaterialButton(this).apply {
            text = device.deviceLabel
            isAllCaps = false
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, LiveFeedActivity::class.java)
                        .putExtra(LiveFeedActivity.EXTRA_DEVICE_ID, device.deviceId),
                )
            }
        }
    }

    private fun copyTokenToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LegacyCamToken", session.token))
    }
}
