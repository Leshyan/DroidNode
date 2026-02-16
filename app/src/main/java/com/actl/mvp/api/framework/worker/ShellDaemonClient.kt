package com.actl.mvp.api.framework.worker

import android.os.Parcel

class ShellDaemonClient {

    fun execute(
        serviceName: String,
        shellCommand: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS // kept for call-site compatibility
    ): WorkerCallResult<String> {
        val safeServiceName = serviceName.trim()
        if (safeServiceName.isEmpty()) {
            return WorkerCallResult(success = false, detail = "service name is empty")
        }
        val command = shellCommand.trim()
        if (command.isEmpty()) {
            return WorkerCallResult(success = false, detail = "shell command is empty")
        }
        return transact(safeServiceName, ShellDaemonProtocol.TX_EXEC) { data ->
            data.writeString(command)
        }
    }

    fun ping(serviceName: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): WorkerCallResult<String> {
        val safeServiceName = serviceName.trim()
        if (safeServiceName.isEmpty()) {
            return WorkerCallResult(success = false, detail = "service name is empty")
        }
        return transact(safeServiceName, ShellDaemonProtocol.TX_PING)
    }

    fun stop(serviceName: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): WorkerCallResult<String> {
        val safeServiceName = serviceName.trim()
        if (safeServiceName.isEmpty()) {
            return WorkerCallResult(success = false, detail = "service name is empty")
        }
        return transact(safeServiceName, ShellDaemonProtocol.TX_STOP)
    }

    private inline fun transact(
        serviceName: String,
        transactionCode: Int,
        writeData: (Parcel) -> Unit = {}
    ): WorkerCallResult<String> {
        val binder = ShellWorkerRegistry.binder(serviceName)
            ?: return WorkerCallResult(success = false, detail = "binder service not found")

        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                writeData(data)
                val transacted = binder.transact(transactionCode, data, reply, 0)
                if (!transacted) {
                    return WorkerCallResult(success = false, detail = "binder transact returned false")
                }
                parseReply(reply)
            } finally {
                runCatching { data.recycle() }
                runCatching { reply.recycle() }
            }
        }.getOrElse { error ->
            ShellWorkerRegistry.remove(serviceName)
            WorkerCallResult(success = false, detail = "binder request failed: ${error.message}")
        }
    }

    private fun parseReply(reply: Parcel): WorkerCallResult<String> {
        if (reply.dataAvail() <= 0) {
            return WorkerCallResult(success = false, detail = "empty worker response")
        }
        val code = reply.readInt()
        val output = reply.readString().orEmpty()
        return if (code == 0) {
            WorkerCallResult(success = true, data = output, detail = "ok")
        } else {
            WorkerCallResult(success = false, detail = output.ifBlank { "exit=$code" })
        }
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MS = 1000
    }
}
