package com.actl.mvp.api.framework

import com.actl.mvp.api.impl.ClickApi
import com.actl.mvp.api.impl.InputApi
import com.actl.mvp.api.impl.ScreenshotApi
import com.actl.mvp.api.impl.SwipeApi
import com.actl.mvp.api.impl.SystemInfoApi
import com.actl.mvp.api.impl.UiDumpApi

object ApiModuleRegistry {
    fun defaultModules(): List<ApiDefinition> {
        return listOf(
            SystemInfoApi(),
            ClickApi(),
            InputApi(),
            SwipeApi(),
            UiDumpApi(),
            ScreenshotApi()
        )
    }
}
