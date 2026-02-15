package com.actl.mvp.adb.core

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

class DirectAdbClient(
    private val host: String,
    private val port: Int,
    private val key: DirectAdbKey
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream
    private var nextLocalId = 1

    fun connect() {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(DirectAdbProtocol.A_CNXN, DirectAdbProtocol.A_VERSION, DirectAdbProtocol.A_MAXDATA, "host::")

        var message = read()
        if (message.command == DirectAdbProtocol.A_STLS) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw DirectAdbException("TLS connect to adbd requires Android 9+")
            }

            write(DirectAdbProtocol.A_STLS, DirectAdbProtocol.A_STLS_VERSION, 0)
            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.soTimeout = SOCKET_READ_TIMEOUT_MS
            tlsSocket.startHandshake()
            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == DirectAdbProtocol.A_AUTH) {
            if (message.arg0 != DirectAdbProtocol.ADB_AUTH_TOKEN) {
                throw DirectAdbException("Unexpected AUTH mode ${message.arg0}")
            }

            write(DirectAdbProtocol.A_AUTH, DirectAdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
            message = read()
            if (message.command != DirectAdbProtocol.A_CNXN) {
                write(DirectAdbProtocol.A_AUTH, DirectAdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != DirectAdbProtocol.A_CNXN) {
            throw DirectAdbException("ADB handshake failed: expected CNXN, got ${message.command}")
        }
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)? = null) {
        runAdbService("shell:$command", listener)
    }

    fun execCommand(command: String, listener: ((ByteArray) -> Unit)? = null) {
        runAdbService("exec:$command", listener)
    }

    fun pullFile(path: String): ByteArray {
        if (path.isBlank()) throw DirectAdbException("Pull path is empty")

        val localId = acquireLocalId()
        write(DirectAdbProtocol.A_OPEN, localId, 0, "sync:")

        var remoteId = 0
        while (true) {
            val message = read()
            if (message.arg1 != localId) {
                handleForeignPacket(message)
                continue
            }

            when (message.command) {
                DirectAdbProtocol.A_OKAY -> {
                    remoteId = message.arg0
                    break
                }

                DirectAdbProtocol.A_WRTE -> {
                    write(DirectAdbProtocol.A_OKAY, localId, message.arg0)
                }

                DirectAdbProtocol.A_CLSE -> {
                    write(DirectAdbProtocol.A_CLSE, localId, message.arg0)
                    throw DirectAdbException("Sync OPEN closed by remote")
                }

                else -> throw DirectAdbException("Unexpected sync OPEN response ${message.command}")
            }
        }

        val requestPayload = encodeSyncRequest("RECV", path.toByteArray())
        write(DirectAdbProtocol.A_WRTE, localId, remoteId, requestPayload)

        val fileOut = ByteArrayOutputStream()
        var pending = ByteArray(0)
        var done = false

        while (true) {
            val message = read()
            if (message.arg1 != localId) {
                handleForeignPacket(message)
                continue
            }
            when (message.command) {
                DirectAdbProtocol.A_WRTE -> {
                    val data = message.data ?: ByteArray(0)
                    pending = appendBytes(pending, data)
                    val parsed = parseSyncPackets(pending)
                    pending = parsed.remaining

                    parsed.packets.forEach { packet ->
                        when (packet.id) {
                            "DATA" -> fileOut.write(packet.payload)
                            "DONE" -> done = true
                            "FAIL" -> throw DirectAdbException("sync pull failed: ${packet.payload.toString(Charsets.UTF_8)}")
                            else -> throw DirectAdbException("Unknown sync packet id ${packet.id}")
                        }
                    }

                    write(DirectAdbProtocol.A_OKAY, localId, remoteId)
                    if (done) {
                        // Some adbd variants do not close sync stream promptly after DONE.
                        // Close proactively to avoid hanging this request.
                        write(DirectAdbProtocol.A_CLSE, localId, remoteId)
                        return fileOut.toByteArray()
                    }
                }

                DirectAdbProtocol.A_CLSE -> {
                    if (pending.isNotEmpty()) {
                        val parsed = parseSyncPackets(pending)
                        parsed.packets.forEach { packet ->
                            when (packet.id) {
                                "DATA" -> fileOut.write(packet.payload)
                                "DONE" -> done = true
                                "FAIL" -> throw DirectAdbException("sync pull failed: ${packet.payload.toString(Charsets.UTF_8)}")
                            }
                        }
                    }
                    write(DirectAdbProtocol.A_CLSE, localId, remoteId)
                    if (done || fileOut.size() > 0) {
                        return fileOut.toByteArray()
                    }
                    throw DirectAdbException("Sync channel closed before DONE")
                }

                DirectAdbProtocol.A_OKAY -> {
                    // Flow-control ack, ignore.
                }

                else -> throw DirectAdbException("Unexpected sync response ${message.command}")
            }
        }
    }

    private fun runAdbService(service: String, listener: ((ByteArray) -> Unit)? = null) {
        val localId = acquireLocalId()
        write(DirectAdbProtocol.A_OPEN, localId, 0, service)

        while (true) {
            val message = read()
            if (message.arg1 != localId) {
                handleForeignPacket(message)
                continue
            }

            val remoteId = message.arg0
            when (message.command) {
                DirectAdbProtocol.A_OKAY -> {
                    // Flow-control ack, ignore.
                }

                DirectAdbProtocol.A_WRTE -> {
                    if (message.dataLength > 0) {
                        listener?.invoke(message.data ?: ByteArray(0))
                    }
                    write(DirectAdbProtocol.A_OKAY, localId, remoteId)
                }

                DirectAdbProtocol.A_CLSE -> {
                    write(DirectAdbProtocol.A_CLSE, localId, remoteId)
                    break
                }

                else -> throw DirectAdbException("Unexpected OPEN response ${message.command}")
            }
        }
    }

    private fun handleForeignPacket(message: DirectAdbMessage) {
        val foreignLocalId = message.arg1
        val foreignRemoteId = message.arg0
        when (message.command) {
            DirectAdbProtocol.A_WRTE -> {
                write(DirectAdbProtocol.A_OKAY, foreignLocalId, foreignRemoteId)
            }

            DirectAdbProtocol.A_CLSE -> {
                write(DirectAdbProtocol.A_CLSE, foreignLocalId, foreignRemoteId)
            }
        }
    }

    private data class SyncPacket(val id: String, val payload: ByteArray)

    private data class ParsedSyncPackets(
        val packets: List<SyncPacket>,
        val remaining: ByteArray
    )

    private fun encodeSyncRequest(id: String, payload: ByteArray): ByteArray {
        require(id.length == 4) { "sync id must be 4 chars" }
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(id.toByteArray(Charsets.US_ASCII))
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    private fun parseSyncPackets(input: ByteArray): ParsedSyncPackets {
        val packets = mutableListOf<SyncPacket>()
        var cursor = 0
        while (input.size - cursor >= 8) {
            val id = String(input, cursor, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(input, cursor + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0) {
                throw DirectAdbException("Invalid sync packet length $len")
            }
            val frameSize = 8 + len
            if (input.size - cursor < frameSize) {
                break
            }
            val payload = input.copyOfRange(cursor + 8, cursor + frameSize)
            packets += SyncPacket(id, payload)
            cursor += frameSize
        }
        val remaining = if (cursor >= input.size) ByteArray(0) else input.copyOfRange(cursor, input.size)
        return ParsedSyncPackets(packets, remaining)
    }

    private fun appendBytes(existing: ByteArray, more: ByteArray): ByteArray {
        if (existing.isEmpty()) return more
        if (more.isEmpty()) return existing
        val out = ByteArray(existing.size + more.size)
        existing.copyInto(out, 0)
        more.copyInto(out, existing.size)
        return out
    }

    private fun acquireLocalId(): Int {
        val id = nextLocalId
        nextLocalId = if (nextLocalId == Int.MAX_VALUE) 1 else nextLocalId + 1
        return id
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        write(DirectAdbMessage(command, arg0, arg1, data))
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        write(DirectAdbMessage(command, arg0, arg1, data))
    }

    private fun write(message: DirectAdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
    }

    private fun read(): DirectAdbMessage {
        try {
            val header = ByteBuffer.allocate(DirectAdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            inputStream.readFully(header.array(), 0, DirectAdbMessage.HEADER_LENGTH)

            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val dataLength = header.int
            val dataCrc = header.int
            val magic = header.int

            val data = if (dataLength > 0) {
                ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
            } else {
                null
            }

            return DirectAdbMessage(command, arg0, arg1, dataLength, dataCrc, magic, data).also {
                it.validateOrThrow()
            }
        } catch (_: SocketTimeoutException) {
            throw DirectAdbException("ADB socket read timeout")
        }
    }

    override fun close() {
        runCatching { plainInputStream.close() }
        runCatching { plainOutputStream.close() }
        runCatching { socket.close() }

        if (useTls) {
            runCatching { tlsInputStream.close() }
            runCatching { tlsOutputStream.close() }
            runCatching { tlsSocket.close() }
        }
    }

    private companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 8_000
    }
}
