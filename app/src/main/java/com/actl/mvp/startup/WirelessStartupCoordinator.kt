package com.actl.mvp.startup

import android.content.Context
import com.actl.mvp.startup.directadb.DirectAdbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WirelessStartupCoordinator(context: Context) {

    private val directAdbManager = DirectAdbManager(context)

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val _logs = MutableStateFlow(listOf("Startup coordinator ready"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val discovery = AdbMdnsDiscovery(
        context = context,
        onState = { discoveredState -> _state.value = discoveredState },
        onLog = { message -> appendLog(message) }
    )

    fun restartDiscovery() {
        discovery.start()
    }

    fun pairThenConnect(pairCode: String): Boolean {
        val current = state.value
        val pairing = current.pairingEndpoint
        val connect = current.connectEndpoint

        if (pairing == null) {
            appendLog("Cannot pair: pairing endpoint not discovered")
            return false
        }
        if (connect == null) {
            appendLog("Cannot connect: connect endpoint not discovered")
            return false
        }

        appendLog("Pairing with ${pairing.host}:${pairing.port} (direct adbd)")
        val pairResult = directAdbManager.pair(pairing.host, pairing.port, pairCode)
        appendLog("Pair result: ${pairResult.message}")
        if (!pairResult.success) {
            return false
        }

        appendLog("Connecting with ${connect.host}:${connect.port} (direct adbd)")
        val connectResult = directAdbManager.connect(connect.host, connect.port)
        appendLog("Connect result: ${connectResult.message}")
        return connectResult.success
    }

    fun directConnect(hostOverride: String?, portOverride: Int?): Boolean {
        val endpoint = resolveConnectEndpoint(hostOverride, portOverride)
        if (endpoint == null) {
            appendLog("Direct connect failed: no endpoint available")
            return false
        }

        appendLog("Direct connect to ${endpoint.host}:${endpoint.port}")
        val result = directAdbManager.connect(endpoint.host, endpoint.port)
        appendLog("Direct connect result: ${result.message}")
        return result.success
    }

    fun shutdown() {
        discovery.stop()
    }

    private fun resolveConnectEndpoint(hostOverride: String?, portOverride: Int?): AdbEndpoint? {
        val host = hostOverride?.trim().orEmpty()
        val port = portOverride ?: -1
        if (host.isNotEmpty() && port > 0) {
            return AdbEndpoint(host = host, port = port, serviceName = "manual")
        }
        return state.value.connectEndpoint
    }

    private fun appendLog(line: String) {
        val timestamp = timeFormatter.format(Date())
        _logs.update { logs ->
            (logs + "[$timestamp] $line").takeLast(MAX_LOG_LINES)
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 200
        private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
