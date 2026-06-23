// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation.oem

import android.content.Context
import android.content.res.Resources
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import javax.inject.Inject
import javax.inject.Singleton

class UnsupportedDeviceException(message: String) : RuntimeException(message)

@Singleton
class OemProfileResolver @Inject constructor(
    @ApplicationContext private val ctx: Context
) {

    private val cached: OemProfile by lazy { resolve() }

    fun active(): OemProfile = cached

    private fun resolve(): OemProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        val device = Build.DEVICE.orEmpty()
        Timber.tag(TAG).i(
            "Build manufacturer=%s brand=%s display=%s device=%s sdk=%d",
            manufacturer, brand, display, device, Build.VERSION.SDK_INT
        )

        val isHonor = brand.equals("HONOR", ignoreCase = true) ||
            manufacturer.equals("HONOR", ignoreCase = true)
        val isHonorByPackage = runCatching {
            ctx.packageManager.getPackageInfo("com.hihonor.systemmanager", 0); true
        }.getOrDefault(false)

        val displayLower = display.lowercase()
        val looksLikeMagicOs = displayLower.contains("magic_ui") ||
            displayLower.contains("magicos") ||
            displayLower.contains("magic ui") ||
            displayLower.contains("magic os")

        if ((isHonor || isHonorByPackage) && (looksLikeMagicOs || isHonorByPackage)) {
            val config = loadConfig(resName = "honor_magicos")
            return HonorMagicOsProfile(ctx, config)
        }
        // TODO(v2): Adapter für Xiaomi MIUI, Samsung OneUI, OPPO ColorOS, Vivo OriginOS
        throw UnsupportedDeviceException(
            "No OEM profile matches: brand=$brand manufacturer=$manufacturer display=$display"
        )
    }

    private fun loadConfig(resName: String): ProfileConfig {
        val res = ctx.resources
        val id = res.getIdentifier(resName, "raw", ctx.packageName)
        if (id == 0) throw Resources.NotFoundException("raw/$resName.json missing")
        val text = res.openRawResource(id).bufferedReader().use(BufferedReader::readText)
        return ProfileConfig.fromJson(JSONObject(text))
    }

    companion object { private const val TAG = "OemResolver" }
}
