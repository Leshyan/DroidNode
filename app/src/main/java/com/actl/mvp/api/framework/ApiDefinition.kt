package com.actl.mvp.api.framework

import io.ktor.server.routing.Route

/**
 * API module contract.
 *
 * KSP registry requirements:
 * 1) concrete class (not abstract/interface)
 * 2) package starts with "com.actl.mvp.api."
 * 3) has a no-arg constructor
 * 4) route path is auto-generated from package path
 *    e.g. com.actl.mvp.api.v1.control.click -> /v1/control/click
 */
interface ApiDefinition {
    val name: String
    val path: String
        get() = ApiPathResolver.resolve(this::class.java)

    fun register(route: Route)
}
