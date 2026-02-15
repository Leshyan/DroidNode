package com.actl.mvp.adb.session

import com.actl.mvp.ActlApp

object AdbRuntime {
    val adbManager: DirectAdbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }
}
