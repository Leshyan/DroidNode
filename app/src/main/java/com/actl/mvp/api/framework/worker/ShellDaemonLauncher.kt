package com.actl.mvp.api.framework.worker

import com.actl.mvp.ActlApp
import com.actl.mvp.adb.session.AdbRuntime

class ShellDaemonLauncher {

    fun launch(
        socketName: String,
        client: ShellDaemonClient = ShellDaemonClient()
    ): WorkerCallResult<Unit> {
        val safeSocketName = socketName.trim()
        if (safeSocketName.isEmpty()) {
            return WorkerCallResult(success = false, detail = "socket name is empty")
        }

        synchronized(LAUNCH_LOCK) {
            val adb = AdbRuntime.adbManager
            var tempConnected = false

            val active = adb.executeShell("echo ACTL_CONNECTED")
            if (!active.success) {
                val endpoint = adb.lastConnectedEndpoint()
                if (endpoint == null) {
                    return WorkerCallResult(success = false, detail = "no cached adb endpoint")
                }
                val connect = adb.connect(endpoint.host, endpoint.port, keepAlive = true)
                if (!connect.success) {
                    return WorkerCallResult(success = false, detail = "adb connect failed: ${connect.message}")
                }
                tempConnected = true
            }

            return try {
                val apkPath = ActlApp.appContext.applicationInfo.sourceDir.orEmpty()
                val packageName = ActlApp.appContext.packageName.orEmpty()
                if (apkPath.isBlank()) {
                    return WorkerCallResult(success = false, detail = "apk sourceDir empty")
                }
                if (packageName.isBlank()) {
                    return WorkerCallResult(success = false, detail = "package name empty")
                }
                adb.executeShellRaw("rm -f ${shellQuote(DEBUG_FILE_PATH)}")
                val command =
                    "CLASSPATH=${shellQuote(apkPath)} " +
                        "app_process / ${ShellDaemonMain::class.java.name} " +
                        "${shellQuote(safeSocketName)} ${shellQuote(packageName)} " +
                        ">/dev/null 2>&1 &"
                val launched = adb.executeShellRaw(command)
                if (!launched.success) {
                    return WorkerCallResult(success = false, detail = "daemon launch failed: ${launched.message}")
                }

                var ping = client.ping(safeSocketName)
                if (!ping.success) {
                    for (attempt in 1..30) {
                        Thread.sleep(100)
                        ping = client.ping(safeSocketName)
                        if (ping.success) {
                            break
                        }
                    }
                }
                if (!ping.success) {
                    val debug = adb.executeShellRaw("cat ${shellQuote(DEBUG_FILE_PATH)} 2>/dev/null")
                    val debugLine = debug.message
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .lastOrNull()
                        .orEmpty()
                    val reason = if (debugLine.isNotBlank()) "$debugLine; " else ""
                    return WorkerCallResult(
                        success = false,
                        detail = "${reason}daemon launched but ping failed: ${ping.detail}"
                    )
                }

                ShellWorkerRegistry.markLaunched(safeSocketName)
                WorkerCallResult(success = true, detail = "daemon launch command sent")
            } finally {
                if (tempConnected) {
                    adb.disconnect()
                }
            }
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private companion object {
        private val LAUNCH_LOCK = Any()
        private const val DEBUG_FILE_PATH = "/data/local/tmp/droidnode_worker_last_error.txt"
    }
}
