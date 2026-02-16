package com.actl.mvp.api.framework.worker

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

object ShellWorkerRegistry {

    private val workerNames = ConcurrentHashMap.newKeySet<String>()
    private val binders = ConcurrentHashMap<String, IBinder>()

    fun markLaunched(workerName: String) {
        val safe = workerName.trim()
        if (safe.isNotEmpty()) {
            workerNames.add(safe)
        }
    }

    fun attachBinder(workerName: String, binder: IBinder) {
        val safe = workerName.trim()
        if (safe.isEmpty()) {
            return
        }
        binders[safe] = binder
        workerNames.add(safe)
        runCatching {
            binder.linkToDeath(
                {
                    binders.remove(safe)
                    workerNames.remove(safe)
                },
                0
            )
        }
    }

    fun binder(workerName: String): IBinder? {
        val safe = workerName.trim()
        if (safe.isEmpty()) {
            return null
        }
        return binders[safe]
    }

    fun remove(workerName: String) {
        val safe = workerName.trim()
        workerNames.remove(safe)
        binders.remove(safe)
    }

    fun allSockets(): List<String> {
        return workerNames.toList().sorted()
    }
}
