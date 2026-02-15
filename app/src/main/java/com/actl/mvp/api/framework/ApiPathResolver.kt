package com.actl.mvp.api.framework

internal object ApiPathResolver {

    private const val API_PACKAGE_PREFIX = "com.actl.mvp.api."

    fun resolve(clazz: Class<*>): String {
        val pkg = clazz.packageName
        require(pkg.startsWith(API_PACKAGE_PREFIX)) {
            "Api module package must start with $API_PACKAGE_PREFIX, but was $pkg"
        }

        val segments = pkg
            .removePrefix(API_PACKAGE_PREFIX)
            .split('.')
            .filter { it.isNotBlank() }

        require(segments.isNotEmpty()) {
            "Api module package must contain versioned path segments, but was $pkg"
        }

        return "/" + segments.joinToString("/")
    }
}

