package com.actl.mvp.adb.legacy

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice

class DdmlibBridge {

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (!initialized) {
            AndroidDebugBridge.init(false)
            initialized = true
        }
    }

    fun waitForOnlineDevices(timeoutMs: Long = 5_000): List<IDevice> {
        ensureInitialized()
        val bridge = AndroidDebugBridge.createBridge() ?: return emptyList()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (!bridge.hasInitialDeviceList() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }

        return bridge.devices?.filter { it.isOnline }.orEmpty()
    }

    @Synchronized
    fun terminate() {
        if (initialized) {
            AndroidDebugBridge.terminate()
            initialized = false
        }
    }
}
