package com.actl.mvp

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ActlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                // Needed for Conscrypt.exportKeyingMaterial used by direct ADB pairing flow.
                HiddenApiBypass.setHiddenApiExemptions("")
            }.onFailure {
                Log.w(TAG, "HiddenApiBypass init failed: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ActlApp"
        lateinit var instance: ActlApp
            private set

        val appContext: Context
            get() = instance.applicationContext
    }
}
