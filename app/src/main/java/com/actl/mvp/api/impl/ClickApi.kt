package com.actl.mvp.api.impl

import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.startup.directadb.DirectAdbManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

class ClickApi : ApiDefinition {
    override val name: String = "click"
    private val adbManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DirectAdbManager(ActlApp.appContext)
    }

    override fun register(route: Route) {
        route.post("/v1/control/click") {
            val request = runCatching { call.receive<ClickRequest>() }.getOrElse {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40001, message = "Invalid click request payload")
                )
                return@post
            }

            if (request.x < 0 || request.y < 0) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = ApiResponse<Unit>(code = 40002, message = "x and y must be >= 0")
                )
                return@post
            }

            val command = "input tap ${request.x} ${request.y}"
            val result = adbManager.executeShell(command)
            if (!result.success) {
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = ApiResponse<Unit>(code = 50011, message = result.message)
                )
                return@post
            }

            call.respond(
                ApiResponse(
                    code = 0,
                    message = "ok",
                    data = ClickResult(command = command)
                )
            )
        }
    }
}

@Serializable
data class ClickRequest(
    val x: Int,
    val y: Int
)

@Serializable
data class ClickResult(
    val command: String
)
