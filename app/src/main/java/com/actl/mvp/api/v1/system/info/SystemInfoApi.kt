package com.actl.mvp.api.v1.system.info

import android.os.Build
import com.actl.mvp.ActlApp
import com.actl.mvp.api.framework.ApiDefinition
import com.actl.mvp.api.framework.ApiResponse
import com.actl.mvp.adb.session.AdbRuntime
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

class SystemInfoApi : ApiDefinition {
    override val name: String = "system_info"

    private val adbManager = AdbRuntime.adbManager

    override fun register(route: Route) {
        route.get(path) {
            val display = resolveDisplayInfo()
            val clickRange = ClickRange(
                xMin = 0,
                yMin = 0,
                xMax = if (display.widthPx > 0) display.widthPx - 1 else 0,
                yMax = if (display.heightPx > 0) display.heightPx - 1 else 0
            )

            val adbProbe = adbManager.executeShell("echo ACTL_CONNECTED")
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
                    connected = adbProbe.success,
                    detail = adbProbe.message.trim()
                ),
                collectedAtMs = System.currentTimeMillis()
            )
            call.respond(ApiResponse(code = 0, message = "ok", data = data))
        }
    }

    private fun resolveDisplayInfo(): DisplayInfo {
        val wmSize = adbManager.executeShell("wm size")
        if (wmSize.success) {
            val parsed = parseWmSize(wmSize.message)
            if (parsed != null) {
                return DisplayInfo(
                    widthPx = parsed.first,
                    heightPx = parsed.second,
                    source = "adb_wm_size",
                    raw = wmSize.message.trim()
                )
            }
        }

        val dm = ActlApp.appContext.resources.displayMetrics
        return DisplayInfo(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            source = "resources_display_metrics",
            raw = wmSize.message.trim()
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
