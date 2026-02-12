package com.actl.mvp.startup.directadb

import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectAdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?
) {

    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command,
        arg0,
        arg1,
        "$data\u0000".toByteArray()
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        crc32(data),
        (command.toLong() xor 0xffffffffL).toInt(),
        data
    )

    fun validateOrThrow() {
        val commandMatches = command == (magic xor -0x1)
        val crcMatches = dataLength == 0 || crc32(data) == dataCrc32
        if (!commandMatches || !crcMatches) {
            throw IllegalArgumentException("Bad message command=$command arg0=$arg0 arg1=$arg1")
        }
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataCrc32)
            putInt(magic)
            data?.let { put(it) }
        }.array()
    }

    companion object {
        const val HEADER_LENGTH = 24

        private fun crc32(bytes: ByteArray?): Int {
            if (bytes == null) {
                return 0
            }
            var result = 0
            bytes.forEach { b ->
                result += if (b >= 0) b.toInt() else b + 256
            }
            return result
        }
    }
}
