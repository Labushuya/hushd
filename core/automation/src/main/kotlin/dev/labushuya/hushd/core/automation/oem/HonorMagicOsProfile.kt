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

/**
 * OemProfile describes all OEM-specific navigation for one device family.
 * The interface is expanded to support the MagicOS battery-detail dialog flow:
 *   Battery list → BatteryNormalAppDetailActivity → tap "Starteinstellungen" row
 *   → dialog with master toggle + sub-toggles → tap OK.
 */
interface OemProfile {
    val id: String

    /** Build an Intent that opens the battery/autostart detail screen for [pkg]. */
    fun openSettingsForPackage(ctx: Context, pkg: String): Intent?

    /** Find the "Starteinstellungen" (launch settings) clickable row on the battery detail screen. */
    fun findStartSettingsRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Find the master "Automatisch verwalten" toggle inside the dialog.
     * Returns the Switch/CheckBox widget node.
     */
    fun findMasterToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /** Find the "Auto-Start" sub-toggle inside the dialog. */
    fun findAutoStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /** Find the "Sekundärer Start" sub-toggle inside the dialog. */
    fun findSecondaryStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /** Find the "Im Hintergrund ausführen" sub-toggle inside the dialog. */
    fun findBackgroundRunToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /** Find the "OK" button that confirms the dialog. */
    fun findOkButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Kept for backwards compatibility with existing code paths that call the single-toggle flow.
     * Delegates to [findAutoStartToggle].
     */
    fun findAutoLaunchToggleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findAutoStartToggle(root)

    /** Returns true when the launch-settings dialog is currently visible. */
    fun isDialogVisible(root: AccessibilityNodeInfo): Boolean

    /** Set of strings that identify the battery detail screen (class name fragments / package). */
    fun expectedScreenSignature(): Set<String>

    /** Set of strings that identify the launch-settings dialog. */
    fun dialogSignature(): Set<String>

    /** Whether the "Sekundärer Start" sub-toggle should be disabled. */
    fun disableSecondaryStart(): Boolean

    /** Whether the "Im Hintergrund ausführen" sub-toggle should be disabled. */
    fun disableBackgroundRun(): Boolean

    /** Millis to wait after disabling the master toggle before touching sub-toggles. */
    fun masterToggleOffDelayMs(): Long

    /** Millis to wait between packages. */
    fun cooldownMs(): Long

    /** Maximum retry attempts per package. */
    fun maxRetries(): Int

    fun screenReadyTimeoutMs(): Long = 6_000
    fun clickTimeoutMs(): Long = 5_000
    fun verifyTimeoutMs(): Long = 3_000
}

/**
 * HonorMagicOsProfile implements the battery-detail dialog flow introduced in
 * MagicOS 8/9 (HONOR Magic V2 and later devices).
 *
 * Navigation path:
 *   Einstellungen → Akku → [app row] → BatteryNormalAppDetailActivity
 *   → tap "Starteinstellungen" row → dialog → disable toggles → OK
 */
