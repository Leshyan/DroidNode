package com.actl.mvp.api.impl

import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.startup.directadb.DirectAdbManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

class UiDumpApi : ApiDefinition {
    override val name: String = "ui-xml"
    private val adbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }

    override fun register(route: Route) {
        route.post("/v1/ui/xml") {
            val shell = adbManager.executeShellRaw(buildShellDumpCommand(dumpPath))
            if (!shell.success) {
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = ApiResponse(
                        code = 50031,
                        message = shell.message,
                        data = UiDumpWithDebugResult(
                            xml = "",
                            debug = UiDumpDebug(
                                mode = "shell-dump-file-read",
                                dumpOutput = "",
                                fallbackDumpOutput = "",
                                pullOutput = shell.message,
                                pullSuccess = false,
                                hasXmlMarker = false,
                                xmlBytes = 0,
                                rawBytes = 0
                            )
                        )
                    )
                )
                return@post
            }

            val shellOutput = shell.message.trim()
            val dumpOk = shellOutput.contains(SHELL_DUMP_OK)
            val pulled = adbManager.pullFileText(dumpPath)
            val xml = if (pulled.success) pulled.message.trim() else ""
            val hasMarker = xml.contains(XML_MARKER)
            val data = UiDumpWithDebugResult(
                xml = "",
                debug = UiDumpDebug(
                    mode = "shell-dump-sync-read",
                    dumpOutput = shellOutput.take(DEBUG_PREVIEW_LIMIT),
                    fallbackDumpOutput = "",
                    pullOutput = pulled.message.take(DEBUG_PREVIEW_LIMIT),
                    pullSuccess = pulled.success,
                    hasXmlMarker = hasMarker,
                    xmlBytes = xml.length,
                    rawBytes = shellOutput.length
                )
            )

            if (!dumpOk) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(code = 50033, message = "UI dump command failed", data = data)
                )
                return@post
            }

            if (!pulled.success) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(code = 50034, message = "UI dump file read failed", data = data)
                )
                return@post
            }

            if (!hasMarker) {
                call.respond(
                    status = HttpStatusCode.InternalServerError,
                    message = ApiResponse(code = 50032, message = "UI dump did not return XML", data = data)
                )
                return@post
            }

            call.respondText(
                text = xml,
                contentType = ContentType.Application.Xml
            )
        }
    }

    private fun buildShellDumpCommand(path: String): String {
        return "if /system/bin/uiautomator dump '$path' >/dev/null 2>&1; then " +
            "echo '$SHELL_DUMP_OK'; " +
            "else echo '$SHELL_DUMP_FAILED'; fi"
    }

    private companion object {
        private const val dumpPath = "/data/local/tmp/actl_ui_dump.xml"
        private const val SHELL_DUMP_OK = "__ACTL_DUMP_OK__"
        private const val SHELL_DUMP_FAILED = "__ACTL_DUMP_CMD_FAILED__"
        private const val XML_MARKER = "<?xml"
        private const val DEBUG_PREVIEW_LIMIT = 500
    }
}

@Serializable
data class UiDumpWithDebugResult(
    val xml: String,
    val debug: UiDumpDebug
)

@Serializable
data class UiDumpDebug(
    val mode: String,
    val dumpOutput: String,
    val fallbackDumpOutput: String,
    val pullOutput: String,
    val pullSuccess: Boolean,
    val hasXmlMarker: Boolean,
    val xmlBytes: Int,
    val rawBytes: Int
)
