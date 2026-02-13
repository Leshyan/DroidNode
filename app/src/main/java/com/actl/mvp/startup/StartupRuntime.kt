package com.actl.mvp.startup

import com.actl.mvp.ActlApp
import com.actl.mvp.startup.directadb.DirectAdbManager

object StartupRuntime {
    val coordinator: WirelessStartupCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WirelessStartupCoordinator(ActlApp.appContext)
    }

    val adbManager: DirectAdbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }

    val settings: AppSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppSettings(ActlApp.appContext)
    }
}
