package com.actl.mvp.startup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress

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
                    onResolved(AdbEndpoint(host, info.port, info.serviceName ?: "unknown"))
                }
            }
        )
    }

    private fun pickHost(host: InetAddress?): String? {
        return host?.hostAddress?.takeIf { it.isNotBlank() }
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

        private fun normalizeServiceType(raw: String?): String {
            return raw.orEmpty().trim().trimEnd('.')
        }
    }
}
