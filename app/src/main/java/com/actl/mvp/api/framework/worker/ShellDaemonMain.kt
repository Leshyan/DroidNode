package com.actl.mvp.api.framework.worker

import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch

class ShellDaemonMain {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val workerName = args.firstOrNull().orEmpty().trim()
            val targetPackage = args.getOrNull(1).orEmpty().trim()
            if (workerName.isEmpty() || targetPackage.isEmpty()) {
                return
            }
            val stopSignal = CountDownLatch(1)
            val service = WorkerBinder(stopSignal)
            val sent = sendBinderToApp(workerName, targetPackage, service)
            if (!sent.success) {
                writeDebug("sendBinder failed for '$workerName': ${sent.detail}")
                return
            }
            writeDebug("worker started: $workerName")
            stopSignal.await()
            writeDebug("worker stopped: $workerName")
        }

        private class WorkerBinder(
            private val stopSignal: CountDownLatch
        ) : Binder() {
            @Throws(RemoteException::class)
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                when (code) {
                    ShellDaemonProtocol.TX_PING -> {
                        writeResponse(reply, status = 0, output = "PONG")
                        return true
                    }

                    ShellDaemonProtocol.TX_EXEC -> {
                        val command = data.readString().orEmpty()
                        if (command.isBlank()) {
                            writeResponse(reply, status = 255, output = "empty exec command")
                            return true
                        }
                        val result = runShell(command)
                        writeResponse(reply, status = result.exitCode, output = result.output)
                        return true
                    }

                    ShellDaemonProtocol.TX_STOP -> {
                        writeResponse(reply, status = 0, output = "STOPPING")
                        stopSignal.countDown()
                        return true
                    }
                }
                return false
            }

            private fun runShell(command: String): CommandResult {
                return runCatching {
                    val process = ProcessBuilder("/system/bin/sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                    val exitCode = process.waitFor()
                    CommandResult(exitCode = exitCode, output = output)
                }.getOrElse { error ->
                    CommandResult(exitCode = 255, output = error.message.orEmpty())
                }
            }

            private fun writeResponse(reply: Parcel?, status: Int, output: String) {
                reply?.writeInt(status)
                reply?.writeString(output)
            }
        }

        private data class CommandResult(
            val exitCode: Int,
            val output: String
        )

        private fun writeDebug(message: String) {
            runCatching {
                File("/data/local/tmp/droidnode_worker_last_error.txt")
                    .appendText("$message\n")
            }
        }

        private fun sendBinderToApp(workerName: String, targetPackage: String, binder: IBinder): WorkerCallResult<Unit> {
            return try {
                ensureHiddenApiAccess()
                val intent = Intent(ShellDaemonProtocol.ACTION_WORKER_BINDER)
                    .setPackage(targetPackage)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                val payload = android.os.Bundle().apply {
                    putBinder(ShellDaemonProtocol.EXTRA_BINDER, binder)
                    putString(ShellDaemonProtocol.EXTRA_SERVICE_NAME, workerName)
                }
                intent.putExtra(ShellDaemonProtocol.EXTRA_DATA, payload)

                val amBinder = getSystemServiceBinder("activity")
                    ?: return WorkerCallResult(success = false, detail = "activity service is null")
                val activityManager = asActivityManager(amBinder)
                    ?: return WorkerCallResult(success = false, detail = "asInterface activity manager failed")
                val broadcast = invokeBroadcastIntent(activityManager, intent)
                if (!broadcast.success) {
                    return WorkerCallResult(success = false, detail = broadcast.detail)
                }
                WorkerCallResult(success = true, detail = "ok")
            } catch (error: Throwable) {
                WorkerCallResult(success = false, detail = "send binder failed: ${error.message}")
            }
        }

        private fun invokeBroadcastIntent(activityManager: Any, intent: Intent): WorkerCallResult<Unit> {
            val methods = activityManager.javaClass.methods
                .filter { it.name == "broadcastIntentWithFeature" || it.name == "broadcastIntent" }
                .sortedByDescending { it.parameterTypes.size }
            if (methods.isEmpty()) {
                return WorkerCallResult(success = false, detail = "broadcast method not found")
            }

            val errors = mutableListOf<String>()
            for (method in methods) {
                val params = buildBroadcastArgs(method, intent)
                val invoke = runCatching {
                    method.isAccessible = true
                    method.invoke(activityManager, *params)
                }
                if (invoke.isSuccess) {
                    return WorkerCallResult(success = true, detail = "ok")
                }
                val root = (invoke.exceptionOrNull() as? InvocationTargetException)?.targetException
                    ?: invoke.exceptionOrNull()
                errors += "${method.name}(${method.parameterTypes.size}): ${root?.javaClass?.name}: ${root?.message}"
            }
            return WorkerCallResult(success = false, detail = errors.joinToString(" | "))
        }

        private fun buildBroadcastArgs(method: Method, intent: Intent): Array<Any?> {
            val types = method.parameterTypes
            val args = arrayOfNulls<Any>(types.size)

            val intIndices = types.indices.filter { types[it] == Int::class.javaPrimitiveType }
            for (i in types.indices) {
                val type = types[i]
                args[i] = when {
                    type == Intent::class.java -> intent
                    type == Int::class.javaPrimitiveType -> {
                        val intPos = intIndices.indexOf(i)
                        when {
                            intPos == -1 -> 0
                            intPos == 0 -> 0
                            intPos == intIndices.lastIndex -> 0
                            else -> -1
                        }
                    }

                    type == Boolean::class.javaPrimitiveType -> false
                    type == String::class.java -> null
                    type == android.os.Bundle::class.java -> null
                    type.isArray && type.componentType == String::class.java -> null
                    else -> null
                }
            }
            return args
        }

        private fun asActivityManager(amBinder: IBinder): Any? {
            return runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    val stub = Class.forName("android.app.IActivityManager\$Stub")
                    val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                    asInterface.invoke(null, amBinder)
                } else {
                    val legacy = Class.forName("android.app.ActivityManagerNative")
                    val asInterface = legacy.getDeclaredMethod("asInterface", IBinder::class.java)
                    asInterface.invoke(null, amBinder)
                }
            }.getOrNull()
        }

        private fun getSystemServiceBinder(name: String): IBinder? {
            return runCatching {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                getService.invoke(null, name) as? IBinder
            }.getOrNull()
        }

        private fun ensureHiddenApiAccess() {
            if (hiddenApiChecked) {
                return
            }
            synchronized(this) {
                if (hiddenApiChecked) {
                    return
                }
                runCatching {
                    val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                    val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
                    val runtime = getRuntime.invoke(null)
                    val setExemptions = vmRuntimeClass.getDeclaredMethod(
                        "setHiddenApiExemptions",
                        Array<String>::class.java
                    )
                    setExemptions.invoke(
                        runtime,
                        arrayOf(
                            "Landroid/app/IActivityManager;",
                            "Landroid/os/ServiceManager;",
                            "Ldalvik/system/VMRuntime;"
                        )
                    )
                }
                hiddenApiChecked = true
            }
        }

        @Volatile
        private var hiddenApiChecked: Boolean = false
    }
}
