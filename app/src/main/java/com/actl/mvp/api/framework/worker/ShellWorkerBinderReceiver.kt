package com.actl.mvp.api.framework.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShellWorkerBinderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ShellDaemonProtocol.ACTION_WORKER_BINDER) {
            return
        }
        val data = intent.getBundleExtra(ShellDaemonProtocol.EXTRA_DATA) ?: return
        val workerName = data.getString(ShellDaemonProtocol.EXTRA_SERVICE_NAME).orEmpty().trim()
        if (workerName.isEmpty()) {
            return
        }
        val binder = data.getBinder(ShellDaemonProtocol.EXTRA_BINDER) ?: return
        ShellWorkerRegistry.attachBinder(workerName, binder)
    }
}

