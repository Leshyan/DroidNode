package com.actl.mvp.api.v1.system.info.worker

import com.actl.mvp.api.framework.worker.ShellWorkerInvoker
import com.actl.mvp.api.framework.worker.WorkerCallResult

class SystemInfoShellWorker(
    private val invoker: ShellWorkerInvoker = ShellWorkerInvoker(socketName = SOCKET_NAME)
) {

    data class Snapshot(
        val connected: Boolean,
        val detail: String,
        val wmSuccess: Boolean,
        val wmRaw: String
    )

    fun collectSnapshot(): WorkerCallResult<Snapshot> {
        val probe = invoker.execute(PROBE_COMMAND)
        if (!probe.success) {
            return WorkerCallResult(success = false, detail = "probe failed: ${probe.detail}")
        }

        val wmPrimary = invoker.execute(WM_SIZE_COMMAND_PRIMARY)
        val wm = if (wmPrimary.success) wmPrimary else invoker.execute(WM_SIZE_COMMAND_FALLBACK)
        val wmSuccess = wm.success
        val wmRaw = if (wm.success) wm.data.orEmpty().trim() else wm.detail.trim()

        return WorkerCallResult(
            success = true,
            data = Snapshot(
                connected = true,
                detail = probe.data?.trim().orEmpty(),
                wmSuccess = wmSuccess,
                wmRaw = wmRaw
            ),
            detail = if (wmSuccess) "ok" else "wm size failed"
        )
    }

    private companion object {
        private const val SOCKET_NAME = "droidnode_worker_v1_system_info"
        private const val PROBE_COMMAND = "echo ACTL_CONNECTED"
        private const val WM_SIZE_COMMAND_PRIMARY = "/system/bin/wm size"
        private const val WM_SIZE_COMMAND_FALLBACK = "wm size"
    }
}
