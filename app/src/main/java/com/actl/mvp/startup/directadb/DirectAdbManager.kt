package com.actl.mvp.startup.directadb

import android.content.Context
import android.os.Build
import com.actl.mvp.startup.AdbBinaryResult
import com.actl.mvp.startup.AdbCommandResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class DirectAdbManager(context: Context) {

    private val keyStore = PreferenceDirectAdbKeyStore(
        context.getSharedPreferences("direct_adb", Context.MODE_PRIVATE)
    )

    private val key by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbKey(keyStore, "actl")
    }

    fun pair(host: String, port: Int, pairCode: String): AdbCommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AdbCommandResult(false, "Wireless pairing requires Android 11+")
        }
        val code = pairCode.trim()
        if (code.isEmpty()) {
            return AdbCommandResult(false, "Pairing code is empty")
        }

        val resolvedHost = normalizeHost(host)
        return runCatching {
            DirectAdbPairingClient(resolvedHost, port, code, key).use { client ->
                if (client.start()) {
                    AdbCommandResult(true, "Pair success")
                } else {
                    AdbCommandResult(false, "Pair failed")
                }
            }
        }.getOrElse { error ->
            when (error) {
                is DirectAdbInvalidPairingCodeException -> AdbCommandResult(false, "Pair failed: invalid pairing code")
                else -> AdbCommandResult(false, "Pair failed: ${error.message}")
            }
        }
    }

    fun connect(host: String, port: Int, keepAlive: Boolean = true): AdbCommandResult {
        val resolvedHost = normalizeHost(host)
        return runCatching {
            val output = StringBuilder()
            val client = DirectAdbClient(resolvedHost, port, key)
            try {
                client.connect()
                client.shellCommand("echo ACTL_CONNECTED") { bytes ->
                    output.append(String(bytes))
                }
            } catch (throwable: Throwable) {
                runCatching { client.close() }
                throw throwable
            }
            if (keepAlive) {
                synchronized(activeClientLock) {
                    runCatching { sharedActiveClient?.close() }
                    sharedActiveClient = client
                }
            } else {
                runCatching { client.close() }
            }
            val text = output.toString().trim().ifEmpty { "Connected" }
            val message = if (keepAlive) "$text (session kept alive)" else text
            AdbCommandResult(true, message)
        }.getOrElse { error ->
            AdbCommandResult(false, "Connect failed: ${error.message}")
        }
    }

    fun executeShell(command: String): AdbCommandResult {
        if (command.isBlank()) {
            return AdbCommandResult(false, "Shell command is empty")
        }
        val active = synchronized(activeClientLock) { sharedActiveClient }
            ?: return AdbCommandResult(false, "No active adb connection")

        return withShellLock(
            onBusy = { AdbCommandResult(false, "ADB command busy") }
        ) {
            runCatching {
                val output = StringBuilder()
                active.shellCommand(command) { bytes ->
                    output.append(String(bytes))
                }
                AdbCommandResult(true, output.toString().trim())
            }.getOrElse { error ->
                AdbCommandResult(false, "Shell command failed: ${error.message}")
            }
        }
    }

    fun executeShellRaw(command: String): AdbCommandResult {
        if (command.isBlank()) {
            return AdbCommandResult(false, "Shell command is empty")
        }
        val active = synchronized(activeClientLock) { sharedActiveClient }
            ?: return AdbCommandResult(false, "No active adb connection")

        return withShellLock(
            onBusy = { AdbCommandResult(false, "ADB command busy") }
        ) {
            runCatching {
                val output = StringBuilder()
                active.shellCommand(command) { bytes ->
                    output.append(String(bytes))
                }
                AdbCommandResult(true, output.toString())
            }.getOrElse { error ->
                AdbCommandResult(false, "Shell command failed: ${error.message}")
            }
        }
    }

    fun executeExecRaw(command: String): AdbCommandResult {
        if (command.isBlank()) {
            return AdbCommandResult(false, "Exec command is empty")
        }
        val active = synchronized(activeClientLock) { sharedActiveClient }
            ?: return AdbCommandResult(false, "No active adb connection")

        return withShellLock(
            onBusy = { AdbCommandResult(false, "ADB command busy") }
        ) {
            runCatching {
                val output = StringBuilder()
                active.execCommand(command) { bytes ->
                    output.append(String(bytes))
                }
                AdbCommandResult(true, output.toString())
            }.getOrElse { error ->
                AdbCommandResult(false, "Exec command failed: ${error.message}")
            }
        }
    }

    fun pullFileText(path: String): AdbCommandResult {
        if (path.isBlank()) {
            return AdbCommandResult(false, "Pull path is empty")
        }
        val active = synchronized(activeClientLock) { sharedActiveClient }
            ?: return AdbCommandResult(false, "No active adb connection")

        return withShellLock(
            onBusy = { AdbCommandResult(false, "ADB command busy") }
        ) {
            runCatching {
                val bytes = active.pullFile(path)
                AdbCommandResult(true, String(bytes))
            }.getOrElse { error ->
                AdbCommandResult(false, "Pull file failed: ${error.message}")
            }
        }
    }

    fun pullFileBytes(path: String): AdbBinaryResult {
        if (path.isBlank()) {
            return AdbBinaryResult(success = false, message = "Pull path is empty")
        }
        val active = synchronized(activeClientLock) { sharedActiveClient }
            ?: return AdbBinaryResult(success = false, message = "No active adb connection")

        return withShellLock(
            onBusy = { AdbBinaryResult(success = false, message = "ADB command busy") }
        ) {
            runCatching {
                val bytes = active.pullFile(path)
                AdbBinaryResult(success = true, bytes = bytes, message = "ok")
            }.getOrElse { error ->
                AdbBinaryResult(success = false, message = "Pull file failed: ${error.message}")
            }
        }
    }

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            return LOOPBACK
        }
        return trimmed
    }

    fun disconnect() {
        synchronized(activeClientLock) {
            runCatching { sharedActiveClient?.close() }
            sharedActiveClient = null
        }
    }

    private inline fun <T> withShellLock(onBusy: () -> T, block: () -> T): T {
        val locked = try {
            shellCommandLock.tryLock(SHELL_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) {
            return onBusy()
        }
        return try {
            block()
        } finally {
            shellCommandLock.unlock()
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val SHELL_LOCK_WAIT_MS = 300L
        private val activeClientLock = Any()
        private val shellCommandLock = ReentrantLock(true)

        @Volatile
        private var sharedActiveClient: DirectAdbClient? = null
    }
}
