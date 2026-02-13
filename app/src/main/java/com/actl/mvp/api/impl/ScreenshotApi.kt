package com.actl.mvp.api.impl

import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.startup.directadb.DirectAdbManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

class ScreenshotApi : ApiDefinition {
    override val name: String = "ui-screenshot"
    private val adbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }

    override fun register(route: Route) {
        // Primary screenshot API: binary stream response.
        route.post("/v1/ui/screenshot") {
            val capture = captureScreenshotBytes()
            if (!capture.success) {
                call.respond(
                    status = capture.status,
                    message = ApiResponse(
                        code = capture.code,
                        message = capture.message,
                        data = ScreenshotDebugResult(
                            dumpOutput = capture.dumpOutput,
                            pullOutput = capture.pullOutput,
                            bytes = 0
                        )
                    )
                )
                return@post
            }

            call.response.header("X-Actl-Image-Bytes", capture.bytes.size.toString())
            call.respondBytes(
                bytes = capture.bytes,
                contentType = ContentType.Image.PNG,
                status = HttpStatusCode.OK
            )
        }
    }

    private fun captureScreenshotBytes(): ScreenshotCaptureResult {
        val exec = adbManager.executeExecBytes(SCREENSHOT_EXEC_COMMAND)
        if (!exec.success) {
            return ScreenshotCaptureResult(
                success = false,
                status = HttpStatusCode.ServiceUnavailable,
                code = 50041,
                message = exec.message,
                dumpOutput = exec.message.trim(),
                pullOutput = "",
                bytes = ByteArray(0)
            )
        }

        val bytes = exec.bytes
        if (bytes.isEmpty()) {
            return ScreenshotCaptureResult(
                success = false,
                status = HttpStatusCode.InternalServerError,
                code = 50042,
                message = "Screenshot bytes is empty",
                dumpOutput = "",
                pullOutput = "",
                bytes = ByteArray(0)
            )
        }

        if (!isPng(bytes)) {
            val preview = bytes.copyOfRange(0, minOf(bytes.size, 96))
                .toString(Charsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
            return ScreenshotCaptureResult(
                success = false,
                status = HttpStatusCode.InternalServerError,
                code = 50043,
                message = "Screenshot output is not PNG",
                dumpOutput = preview,
                pullOutput = "",
                bytes = ByteArray(0)
            )
        }

        return ScreenshotCaptureResult(
            success = true,
            status = HttpStatusCode.OK,
            code = 0,
            message = "ok",
            dumpOutput = "",
            pullOutput = "",
            bytes = bytes
        )
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) {
            return false
        }
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
    }

    private companion object {
        private const val SCREENSHOT_EXEC_COMMAND = "/system/bin/screencap -p"
    }
}

private data class ScreenshotCaptureResult(
    val success: Boolean,
    val status: HttpStatusCode,
    val code: Int,
    val message: String,
    val dumpOutput: String,
    val pullOutput: String,
    val bytes: ByteArray
)

@Serializable
data class ScreenshotDebugResult(
    val dumpOutput: String,
    val pullOutput: String,
    val bytes: Int
)
