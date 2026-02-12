package com.actl.mvp.startup.directadb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val CURRENT_KEY_HEADER_VERSION: Byte = 1
private const val MIN_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
private const val MAX_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
private const val MAX_PEER_INFO_SIZE = 8192
private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
private const val PAIRING_PACKET_HEADER_SIZE = 6

private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
private const val EXPORTED_KEY_SIZE = 64
private const val TAG = "DirectAdbPairing"

private class PeerInfo(type: Byte, sourceData: ByteArray) {
    val type: Byte = type
    val data: ByteArray = ByteArray(MAX_PEER_INFO_SIZE - 1).also {
        sourceData.copyInto(it, endIndex = sourceData.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(type)
        buffer.put(data)
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(val version: Byte, val type: Byte, val payload: Int) {
    object Type {
        const val SPAKE2_MSG: Byte = 0
        const val PEER_INFO: Byte = 1
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(payload)
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                return null
            }
            if (type != Type.SPAKE2_MSG && type != Type.PEER_INFO) {
                return null
            }
            if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                return null
            }
            return PairingPacketHeader(version, type, payload)
        }
    }
}

private class PairingContext private constructor(private val nativePtr: Long) {
    val msg: ByteArray
        get() = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, input: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, input: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class DirectAdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: DirectAdbKey
) : Closeable {

    private enum class State {
        READY,
        EXCHANGING_MSGS,
        EXCHANGING_PEER_INFO,
        STOPPED
    }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private lateinit var pairingContext: PairingContext
    private val peerInfo = PeerInfo(0, key.adbPublicKey)
    private var state: State = State.READY

    fun start(): Boolean {
        setupTlsConnection()

        state = State.EXCHANGING_MSGS
        if (!doExchangeMsgs()) {
            state = State.STOPPED
            return false
        }

        state = State.EXCHANGING_PEER_INFO
        if (!doExchangePeerInfo()) {
            state = State.STOPPED
            return false
        }

        state = State.STOPPED
        return true
    }

    private fun setupTlsConnection() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true

        val sslSocket = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val codeBytes = pairCode.toByteArray()
        val keyMaterial = exportKeyMaterial(sslSocket)
        val password = ByteArray(codeBytes.size + keyMaterial.size)
        codeBytes.copyInto(password)
        keyMaterial.copyInto(password, codeBytes.size)

        val context = PairingContext.create(password)
            ?: throw DirectAdbException("Unable to create pairing context")
        pairingContext = context
    }

    private fun exportKeyMaterial(sslSocket: SSLSocket): ByteArray {
        val failures = mutableListOf<String>()

        runCatching {
            val method = sslSocket.javaClass.getMethod(
                "exportKeyingMaterial",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            val result = method.invoke(sslSocket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
            if (result is ByteArray) {
                return result
            }
            failures += "instance exportKeyingMaterial returned non-byte[]"
        }.onFailure {
            failures += "instance method: ${it.javaClass.simpleName}: ${it.message}"
        }

        runCatching {
            val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
            val candidates = conscrypt.declaredMethods.filter { method ->
                method.name == "exportKeyingMaterial" &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes[0].isAssignableFrom(sslSocket.javaClass)
            }
            if (candidates.isEmpty()) {
                throw NoSuchMethodException("Conscrypt.exportKeyingMaterial(...) not found")
            }
            for (method in candidates) {
                runCatching {
                    method.isAccessible = true
                    val result = method.invoke(null, sslSocket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
                    if (result is ByteArray) {
                        return result
                    }
                }
            }
            failures += "conscrypt exportKeyingMaterial returned non-byte[]"
        }.onFailure {
            failures += "conscrypt static method: ${it.javaClass.simpleName}: ${it.message}"
        }

        Log.w(TAG, "TLS key material export failed: ${failures.joinToString(" | ")}")
        throw DirectAdbException("Unable to export TLS key material for ADB pairing")
    }

    private fun createHeader(type: Byte, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(CURRENT_KEY_HEADER_VERSION, type, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(PAIRING_PACKET_HEADER_SIZE)
        inputStream.readFully(bytes)
        return PairingPacketHeader.readFrom(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        outputStream.write(buffer.array())
        outputStream.write(payload)
    }

    private fun doExchangeMsgs(): Boolean {
        val msg = pairingContext.msg
        writeHeader(createHeader(PairingPacketHeader.Type.SPAKE2_MSG, msg.size), msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG) {
            return false
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        return pairingContext.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): Boolean {
        val buffer = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buffer)

        val encrypted = pairingContext.encrypt(buffer.array()) ?: return false
        writeHeader(createHeader(PairingPacketHeader.Type.PEER_INFO, encrypted.size), encrypted)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO) {
            return false
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        val decrypted = pairingContext.decrypt(theirMessage) ?: throw DirectAdbInvalidPairingCodeException()
        if (decrypted.size != MAX_PEER_INFO_SIZE) {
            return false
        }

        PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        return true
    }

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }

        if (state != State.READY) {
            runCatching { pairingContext.destroy() }
        }
    }

    companion object {
        init {
            System.loadLibrary("adbpair")
        }
    }
}
