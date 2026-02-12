package com.actl.mvp.api.framework

import io.ktor.server.routing.Route

interface ApiDefinition {
    val name: String
    fun register(route: Route)
}
