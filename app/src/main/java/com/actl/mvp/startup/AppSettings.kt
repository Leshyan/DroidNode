package com.actl.mvp.startup

import android.content.Context
import androidx.core.content.edit
import com.actl.mvp.api.framework.ApiServer

class AppSettings(context: Context) {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var apiServerPort: Int
        get() {
            val value = preferences.getInt(KEY_API_SERVER_PORT, ApiServer.DEFAULT_PORT)
            return sanitizePort(value)
        }
        set(value) {
            preferences.edit { putInt(KEY_API_SERVER_PORT, sanitizePort(value)) }
        }

    private fun sanitizePort(value: Int): Int {
        return if (value in 1..65535) value else ApiServer.DEFAULT_PORT
    }

    companion object {
        private const val PREF_NAME = "actl_settings"
        private const val KEY_API_SERVER_PORT = "api_server_port"
    }
}
