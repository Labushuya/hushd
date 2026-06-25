// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/**
 * OemProfile describes all OEM-specific navigation for one device family.
 *
 * MagicOS two-activity flow:
 *   1. Open [openBatteryListScreen] → HwPowerManagerActivity (battery list, publicly accessible)
 *   2. Accessibility service finds the app row via [findAppRowInBatteryList] and taps it
 *   3. System internally opens DetailOfSoftConsumptionActivity (requires HW_SIGNATURE_OR_SYSTEM)
 *   4. Service finds "Starteinstellungen" row via [findStartSettingsRow] and taps it
 *   5. Dialog appears: disable master + sub-toggles via resource-ID finders → tap OK
 */
interface OemProfile {
    val id: String

    /**
     * Opens the battery list screen (HwPowerManagerActivity).
     * This is NOT the per-app detail screen — it is the list of all apps with battery usage.
     * The accessibility service must then tap the app row to trigger internal navigation
     * to DetailOfSoftConsumptionActivity.
     */
    fun openBatteryListScreen(ctx: Context): Intent

    /**
     * Finds the app row in the battery list (HwPowerManagerActivity) whose text contains
     * [appLabel]. The row may include a percentage suffix; matching is done by containment,
     * not exact equality. Returns the clickable row container node, or null if not found.
     */
    fun findAppRowInBatteryList(root: AccessibilityNodeInfo, appLabel: String): AccessibilityNodeInfo?

    /**
     * Finds the "Starteinstellungen" (launch settings) clickable row on the
     * DetailOfSoftConsumptionActivity screen. Tries all labels from config.
     */
    fun findStartSettingsRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Finds the master "Automatisch verwalten" toggle inside the dialog.
     * Uses [ProfileConfig.masterToggleResourceId] via findAccessibilityNodeInfosByViewId.
     */
    fun findMasterToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Finds the "Auto-Start" sub-toggle inside the dialog.
     * Uses [ProfileConfig.autoStartResourceId].
     */
    fun findAutoStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Finds the "Sekundärer Start" sub-toggle inside the dialog.
     * Uses [ProfileConfig.secondaryStartResourceId].
     */
    fun findSecondaryStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Finds the "Im Hintergrund ausführen" sub-toggle inside the dialog.
     * Uses [ProfileConfig.backgroundRunResourceId].
     */
    fun findBackgroundRunToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Finds the OK button that confirms the dialog.
     * Uses [ProfileConfig.okButtonResourceId] (android:id/button1).
     */
    fun findOkButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /**
     * Returns true when the launch-settings dialog is currently visible, detected by
     * findAccessibilityNodeInfosByViewId([ProfileConfig.dialogTitleResourceId]).
     */
    fun isDialogVisible(root: AccessibilityNodeInfo): Boolean

    /**
     * Legacy — kept for backward compatibility with existing code paths.
     * Delegates to [findAutoStartToggle].
     */
    fun findAutoLaunchToggleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findAutoStartToggle(root)

    /** Set of strings that identify the battery / detail screens (class-name fragments / package). */
    fun expectedScreenSignature(): Set<String>

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
}

/**
 * HonorMagicOsProfile implements the battery-list → detail dialog flow verified on the
 * Honor Magic V2 running MagicOS.
 *
 * Navigation path (verified via adb uiautomator dump):
 *   1. startActivity(HwPowerManagerActivity) — publicly accessible, no system permission
 *   2. A11y taps the app row (text contains appLabel) → DetailOfSoftConsumptionActivity opens
 *   3. A11y taps the "Starteinstellungen" row → dialog appears
 *   4. A11y turns off switch_auto_management (master) → waits [masterToggleOffDelayMs]
 *   5. A11y turns off switch_startup, switch_secondary_launch, switch_background_running
 *   6. A11y clicks android:id/button1 (OK) → 2× GLOBAL_ACTION_BACK
 */
