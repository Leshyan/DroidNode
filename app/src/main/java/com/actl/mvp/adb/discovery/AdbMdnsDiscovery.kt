package com.actl.mvp.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

class AdbMdnsDiscovery(
    context: Context,
    private val onState: (DiscoveryState) -> Unit,
    private val onLog: (String) -> Unit
) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    private var pairingEndpoint: AdbEndpoint? = null
    private var connectEndpoint: AdbEndpoint? = null
    private val localHostAddresses: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        collectLocalHostAddresses()
    }

    fun start() {
        stop()
        onLog("Starting mDNS discovery for ADB wireless debugging")
        pairingListener = buildListener(PAIRING_SERVICE_TYPE) { endpoint ->
            pairingEndpoint = endpoint
            publishState(true)
            onLog("Pairing endpoint found: $endpoint")
        }
        connectListener = buildListener(CONNECT_SERVICE_TYPE) { endpoint ->
            connectEndpoint = endpoint
            publishState(true)
            onLog("Connect endpoint found: $endpoint")
        }

        nsdManager.discoverServices(
            PAIRING_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            pairingListener
        )
        nsdManager.discoverServices(
            CONNECT_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            connectListener
        )
        publishState(true)
    }

    fun stop() {
        pairingListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        connectListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        pairingListener = null
        connectListener = null
        publishState(false)
    }

    private fun buildListener(
        serviceType: String,
        onResolved: (AdbEndpoint) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                onLog("mDNS start failed for $serviceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                onLog("mDNS stop failed for $serviceType: $errorCode")
            }

            override fun onDiscoveryStarted(type: String) {
                onLog("mDNS started for $serviceType")
            }

            override fun onDiscoveryStopped(type: String) {
                onLog("mDNS stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val normalizedFound = normalizeServiceType(serviceInfo.serviceType)
                val normalizedExpected = normalizeServiceType(serviceType)
                if (normalizedFound != normalizedExpected) {
                    return
                }
                resolve(serviceInfo, onResolved)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Wireless debugging pairing services are ephemeral and may disappear quickly.
                // Keep the last resolved endpoint cached so pairing can still proceed.
                publishState(true)
                onLog("mDNS service lost (cached): ${serviceInfo.serviceName} ($serviceType)")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo, onResolved: (AdbEndpoint) -> Unit) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    onLog("mDNS resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = pickHost(info.host)
                    if (host.isNullOrBlank()) {
                        onLog("Resolved service has no host: ${info.serviceName}")
                        return
                    }
                    if (!isLocalHost(host)) {
                        onLog("Ignoring non-local service: ${info.serviceName} -> $host:${info.port}")
                        return
                    }
                    onResolved(AdbEndpoint(host, info.port, info.serviceName ?: "unknown"))
                }
            }
        )
    }

    private fun pickHost(host: InetAddress?): String? {
        return host?.hostAddress?.takeIf { it.isNotBlank() }
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = normalizeHostAddress(host)
        return normalized == LOOPBACK_V4 ||
            normalized == LOOPBACK_V6 ||
            localHostAddresses.contains(normalized)
    }

    private fun collectLocalHostAddresses(): Set<String> {
        val addresses = mutableSetOf(LOOPBACK_V4, LOOPBACK_V6)
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                val inetAddresses = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val raw = inetAddresses.nextElement().hostAddress.orEmpty()
                    val normalized = normalizeHostAddress(raw)
                    if (normalized.isNotBlank()) {
                        addresses += normalized
                    }
                }
            }
        }.onFailure { error ->
            onLog("Local host detect failed: ${error.message}")
        }
        return addresses
    }

    private fun publishState(discovering: Boolean) {
        onState(
            DiscoveryState(
                pairingEndpoint = pairingEndpoint,
                connectEndpoint = connectEndpoint,
                discovering = discovering
            )
        )
    }

    companion object {
        private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
        private const val LOOPBACK_V4 = "127.0.0.1"
        private const val LOOPBACK_V6 = "::1"

        private fun normalizeServiceType(raw: String?): String {
            return raw.orEmpty().trim().trimEnd('.')
        }

        private fun normalizeHostAddress(raw: String?): String {
            return raw.orEmpty()
                .trim()
                .substringBefore('%')
                .lowercase(Locale.US)
        }
    }
}
