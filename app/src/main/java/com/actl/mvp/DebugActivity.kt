package com.actl.mvp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.actl.mvp.databinding.ActivityDebugBinding
import com.actl.mvp.startup.StartupRuntime
import com.actl.mvp.startup.service.ApiServerForegroundService
import kotlinx.coroutines.launch

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonDiscover.setOnClickListener {
            StartupRuntime.coordinator.restartDiscovery()
            toast("mDNS discovery restarted")
        }
        binding.buttonApplyApiPort.setOnClickListener {
            applyApiPort()
        }

        val savedPort = StartupRuntime.settings.apiServerPort
        binding.editApiPort.setText(savedPort.toString())
        binding.textCurrentApiPort.text = "Current port: $savedPort"

        observeDebugState()
    }

    private fun observeDebugState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    StartupRuntime.coordinator.state.collect { state ->
                        binding.textPairingEndpoint.text =
                            "Pairing endpoint: ${state.pairingEndpoint ?: "not found"}"
                        binding.textConnectEndpoint.text =
                            "Connect endpoint: ${state.connectEndpoint ?: "not found"}"
                    }
                }
                launch {
                    StartupRuntime.coordinator.logs.collect { logs ->
                        binding.textLogs.text = logs.joinToString(separator = "\n")
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyApiPort() {
        val value = binding.editApiPort.text?.toString()?.trim().orEmpty()
        val port = value.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast("Invalid port, please input 1-65535")
            return
        }

        StartupRuntime.settings.apiServerPort = port
        binding.textCurrentApiPort.text = "Current port: $port"
        ApiServerForegroundService.start(this, port)
        toast("API port updated to $port")
    }
}