class HonorMagicOsProfile @Inject constructor(
    @ApplicationContext private val appCtx: Context,
    private val config: ProfileConfig
) : OemProfile {

    override val id: String = "honor-magicos"

    // -------------------------------------------------------------------------
    // Screen navigation
    // -------------------------------------------------------------------------

    override fun openBatteryListScreen(ctx: Context): Intent {
        val (appPkg, cls) = splitFqn(config.batteryListActivityFqn)
            ?: error("Malformed batteryListActivityFqn: ${config.batteryListActivityFqn}")
        Timber.tag(TAG).d("openBatteryListScreen → %s", config.batteryListActivityFqn)
        return Intent().apply {
            component = ComponentName(appPkg, cls)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
    }

    // -------------------------------------------------------------------------
    // Accessibility node finders
    // -------------------------------------------------------------------------

    override fun findAppRowInBatteryList(
        root: AccessibilityNodeInfo,
        appLabel: String
    ): AccessibilityNodeInfo? {
        // App rows in HwPowerManagerActivity show "AppName  XX%" — match by containment
        val candidates = root.findAccessibilityNodeInfosByText(appLabel)
        for (node in candidates) {
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString() ?: continue
            if (!text.contains(appLabel, ignoreCase = false)) continue
            // Walk to nearest clickable ancestor (the row container)
            val row = findClickableAncestor(node) ?: if (node.isClickable) node else continue
            Timber.tag(TAG).d("findAppRowInBatteryList matched '%s' in '%s'", appLabel, text)
            return row
        }
        return null
    }

    override fun findStartSettingsRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in config.startSettingsRowLabels) {
            val textNode = findTextNode(root, label) ?: continue
            val row = findClickableAncestor(textNode) ?: if (textNode.isClickable) textNode else null
            if (row != null) {
                Timber.tag(TAG).d("findStartSettingsRow matched label='%s'", label)
                return row
            }
        }
        return null
    }

    override fun findMasterToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByResourceId(root, config.masterToggleResourceId, "masterToggle")

    override fun findAutoStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByResourceId(root, config.autoStartResourceId, "autoStartToggle")

    override fun findSecondaryStartToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByResourceId(root, config.secondaryStartResourceId, "secondaryStartToggle")

    override fun findBackgroundRunToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByResourceId(root, config.backgroundRunResourceId, "backgroundRunToggle")

    override fun findOkButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByResourceId(root, config.okButtonResourceId, "okButton")

    override fun isDialogVisible(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(config.dialogTitleResourceId)
        return nodes.any { it.isVisibleToUser }
    }

    override fun expectedScreenSignature(): Set<String> = config.screenSignaturePatterns
    override fun disableSecondaryStart(): Boolean = config.disableSecondaryStart
    override fun disableBackgroundRun(): Boolean = config.disableBackgroundRun
    override fun masterToggleOffDelayMs(): Long = config.masterToggleOffDelayMs
    override fun cooldownMs(): Long = config.cooldownMs
    override fun maxRetries(): Int = config.maxRetries

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the first visible node matching [resourceId] via findAccessibilityNodeInfosByViewId.
     * Returns the node directly if clickable; otherwise falls back to its first clickable child
     * (some Switch containers wrap the actual widget in an extra layer).
     */
    private fun findByResourceId(
        root: AccessibilityNodeInfo,
        resourceId: String,
        debugName: String
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        val node = nodes.firstOrNull { it.isVisibleToUser } ?: return null
        Timber.tag(TAG).d("%s found via resourceId=%s enabled=%b checked=%b",
            debugName, resourceId, node.isEnabled, node.isChecked)
        return node
    }

    /**
     * Finds the first visible text node whose text exactly matches [label] (case-sensitive),
     * or falls back to a case-insensitive containment match.
     */
    private fun findTextNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(label)
        return candidates.firstOrNull { n ->
            n.isVisibleToUser &&
                n.text?.toString() == label
        } ?: candidates.firstOrNull { n ->
            n.isVisibleToUser &&
                n.text?.toString()?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Walks the ancestor chain upward looking for the closest clickable node.
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
     * Splits a fully-qualified "pkg/cls" or "pkg/.SubClass" component-name string.
     * Returns null on malformed input.
     */
    private fun splitFqn(fqn: String): Pair<String, String>? {
        val slash = fqn.indexOf('/')
        if (slash < 1 || slash == fqn.length - 1) return null
        val appPkg = fqn.substring(0, slash)
        val rawCls = fqn.substring(slash + 1)
        val cls = if (rawCls.startsWith('.')) "$appPkg$rawCls" else rawCls
        return appPkg to cls
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

/** Parsed representation of honor_magicos.json (resource-ID–based MagicOS flow). */
data class ProfileConfig(
    /** FQN of HwPowerManagerActivity — the battery list opened by the service. */
    val batteryListActivityFqn: String,
    /** FQN of DetailOfSoftConsumptionActivity — opened internally by tapping an app row. */
    val batteryDetailActivityFqn: String,
    /** Text labels used to find the "Starteinstellungen" row on the detail screen. */
    val startSettingsRowLabels: List<String>,
    /** Resource ID of the master auto-management toggle (switch_auto_management). */
    val masterToggleResourceId: String,
    /** Resource ID of the Auto-Start sub-toggle (switch_startup). */
    val autoStartResourceId: String,
    /** Resource ID of the Secondary-Start sub-toggle (switch_secondary_launch). */
    val secondaryStartResourceId: String,
    /** Resource ID of the background-run sub-toggle (switch_background_running). */
    val backgroundRunResourceId: String,
    /** Resource ID of the OK button (android:id/button1). */
    val okButtonResourceId: String,
    /** Resource ID of the dialog title (android:id/alertTitle) — used to detect dialog presence. */
    val dialogTitleResourceId: String,
    /** Expected dialog title text (used as a secondary dialog-presence check). */
    val dialogTitleText: String,
    /** Fragments of class names / package names that identify the relevant screens. */
    val screenSignaturePatterns: Set<String>,
    /** Whether to disable the "Sekundärer Start" sub-toggle. */
    val disableSecondaryStart: Boolean,
    /** Whether to disable the "Im Hintergrund ausführen" sub-toggle. */
    val disableBackgroundRun: Boolean,
    /** Millis to wait after turning off the master toggle before touching sub-toggles. */
    val masterToggleOffDelayMs: Long,
    /** Millis to wait between processing consecutive packages. */
    val cooldownMs: Long,
    /** Maximum retry attempts per package before reporting failure. */
    val maxRetries: Int,
) {
    companion object {
        fun fromJson(json: JSONObject): ProfileConfig = ProfileConfig(
            batteryListActivityFqn =
                json.getString("batteryListActivityFqn"),
            batteryDetailActivityFqn =
                json.getString("batteryDetailActivityFqn"),
            startSettingsRowLabels =
                json.getJSONArray("startSettingsRowLabels").toStringList(),
            masterToggleResourceId =
                json.getString("masterToggleResourceId"),
            autoStartResourceId =
                json.getString("autoStartResourceId"),
            secondaryStartResourceId =
                json.getString("secondaryStartResourceId"),
            backgroundRunResourceId =
                json.getString("backgroundRunResourceId"),
            okButtonResourceId =
                json.getString("okButtonResourceId"),
            dialogTitleResourceId =
                json.getString("dialogTitleResourceId"),
            dialogTitleText =
                json.getString("dialogTitleText"),
            screenSignaturePatterns =
                json.getJSONArray("screenSignaturePatterns").toStringList().toSet(),
            disableSecondaryStart =
                json.optBoolean("disableSecondaryStart", true),
            disableBackgroundRun =
                json.optBoolean("disableBackgroundRun", true),
            masterToggleOffDelayMs =
                json.optLong("masterToggleOffDelayMs", 400L),
            cooldownMs =
                json.getLong("cooldownMs"),
            maxRetries =
                json.getInt("maxRetries"),
        )

        private fun org.json.JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}
