package com.actl.mvp.api.v1.control.swipe

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

class SwipeApi : ApiDefinition {
    override val name: String = "swipe"
    private val adbManager = AdbRuntime.adbManager

    override fun register(route: Route) {
        route.post(path) {
            val request = runCatching { call.receive<SwipeRequest>() }.getOrElse {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40011, message = "Invalid swipe request payload")
                )
                return@post
            }

            if (request.startX < 0 || request.startY < 0 || request.endX < 0 || request.endY < 0) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40012, message = "coordinates must be >= 0")
                )
                return@post
            }

            val duration = request.durationMs.coerceIn(1, 60_000)
            val command = "input swipe ${request.startX} ${request.startY} ${request.endX} ${request.endY} $duration"
            val result = adbManager.executeShell(command)
            if (!result.success) {
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = ApiResponse<Unit>(code = 50021, message = result.message)
                )
                return@post
            }

            call.respond(
                ApiResponse(
                    code = 0,
                    message = "ok",
                    data = SwipeResult(command = command, durationMs = duration)
                )
            )
        }
    }
}

@Serializable
data class SwipeRequest(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Int = 300
)

@Serializable
data class SwipeResult(
    val command: String,
    val durationMs: Int
)
