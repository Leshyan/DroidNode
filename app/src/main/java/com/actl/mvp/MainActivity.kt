package com.actl.mvp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.actl.mvp.databinding.ActivityMainBinding
import com.actl.mvp.startup.StartupRuntime
import com.actl.mvp.startup.WirelessStartupCoordinator
import com.actl.mvp.startup.service.ApiServerForegroundService
import com.actl.mvp.startup.service.PairingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val startupCoordinator: WirelessStartupCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        StartupRuntime.coordinator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNearbyPermissionIfNeeded()
        ensureNotificationPermissionIfNeeded()
        bindActions()

        startupCoordinator.restartDiscovery()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        startupCoordinator.restartDiscovery()
        refreshStatus()
    }

    private fun bindActions() {
        binding.buttonOpenDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        binding.buttonPairNow.setOnClickListener {
            PairingForegroundService.start(this)
            toast("Pair mode moved to notification")
        }

        binding.buttonDirectConnectNow.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                // If endpoint is not ready yet (e.g. first-run permission flow), retry discovery first.
                if (startupCoordinator.state.value.connectEndpoint == null) {
                    startupCoordinator.restartDiscovery()
                    delay(900)
                }
                val success = startupCoordinator.directConnect(hostOverride = null, portOverride = null)
                val endpointText = startupCoordinator.state.value.connectEndpoint?.toString() ?: "not found"

                launch(Dispatchers.Main) {
                    if (success) {
                        toast("Direct connect success")
                    } else {
                        toast("Direct connect failed (endpoint: $endpointText)")
                    }
                    refreshStatus()
                }
            }
        }

        binding.buttonDisconnectAdb.setOnClickListener {
            StartupRuntime.adbManager.disconnect()
            toast("ADB session disconnected")
            refreshStatus()
        }

        binding.buttonStartApi.setOnClickListener {
            ApiServerForegroundService.start(this, StartupRuntime.settings.apiServerPort)
            toast("API server service starting")
            lifecycleScope.launch {
                delay(450)
                refreshStatus()
            }
        }

        binding.buttonStopApi.setOnClickListener {
            ApiServerForegroundService.stop(this)
            toast("API server service stopping")
            lifecycleScope.launch {
                delay(450)
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiPort = StartupRuntime.settings.apiServerPort
            val adbAlive = StartupRuntime.adbManager.executeShell("echo ACTL_CONNECTED").success
            val apiAlive = isLocalApiAlive(apiPort)

            launch(Dispatchers.Main) {
                binding.textAdbSessionStatus.text =
                    if (adbAlive) "Connected" else "Disconnected"
                binding.textApiServerStatus.text =
                    if (apiAlive) "Running :$apiPort" else "Stopped"

                applyStatusBadge(binding.textAdbSessionStatus, adbAlive)
                applyStatusBadge(binding.textApiServerStatus, apiAlive)
            }
        }
    }

    private fun applyStatusBadge(view: TextView, online: Boolean) {
        if (online) {
            view.setBackgroundResource(R.drawable.bg_status_badge_on)
            view.setTextColor(0xFF4F3E64.toInt())
        } else {
            view.setBackgroundResource(R.drawable.bg_status_badge_off)
            view.setTextColor(0xFF7A6A8F.toInt())
        }
    }

    private fun isLocalApiAlive(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port),
                    350
                )
            }
            true
        }.getOrDefault(false)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NEARBY_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startupCoordinator.restartDiscovery()
        }
    }
}
