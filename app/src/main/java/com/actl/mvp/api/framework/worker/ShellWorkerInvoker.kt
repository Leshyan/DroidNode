package com.actl.mvp.api.framework.worker

class ShellWorkerInvoker(
    private val socketName: String,
    private val client: ShellDaemonClient = ShellDaemonClient(),
    private val launcher: ShellDaemonLauncher = ShellDaemonLauncher()
) {

    fun execute(requestCommand: String): WorkerCallResult<String> {
        val first = client.execute(serviceName = socketName, shellCommand = requestCommand)
        if (first.success) {
            return first
        }

        val launch = launcher.launch(socketName)
        if (!launch.success) {
            return WorkerCallResult(
                success = false,
                detail = "first call failed: ${first.detail}; launch failed: ${launch.detail}"
            )
        }

        val second = client.execute(serviceName = socketName, shellCommand = requestCommand)
        if (second.success) {
            return second
        }
        return WorkerCallResult(
            success = false,
            detail = "first call failed: ${first.detail}; second call failed: ${second.detail}"
        )
    }
}
