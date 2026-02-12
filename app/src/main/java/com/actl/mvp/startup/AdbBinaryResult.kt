package com.actl.mvp.startup

data class AdbBinaryResult(
    val success: Boolean,
    val bytes: ByteArray = ByteArray(0),
    val message: String
)

