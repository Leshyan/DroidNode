package com.actl.mvp.api.impl

import android.util.Base64
import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.startup.directadb.DirectAdbManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

class ScreenshotApi : ApiDefinition {
    override val name: String = "ui-screenshot"
    private val adbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }

    override fun register(route: Route) {
        route.post("/v1/ui/screenshot") {
            val dump = adbManager.executeShellRaw(buildScreenshotCommand(screenshotPath))
            if (!dump.success) {
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = ApiResponse<Unit>(code = 50041, message = dump.message)
                )
                return@post
            }
            if (!dump.message.contains(SCREENSHOT_OK)) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(
                        code = 50042,
                        message = "Screenshot command failed",
                        data = ScreenshotDebugResult(dumpOutput = dump.message.trim(), pullOutput = "", bytes = 0)
                    )
                )
                return@post
            }

            val pulled = adbManager.pullFileBytes(screenshotPath)
            if (!pulled.success) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(
                        code = 50043,
                        message = pulled.message,
                        data = ScreenshotDebugResult(
                            dumpOutput = dump.message.trim(),
                            pullOutput = pulled.message,
                            bytes = 0
                        )
                    )
                )
                return@post
            }

            val bytes = pulled.bytes
            if (bytes.isEmpty()) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(
                        code = 50044,
                        message = "Screenshot bytes is empty",
                        data = ScreenshotDebugResult(
                            dumpOutput = dump.message.trim(),
                            pullOutput = pulled.message,
                            bytes = 0
                        )
                    )
                )
                return@post
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            call.respond(
                ApiResponse(
                    code = 0,
                    message = "ok",
                    data = ScreenshotResult(
                        mimeType = "image/png",
                        bytes = bytes.size,
                        imageBase64 = base64
                    )
                )
            )
        }
    }

    private fun buildScreenshotCommand(path: String): String {
        return "if /system/bin/screencap -p '$path' >/dev/null 2>&1; then " +
            "echo '$SCREENSHOT_OK'; " +
            "else echo '$SCREENSHOT_FAILED'; fi"
    }

    private companion object {
        private const val screenshotPath = "/data/local/tmp/actl_screenshot.png"
        private const val SCREENSHOT_OK = "__ACTL_SCREEN_OK__"
        private const val SCREENSHOT_FAILED = "__ACTL_SCREEN_FAILED__"
    }
}

@Serializable
data class ScreenshotResult(
    val mimeType: String,
    val bytes: Int,
    val imageBase64: String
)

@Serializable
data class ScreenshotDebugResult(
    val dumpOutput: String,
    val pullOutput: String,
    val bytes: Int
)

