package com.actl.mvp.startup

data class DiscoveryState(
    val pairingEndpoint: AdbEndpoint? = null,
    val connectEndpoint: AdbEndpoint? = null,
    val discovering: Boolean = false
)
