// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

interface OemProfile {
    val id: String
    fun openSettingsForPackage(ctx: Context, pkg: String): Intent?
    fun findAutoLaunchToggleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo?
    fun expectedScreenSignature(): Set<String>
    fun cooldownMs(): Long
    fun maxRetries(): Int
    fun screenReadyTimeoutMs(): Long = 4_000
    fun clickTimeoutMs(): Long = 4_000
    fun verifyTimeoutMs(): Long = 2_500
}

class HonorMagicOsProfile @Inject constructor(
    @ApplicationContext private val appCtx: Context,
    private val config: ProfileConfig
) : OemProfile {

    override val id: String = "honor-magicos"

    /**
     * Probe-Reihenfolge nach Recherche: MagicOS 8/9 → MagicOS 7 → Huawei-Legacy →
     * Phone-Manager-Home → generischer App-Details-Fallback.
     * ACHTUNG: tatsächliche Klasse ist MainScreenActivity, NICHT MainActivity.
     */
    override fun openSettingsForPackage(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        val candidates = listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appmanage.ui.AppManageMainActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.mainscreen.MainScreenActivity"
        )
        for ((p, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(p, cls)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra("package", pkg)
            }
            if (runCatching { pm.resolveActivity(intent, 0) }.getOrNull() != null) {
                Timber.tag(TAG).d("Resolved settings intent: %s/%s", p, cls)
                return intent
            }
        }
        // Generischer Fallback
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun findAutoLaunchToggleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in config.toggleLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val container = n.parent ?: n
                val toggle = bfs(container) { node ->
                    node.isClickable &&
                        (node.className?.contains("Switch", ignoreCase = true) == true ||
                            node.className?.contains("CheckBox", ignoreCase = true) == true)
                }
                if (toggle != null) return toggle
            }
        }
        // ResourceID-Fallback aus config
        for (rid in config.resourceIdFallbacks) {
            root.findAccessibilityNodeInfosByViewId(rid).firstOrNull()?.let { return it }
        }
        return null
    }

    override fun expectedScreenSignature(): Set<String> = config.screenSignaturePatterns
    override fun cooldownMs(): Long = config.cooldownMs
    override fun maxRetries(): Int = config.maxRetries

    private fun bfs(start: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(start) }
        var iter = 0
        while (q.isNotEmpty() && iter++ < 2_000) {
            val n = q.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        return null
    }

    companion object { private const val TAG = "HonorProfile" }
}

/** Repräsentation der JSON-Profilkonfiguration. */
data class ProfileConfig(
    val toggleLabels: List<String>,
    val resourceIdFallbacks: List<String>,
    val screenSignaturePatterns: Set<String>,
    val cooldownMs: Long,
    val maxRetries: Int,
    val settingsActivityFqns: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): ProfileConfig = ProfileConfig(
            toggleLabels = json.getJSONArray("toggleLabels").toStringList(),
            resourceIdFallbacks = json.getJSONArray("resourceIdFallbacks").toStringList(),
            screenSignaturePatterns = json.getJSONArray("screenSignaturePatterns").toStringList().toSet(),
            cooldownMs = json.getLong("cooldownMs"),
            maxRetries = json.getInt("maxRetries"),
            settingsActivityFqns = json.getJSONArray("settingsActivityFqns").toStringList()
        )

        private fun org.json.JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}
