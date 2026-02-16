package com.actl.mvp.api.framework.worker

object ShellDaemonProtocol {
    const val TX_PING = 1
    const val TX_EXEC = 2
    const val TX_STOP = 3
    const val ACTION_WORKER_BINDER = "com.actl.mvp.action.SHELL_WORKER_BINDER"
    const val EXTRA_DATA = "data"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_SERVICE_NAME = "service_name"
}
