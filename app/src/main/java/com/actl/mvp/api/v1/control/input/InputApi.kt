package com.actl.mvp.api.v1.control.input

import android.util.Base64
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.adb.session.AdbRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

class InputApi : ApiDefinition {
    override val name: String = "input"
    private val adbManager = AdbRuntime.adbManager

    override fun register(route: Route) {
        route.post(path) {
            val request = runCatching { call.receive<InputRequest>() }.getOrElse {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40031, message = "Invalid input request payload")
                )
                return@post
            }

            val text = request.text
            if (text.isEmpty()) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40032, message = "text must not be empty")
                )
                return@post
            }
            if (text.length > MAX_TEXT_LENGTH) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40033, message = "text too long")
                )
                return@post
            }

            val requestedEnterAction = request.enterAction.lowercase()
            if (requestedEnterAction !in SUPPORTED_ENTER_ACTIONS) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40035, message = "enterAction is invalid")
                )
                return@post
            }

            val effectiveEnterAction = if (request.pressEnter) {
                normalizeEnterAction(requestedEnterAction)
            } else {
                ENTER_ACTION_NONE
            }
            val imeResult = injectWithIme(text, effectiveEnterAction)

            if (imeResult.success) {
                call.respond(
                    ApiResponse(
                        code = 0,
                        message = "ok",
                        data = InputResult(
                            mode = "ime",
                            textLength = text.length,
                            pressEnter = effectiveEnterAction != ENTER_ACTION_NONE,
                            enterAction = effectiveEnterAction,
                            detail = imeResult.detail
                        )
                    )
                )
                return@post
            }

            call.respond(
                status = HttpStatusCode.ServiceUnavailable,
                message = ApiResponse<Unit>(
                    code = 50052,
                    message = "IME injection failed: ${imeResult.detail}"
                )
            )
        }
    }

    private fun injectWithIme(text: String, enterAction: String): InjectAttempt {
        val imeList = adbManager.executeShellRaw("ime list -a")
        if (!imeList.success) {
            return InjectAttempt(false, "ime list failed: ${imeList.message}")
        }
        if (!imeList.message.contains(ADB_IME_ID)) {
            return InjectAttempt(false, "ACTL IME not available: $ADB_IME_ID")
        }

        val originalImeResult = adbManager.executeShell("settings get secure default_input_method")
        if (!originalImeResult.success) {
            return InjectAttempt(false, "read current ime failed: ${originalImeResult.message}")
        }
        val originalIme = originalImeResult.message.trim()
        var switched = false

        try {
            if (originalIme != ADB_IME_ID) {
                val enableIme = adbManager.executeShell("ime enable $ADB_IME_ID")
                if (!enableIme.success) {
                    return InjectAttempt(false, "ime enable failed: ${enableIme.message}")
                }
                val setIme = adbManager.executeShell("ime set $ADB_IME_ID")
                if (!setIme.success) {
                    return InjectAttempt(false, "ime set failed: ${setIme.message}")
                }
                switched = true
                Thread.sleep(220)
                val currentAfterSet = readCurrentImeId()
                if (currentAfterSet == null) {
                    return InjectAttempt(false, "verify ime failed")
                }
                if (currentAfterSet != ADB_IME_ID) {
                    return InjectAttempt(false, "ime switch not applied, current=$currentAfterSet")
                }
            }

            val base64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val inject = adbManager.executeShellRaw(
                "am broadcast -a $IME_B64_ACTION --es msg ${shellQuote(base64Text)}"
            )
            if (!inject.success) {
                return InjectAttempt(false, "ime broadcast failed: ${inject.message}")
            }
            if (!inject.message.contains("Broadcast completed")) {
                return InjectAttempt(false, "ime broadcast not delivered: ${inject.message}")
            }

            if (enterAction != ENTER_ACTION_NONE) {
                val action = adbManager.executeShellRaw(
                    "am broadcast -a $IME_EDITOR_ACTION --es action ${shellQuote(enterAction)}"
                )
                if (!action.success) {
                    return InjectAttempt(false, "ime editor action failed: ${action.message}")
                }
                if (!action.message.contains("Broadcast completed")) {
                    return InjectAttempt(false, "ime editor action not delivered: ${action.message}")
                }
            }
            Thread.sleep(220)

            return InjectAttempt(success = true, detail = "ACTL IME broadcast")
        } finally {
            if (switched && originalIme.isNotBlank()) {
                adbManager.executeShell("ime set $originalIme")
            }
        }
    }

    private fun readCurrentImeId(): String? {
        val current = adbManager.executeShell("settings get secure default_input_method")
        if (!current.success) {
            return null
        }
        val value = current.message.trim()
        if (value.isBlank()) {
            return null
        }
        return value
    }

    private fun normalizeEnterAction(action: String): String {
        if (action == ENTER_ACTION_NONE) {
            return ENTER_ACTION_AUTO
        }
        return action
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private data class InjectAttempt(
        val success: Boolean,
        val detail: String
    )

    private companion object {
        private const val MAX_TEXT_LENGTH = 4096
        private const val ADB_IME_ID = "com.actl.mvp/.ime.ActlImeService"
        private const val IME_B64_ACTION = "com.actl.mvp.action.ADB_INPUT_B64"
        private const val IME_EDITOR_ACTION = "com.actl.mvp.action.ADB_EDITOR_ACTION"
        private const val ENTER_ACTION_NONE = "none"
        private const val ENTER_ACTION_AUTO = "auto"
        private val SUPPORTED_ENTER_ACTIONS = setOf(
            "auto",
            "search",
            "send",
            "done",
            "go",
            "next",
            "enter",
            "none"
        )
    }
}

@Serializable
data class InputRequest(
    val text: String,
    val pressEnter: Boolean = false,
    val enterAction: String = "auto"
)

@Serializable
data class InputResult(
    val mode: String,
    val textLength: Int,
    val pressEnter: Boolean,
    val enterAction: String,
    val detail: String
)
