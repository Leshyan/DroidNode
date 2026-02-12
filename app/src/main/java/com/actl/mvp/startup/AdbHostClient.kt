package com.actl.mvp.startup

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class AdbHostClient(
    private val adbServerHost: String = "127.0.0.1",
    private val adbServerPort: Int = 5037,
    private val timeoutMs: Int = 4_000
) {

    fun pair(host: String, port: Int, pairingCode: String): AdbCommandResult {
        val code = pairingCode.trim()
        if (code.isEmpty()) {
            return AdbCommandResult(false, "Pairing code is empty")
        }
        return sendCommand("host:pair:$host:$port:$code")
    }

    fun connect(host: String, port: Int): AdbCommandResult {
        return sendCommand("host:connect:$host:$port")
    }

    private fun sendCommand(command: String): AdbCommandResult {
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(adbServerHost, adbServerPort), timeoutMs)

                val output = socket.getOutputStream()
                val commandBytes = command.toByteArray(StandardCharsets.US_ASCII)
                val header = "%04x".format(commandBytes.size).toByteArray(StandardCharsets.US_ASCII)
                output.write(header)
                output.write(commandBytes)
                output.flush()

                val input = socket.getInputStream()
                val status = String(readExactly(input, 4), StandardCharsets.US_ASCII)
                val payload = readLengthPrefixedPayload(input)

                if (status == "OKAY") {
                    val normalized = payload.ifEmpty { "OKAY" }
                    AdbCommandResult(true, normalized)
                } else {
                    val normalized = payload.ifEmpty { "ADB command failed: $status" }
                    AdbCommandResult(false, normalized)
                }
            }
        }.getOrElse { error ->
            AdbCommandResult(false, error.message ?: "ADB host protocol error")
        }
    }

    private fun readLengthPrefixedPayload(input: InputStream): String {
        return runCatching {
            val lenRaw = String(readExactly(input, 4), StandardCharsets.US_ASCII)
            val length = lenRaw.toInt(16)
            if (length <= 0) {
                ""
            } else {
                String(readExactly(input, length), StandardCharsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF")
            }
            offset += read
        }
        return bytes
    }
}
