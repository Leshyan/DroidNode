package com.actl.mvp.api.framework

import com.actl.mvp.api.framework.worker.ShellDaemonClient
import com.actl.mvp.api.framework.worker.ShellWorkerRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

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
                post("/v1/workers/stop") {
                    val client = ShellDaemonClient()
                    val sockets = ShellWorkerRegistry.allSockets()
                    if (sockets.isEmpty()) {
                        call.respond(
                            ApiResponse(
                                code = 0,
                                message = "ok",
                                data = WorkersStopResponse(
                                    requested = 0,
                                    stopped = 0,
                                    alive = 0,
                                    results = emptyList()
                                )
                            )
                        )
                        return@post
                    }

                    val results = sockets.map { socketName ->
                        val pingBefore = client.ping(socketName)
                        val stop = client.stop(socketName)
                        var ping = client.ping(socketName)
                        if (ping.success) {
                            repeat(2) {
                                Thread.sleep(60)
                                ping = client.ping(socketName)
                            }
                        }
                        val stopped = !ping.success && (stop.success || !pingBefore.success)
                        if (stopped) {
                            ShellWorkerRegistry.remove(socketName)
                        }
                        WorkerStopItem(
                            socketName = socketName,
                            pingBeforeStopSuccess = pingBefore.success,
                            pingBeforeStopDetail = pingBefore.detail,
                            stopSuccess = stop.success,
                            stopDetail = stop.detail,
                            pingAfterStopSuccess = ping.success,
                            pingAfterStopDetail = ping.detail,
                            stopped = stopped
                        )
                    }

                    val stoppedCount = results.count { it.stopped }
                    val aliveCount = results.size - stoppedCount
                    val payload = WorkersStopResponse(
                        requested = results.size,
                        stopped = stoppedCount,
                        alive = aliveCount,
                        results = results
                    )

                    if (aliveCount == 0) {
                        call.respond(ApiResponse(code = 0, message = "ok", data = payload))
                    } else {
                        call.respond(
                            status = HttpStatusCode.ServiceUnavailable,
                            message = ApiResponse(
                                code = 50071,
                                message = "some workers are still alive after stop",
                                data = payload
                            )
                        )
                    }
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

@Serializable
data class WorkerStopItem(
    val socketName: String,
    val pingBeforeStopSuccess: Boolean,
    val pingBeforeStopDetail: String,
    val stopSuccess: Boolean,
    val stopDetail: String,
    val pingAfterStopSuccess: Boolean,
    val pingAfterStopDetail: String,
    val stopped: Boolean
)

@Serializable
data class WorkersStopResponse(
    val requested: Int,
    val stopped: Int,
    val alive: Int,
    val results: List<WorkerStopItem>
)