class HonorMagicOsProfile @Inject constructor(
    @ApplicationContext private val appCtx: Context,
    private val config: ProfileConfig
) : OemProfile {

    override val id: String = "honor-magicos"

    // -------------------------------------------------------------------------
    // Screen navigation
    // -------------------------------------------------------------------------

    /**
     * Tries battery-detail Activity candidates first (primary path for MagicOS 8/9),
     * then falls back to the legacy startup-manager screens, then to the generic
     * ACTION_APPLICATION_DETAILS_SETTINGS.
     */
    override fun openSettingsForPackage(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager

        // Primary: battery detail candidates from config (BatteryNormalAppDetailActivity, …)
        for (candidate in config.batteryDetailActivityCandidates) {
            val (appPkg, cls) = splitFqn(candidate) ?: continue
            val intent = buildIntent(appPkg, cls, pkg)
            if (runCatching { pm.resolveActivity(intent, 0) }.getOrNull() != null) {
                Timber.tag(TAG).d("Resolved battery intent: %s", candidate)
                return intent
            }
        }

        // Last resort: generic App-Info screen
        Timber.tag(TAG).w("No battery detail activity resolved for %s — falling back to App-Info", pkg)
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // -------------------------------------------------------------------------
    // Accessibility node finders
    // -------------------------------------------------------------------------

    override fun findStartSettingsRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in config.startSettingsRowLabels) {
            val textNode = findTextNode(root, label) ?: continue
            // Walk up to find the closest clickable ancestor (the row container)
            val row = findClickableAncestor(textNode)
            if (row != null) {
                Timber.tag(TAG).d("findStartSettingsRow matched label='%s'", label)
                return row
            }
        }
        return null
    }

    override fun findMasterToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findToggleByLabels(root, config.masterToggleLabels, "masterToggle")

    override fun findAutoStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findToggleByLabels(root, config.autoStartLabels, "autoStartToggle")

    override fun findSecondaryStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findToggleByLabels(root, config.secondaryStartLabels, "secondaryStartToggle")

    override fun findBackgroundRunToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findToggleByLabels(root, config.backgroundRunLabels, "backgroundRunToggle")

    override fun findOkButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in config.okButtonLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                // The button node itself might be clickable, or we look for a clickable ancestor
                if (n.isClickable) return n
                val ancestor = findClickableAncestor(n)
                if (ancestor != null) return ancestor
            }
        }
        return null
    }

    override fun isDialogVisible(root: AccessibilityNodeInfo): Boolean {
        for (sig in config.dialogTitleSignatures) {
            val nodes = root.findAccessibilityNodeInfosByText(sig)
            if (nodes.any { it.isVisibleToUser }) return true
        }
        return false
    }

    override fun expectedScreenSignature(): Set<String> = config.screenSignaturePatterns
    override fun dialogSignature(): Set<String> = config.dialogTitleSignatures.toSet()
    override fun disableSecondaryStart(): Boolean = config.disableSecondaryStart
    override fun disableBackgroundRun(): Boolean = config.disableBackgroundRun
    override fun masterToggleOffDelayMs(): Long = config.masterToggleOffDelayMs
    override fun cooldownMs(): Long = config.cooldownMs
    override fun maxRetries(): Int = config.maxRetries

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Finds a toggle (Switch or CheckBox) whose row contains a label from [labels].
     * Strategy:
     *   1. Locate the text node matching the label (case-insensitive).
     *   2. Walk up to the row container via [findClickableAncestor] or direct parent.
     *   3. BFS within the row container for a Switch/CheckBox that is clickable.
     */
    private fun findToggleByLabels(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        debugName: String
    ): AccessibilityNodeInfo? {
        for (label in labels) {
            val textNode = findTextNode(root, label) ?: continue
            // Use the row container: prefer clickable ancestor, otherwise the parent
            val rowContainer: AccessibilityNodeInfo =
                findClickableAncestor(textNode) ?: textNode.parent ?: textNode
            val toggle = bfs(rowContainer) { node ->
                node.isClickable &&
                    (node.className?.contains("Switch", ignoreCase = true) == true ||
                        node.className?.contains("CheckBox", ignoreCase = true) == true)
            }
            if (toggle != null) {
                Timber.tag(TAG).d("%s matched label='%s'", debugName, label)
                return toggle
            }
        }
        return null
    }

    /**
     * Finds the first visible text node whose text contains [label] (case-insensitive).
     * Uses the Accessibility API text search first; verifies visibility.
     */
    private fun findTextNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(label)
        return candidates.firstOrNull { n ->
            n.isVisibleToUser &&
                n.text?.toString()?.equals(label, ignoreCase = true) == true
        } ?: candidates.firstOrNull { n ->
            n.isVisibleToUser &&
                n.text?.toString()?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Walks ancestor chain upward looking for the closest node that is clickable.
     * Stops after [MAX_ANCESTOR_DEPTH] levels to avoid runaway traversal.
     */
    private fun findClickableAncestor(
        node: AccessibilityNodeInfo,
        maxDepth: Int = MAX_ANCESTOR_DEPTH
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * BFS over the subtree rooted at [start], returning the first node matching [predicate].
     * Limited to [MAX_BFS_NODES] iterations to bound worst-case traversal.
     */
    internal fun bfs(
        start: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(start) }
        var iter = 0
        while (q.isNotEmpty() && iter++ < MAX_BFS_NODES) {
            val n = q.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        return null
    }

    /**
     * Splits a fully-qualified "pkg/cls" or "pkg/.SubClass" component name string.
     * Returns null on malformed input.
     */
    private fun splitFqn(fqn: String): Pair<String, String>? {
        val slash = fqn.indexOf('/')
        if (slash < 1 || slash == fqn.length - 1) return null
        val appPkg = fqn.substring(0, slash)
        val rawCls = fqn.substring(slash + 1)
        // Handle shorthand ".SubClass" → "pkg.SubClass"
        val cls = if (rawCls.startsWith('.')) "$appPkg$rawCls" else rawCls
        return appPkg to cls
    }

    private fun buildIntent(appPkg: String, cls: String, targetPkg: String): Intent =
        Intent().apply {
            component = ComponentName(appPkg, cls)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            // Provide both extra keys — device firmware varies in which one it reads
            putExtra("packageName", targetPkg)
            putExtra("package", targetPkg)
        }

    companion object {
        private const val TAG = "HonorProfile"
        private const val MAX_BFS_NODES = 2_000
        private const val MAX_ANCESTOR_DEPTH = 8
    }
}

// -----------------------------------------------------------------------------
// ProfileConfig — JSON deserialization
// -----------------------------------------------------------------------------

/** Parsed representation of honor_magicos.json. */
data class ProfileConfig(
    // New battery-detail flow fields
    val batteryDetailActivityCandidates: List<String>,
    val startSettingsRowLabels: List<String>,
    val masterToggleLabels: List<String>,
    val autoStartLabels: List<String>,
    val secondaryStartLabels: List<String>,
    val backgroundRunLabels: List<String>,
    val okButtonLabels: List<String>,
    val screenTitleSignatures: List<String>,
    val dialogTitleSignatures: List<String>,
    val screenSignaturePatterns: Set<String>,
    val disableSecondaryStart: Boolean,
    val disableBackgroundRun: Boolean,
    val masterToggleOffDelayMs: Long,
    // Common fields
    val cooldownMs: Long,
    val maxRetries: Int,
) {
    companion object {
        fun fromJson(json: JSONObject): ProfileConfig = ProfileConfig(
            batteryDetailActivityCandidates =
                json.getJSONArray("batteryDetailActivityCandidates").toStringList(),
            startSettingsRowLabels =
                json.getJSONArray("startSettingsRowLabels").toStringList(),
            masterToggleLabels =
                json.getJSONArray("masterToggleLabels").toStringList(),
            autoStartLabels =
                json.getJSONArray("autoStartLabels").toStringList(),
            secondaryStartLabels =
                json.getJSONArray("secondaryStartLabels").toStringList(),
            backgroundRunLabels =
                json.getJSONArray("backgroundRunLabels").toStringList(),
            okButtonLabels =
                json.getJSONArray("okButtonLabels").toStringList(),
            screenTitleSignatures =
                json.getJSONArray("screenTitleSignatures").toStringList(),
            dialogTitleSignatures =
                json.getJSONArray("dialogTitleSignatures").toStringList(),
            screenSignaturePatterns =
                json.getJSONArray("screenSignaturePatterns").toStringList().toSet(),
            disableSecondaryStart = json.optBoolean("disableSecondaryStart", true),
            disableBackgroundRun = json.optBoolean("disableBackgroundRun", true),
            masterToggleOffDelayMs = json.optLong("masterToggleOffDelayMs", 400L),
            cooldownMs = json.getLong("cooldownMs"),
            maxRetries = json.getInt("maxRetries"),
        )

        private fun org.json.JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}
