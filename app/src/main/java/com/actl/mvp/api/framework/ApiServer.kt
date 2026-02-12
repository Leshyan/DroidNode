package com.actl.mvp.api.framework

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

class ApiServer(
    private val modules: List<ApiDefinition>
) {

    private var server: ApplicationEngine? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) {
            return
        }
        server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/v1/health") {
                    call.respond(ApiResponse(code = 0, message = "ok", data = mapOf("status" to "up")))
                }
                modules.forEach { module -> module.register(this) }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
        server = null
    }

    companion object {
        const val DEFAULT_PORT = 17171
    }
}
