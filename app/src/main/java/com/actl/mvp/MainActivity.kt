package com.actl.mvp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.actl.mvp.databinding.ActivityMainBinding
import com.actl.mvp.startup.WirelessStartupCoordinator
import com.actl.mvp.startup.service.ApiServerForegroundService
import com.actl.mvp.startup.service.PairingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var startupCoordinator: WirelessStartupCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startupCoordinator = WirelessStartupCoordinator(applicationContext)
        ensureNearbyPermissionIfNeeded()
        ensureNotificationPermissionIfNeeded()
        bindActions()
        observeState()

        startupCoordinator.restartDiscovery()
    }

    override fun onDestroy() {
        startupCoordinator.shutdown()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.buttonDiscover.setOnClickListener {
            startupCoordinator.restartDiscovery()
            toast("mDNS discovery restarted")
        }

        binding.buttonExecuteStartup.setOnClickListener {
            if (binding.radioPair.isChecked) {
                PairingForegroundService.start(this)
                toast("Pair mode moved to notification. Open wireless debugging, then input pair code in notification action.")
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val host = binding.editHost.text?.toString()
                val port = binding.editPort.text?.toString()?.toIntOrNull()
                val success = startupCoordinator.directConnect(host, port)

                launch(Dispatchers.Main) {
                    toast(if (success) "Direct connect success" else "Direct connect failed")
                }
            }
        }

        binding.buttonStartApi.setOnClickListener {
            ApiServerForegroundService.start(this)
            toast("API server service starting")
        }

        binding.buttonStopApi.setOnClickListener {
            ApiServerForegroundService.stop(this)
            toast("API server service stopping")
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    startupCoordinator.state.collect { state ->
                        binding.textPairingEndpoint.text =
                            "Pairing endpoint: ${state.pairingEndpoint ?: "not found"}"
                        binding.textConnectEndpoint.text =
                            "Connect endpoint: ${state.connectEndpoint ?: "not found"}"
                    }
                }
                launch {
                    startupCoordinator.logs.collect { logs ->
                        binding.textLogs.text = logs.joinToString(separator = "\n")
                    }
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun ensureNearbyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            REQUEST_NEARBY_PERMISSION
        )
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    companion object {
        private const val REQUEST_NEARBY_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
