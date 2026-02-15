package com.actl.mvp.adb.discovery

data class AdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String
) {
    override fun toString(): String = "$host:$port ($serviceName)"
}
