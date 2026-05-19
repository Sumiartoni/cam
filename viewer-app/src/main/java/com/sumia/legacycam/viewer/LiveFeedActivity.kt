package com.sumia.legacycam.viewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sumia.legacycam.viewer.databinding.ActivityLiveFeedBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LiveFeedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveFeedBinding
    private var previewAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val selectedDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        if (selectedDeviceId.isBlank()) {
            finish()
            return
        }

        ViewerController.selectDevice(selectedDeviceId)
        binding.backButton.setOnClickListener { finish() }
        binding.switchCameraButton.setOnClickListener { ViewerController.switchCamera() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ViewerController.state.collectLatest(::renderState)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!previewAttached) {
            ViewerController.attachRemoteRenderer(binding.remoteView)
            previewAttached = true
        }
    }

    override fun onStop() {
        if (previewAttached) {
            ViewerController.detachRemoteRenderer(binding.remoteView)
            previewAttached = false
        }
        super.onStop()
    }

    private fun renderState(state: ViewerState) {
        val selectedDevice = ViewerController.getSelectedDevice()
        binding.deviceNameValue.text = selectedDevice?.deviceLabel ?: getString(R.string.no_device_selected)
        binding.statusValue.text = state.status
        binding.errorValue.isVisible = !state.errorMessage.isNullOrBlank()
        binding.errorValue.text = state.errorMessage.orEmpty()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
