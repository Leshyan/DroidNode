package com.actl.mvp.adb.discovery

data class DiscoveryState(
    val pairingEndpoint: AdbEndpoint? = null,
    val connectEndpoint: AdbEndpoint? = null,
    val discovering: Boolean = false
)
