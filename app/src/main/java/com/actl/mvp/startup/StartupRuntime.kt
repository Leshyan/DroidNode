package com.actl.mvp.startup

import com.actl.mvp.ActlApp
import com.actl.mvp.adb.session.AdbRuntime
import com.actl.mvp.adb.session.DirectAdbManager

object StartupRuntime {
    val coordinator: WirelessStartupCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WirelessStartupCoordinator(ActlApp.appContext)
    }

    val adbManager: DirectAdbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AdbRuntime.adbManager
    }

    val settings: AppSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppSettings(ActlApp.appContext)
    }
}
