package com.actl.mvp.api.v1.system.info

import android.os.Build
import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.api.v1.system.info.worker.SystemInfoShellWorker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

class SystemInfoApi : ApiDefinition {
    override val name: String = "system_info"

    private val systemInfoWorker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SystemInfoShellWorker()
    }

    override fun register(route: Route) {
        route.get(path) {
            val snapshot = systemInfoWorker.collectSnapshot()
            if (!snapshot.success || snapshot.data == null) {
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = ApiResponse<Unit>(
                        code = 50061,
                        message = "system-info shell worker unavailable: ${snapshot.detail}"
                    )
                )
                return@get
            }

            val display = resolveDisplayInfo(snapshot.data)
            val clickRange = ClickRange(
                xMin = 0,
                yMin = 0,
                xMax = if (display.widthPx > 0) display.widthPx - 1 else 0,
                yMax = if (display.heightPx > 0) display.heightPx - 1 else 0
            )

            val data = SystemInfoResult(
                device = DeviceInfo(
                    manufacturer = Build.MANUFACTURER,
                    brand = Build.BRAND,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    product = Build.PRODUCT,
                    hardware = Build.HARDWARE,
                    board = Build.BOARD,
                    fingerprint = Build.FINGERPRINT
                ),
                android = AndroidInfo(
                    release = Build.VERSION.RELEASE.orEmpty(),
                    sdkInt = Build.VERSION.SDK_INT,
                    securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
                    buildId = Build.ID.orEmpty(),
                    incremental = Build.VERSION.INCREMENTAL.orEmpty(),
                    supportedAbis = Build.SUPPORTED_ABIS.toList()
                ),
                display = display,
                clickRange = clickRange,
                adbSession = AdbSessionInfo(
                    connected = snapshot.data.connected,
                    detail = snapshot.data.detail
                ),
                collectedAtMs = System.currentTimeMillis()
            )
            call.respond(ApiResponse(code = 0, message = "ok", data = data))
        }
    }

    private fun resolveDisplayInfo(snapshot: SystemInfoShellWorker.Snapshot): DisplayInfo {
        if (snapshot.wmSuccess) {
            val parsed = parseWmSize(snapshot.wmRaw)
            if (parsed != null) {
                return DisplayInfo(
                    widthPx = parsed.first,
                    heightPx = parsed.second,
                    source = "adb_wm_size",
                    raw = snapshot.wmRaw
                )
            }
        }

        val dm = ActlApp.appContext.resources.displayMetrics
        return DisplayInfo(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            source = "resources_display_metrics",
            raw = snapshot.wmRaw
        )
    }

    private fun parseWmSize(raw: String): Pair<Int, Int>? {
        val overrideMatch = WM_OVERRIDE_REGEX.find(raw)
        if (overrideMatch != null) {
            val w = overrideMatch.groupValues[1].toIntOrNull()
            val h = overrideMatch.groupValues[2].toIntOrNull()
            if (w != null && h != null) {
                return w to h
            }
        }

        val physicalMatch = WM_PHYSICAL_REGEX.find(raw)
        if (physicalMatch != null) {
            val w = physicalMatch.groupValues[1].toIntOrNull()
            val h = physicalMatch.groupValues[2].toIntOrNull()
            if (w != null && h != null) {
                return w to h
            }
        }

        val generic = WM_GENERIC_REGEX.find(raw)
        if (generic != null) {
            val w = generic.groupValues[1].toIntOrNull()
            val h = generic.groupValues[2].toIntOrNull()
            if (w != null && h != null) {
                return w to h
            }
        }
        return null
    }

    private companion object {
        val WM_OVERRIDE_REGEX = Regex("Override size:\\s*(\\d+)x(\\d+)")
        val WM_PHYSICAL_REGEX = Regex("Physical size:\\s*(\\d+)x(\\d+)")
        val WM_GENERIC_REGEX = Regex("(\\d+)x(\\d+)")
    }
}

@Serializable
data class SystemInfoResult(
    val device: DeviceInfo,
    val android: AndroidInfo,
    val display: DisplayInfo,
    val clickRange: ClickRange,
    val adbSession: AdbSessionInfo,
    val collectedAtMs: Long
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val fingerprint: String
)

@Serializable
data class AndroidInfo(
    val release: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildId: String,
    val incremental: String,
    val supportedAbis: List<String>
)

@Serializable
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val source: String,
    val raw: String
)

@Serializable
data class ClickRange(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int
)

@Serializable
data class AdbSessionInfo(
    val connected: Boolean,
    val detail: String
)
