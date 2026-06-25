// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Autostart Manager Maintainers
package dev.labushuya.hushd.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import dev.labushuya.hushd.core.automation.A11yServiceHandle
import dev.labushuya.hushd.core.automation.BulkAutostopEngine
import dev.labushuya.hushd.core.automation.NodeEvent
import dev.labushuya.hushd.core.automation.ServiceCommand
import dev.labushuya.hushd.core.automation.oem.OemProfile
import dev.labushuya.hushd.core.automation.oem.OemProfileResolver
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Per-package flow state machine
// ---------------------------------------------------------------------------

/**
 * Tracks where we are in the MagicOS two-activity dialog flow for the current package.
 *
 * Flow:
 *   IDLE
 *   → OPENING_BATTERY_LIST      intent sent, waiting for HwPowerManagerActivity
 *   → FINDING_APP_ROW           on battery list, looking for the app row by label
 *   → WAITING_DETAIL_SCREEN     app row clicked, waiting for DetailOfSoftConsumptionActivity
 *   → FINDING_START_SETTINGS    on detail screen, looking for "Starteinstellungen" row
 *   → WAITING_DIALOG            row clicked, waiting for dialog (android:id/alertTitle)
 *   → CLICKING_MASTER           click switch_auto_management if checked=true
 *   → WAITING_AFTER_MASTER      delay [masterToggleOffDelayMs] after master click
 *   → CLICKING_AUTOSTART        click switch_startup if checked=true and enabled
 *   → CLICKING_SECONDARY        click switch_secondary_launch if config says so
 *   → CLICKING_BACKGROUND       click switch_background_running if config says so
 *   → CLICKING_OK               click android:id/button1 (OK)
 *   → GOING_BACK                200ms delay, GLOBAL_ACTION_BACK x2, emit AllTogglesDisabled
 *   → DONE
 *   → ERROR
 */
private enum class FlowStep {
    IDLE,
    OPENING_BATTERY_LIST,
    FINDING_APP_ROW,
    WAITING_DETAIL_SCREEN,
    FINDING_START_SETTINGS,
    WAITING_DIALOG,
    CLICKING_MASTER,
    WAITING_AFTER_MASTER,
    CLICKING_AUTOSTART,
    CLICKING_SECONDARY,
    CLICKING_BACKGROUND,
    CLICKING_OK,
    GOING_BACK,
    DONE,
    ERROR,
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * AccessibilityService implementing the verified MagicOS two-activity dialog automation.
 *
 * Activity chain (verified on Honor Magic V2 MagicOS via adb uiautomator dump):
 *   com.hihonor.systemmanager/.power.ui.HwPowerManagerActivity  — publicly accessible
 *   com.hihonor.systemmanager/.power.ui.DetailOfSoftConsumptionActivity  — opened internally
 *
 * All toggle finders use resource-ID lookups (findAccessibilityNodeInfosByViewId) for
 * reliability. Text-label fallback is kept only for findStartSettingsRow and findAppRowInBatteryList.
 *
 * packageNames filter in accessibility_service_config.xml is restricted to
 * com.hihonor.systemmanager to limit event exposure.
 *
 * Lifecycle:
 *   onServiceConnected → engine.attachService(this), coroutine collects commands
 *   onAccessibilityEvent → advances [step] state machine
 *   12 s watchdog per package resets on each step; fires ToggleNotFound + IDLE on timeout
 */
@AndroidEntryPoint
class AutostopAccessibilityService : AccessibilityService(), A11yServiceHandle {

    @Inject lateinit var engine: BulkAutostopEngine
    @Inject lateinit var profileResolver: OemProfileResolver

    /**
     * Outbound channel: service publishes NodeEvents, engine consumes.
     * BufferOverflow.DROP_OLDEST: at burst rate we prefer newest events.
     */
    private val outbound: Channel<NodeEvent> =
        Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val events: Channel<NodeEvent> get() = outbound

    private val serviceScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val watchdogToken = Any()

    // State machine — only accessed on the main thread (Handler + onAccessibilityEvent)
    @Volatile private var step: FlowStep = FlowStep.IDLE
    @Volatile private var currentPkg: String = ""
    @Volatile private var currentAppLabel: String = ""

    // Per-step retry counter
    private var findAppRowRetries = 0
    private var findStartSettingsRetries = 0
    private var findDialogRetries = 0

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.tag(TAG).i("Connected; profile=%s", profileResolver.active().id)
        engine.attachService(this)
        serviceScope.launch(Dispatchers.Main.immediate) {
            engine.commands.collect { cmd -> handleCommand(cmd) }
        }
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("onInterrupt")
        outbound.trySend(NodeEvent.Interrupted)
        resetToIdle()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Timber.tag(TAG).i("onUnbind")
        cancelWatchdog()
        handler.removeCallbacksAndMessages(null)
        engine.detachService()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        outbound.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // Accessibility events — state machine entry point
    // ---------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val profile = profileResolver.active()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString().orEmpty()
                handleWindowStateChanged(cls, profile)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(profile)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------
    // TYPE_WINDOW_STATE_CHANGED
    // ---------------------------------------------------------------------------

    private fun handleWindowStateChanged(cls: String, profile: OemProfile) {
        Timber.tag(TAG).v("WindowStateChanged cls=%s step=%s", cls, step)

        when {
            // Battery list arrived
            cls.contains("HwPowerManagerActivity") && step == FlowStep.OPENING_BATTERY_LIST -> {
                Timber.tag(TAG).d("HwPowerManagerActivity confirmed — scheduling findAppRow")
                step = FlowStep.FINDING_APP_ROW
                outbound.trySend(NodeEvent.ScreenReady(
                    packageName = "com.hihonor.systemmanager",
                    className = cls,
                    rootBoundsHash = 0,
                ))
                resetWatchdog()
                // Short delay for the list to finish rendering
                handler.postDelayed({ tryFindAndClickAppRow(profile) }, 100L)
            }

            // Detail screen arrived (internal navigation from tapping app row)
            cls.contains("DetailOfSoftConsumptionActivity") && step == FlowStep.WAITING_DETAIL_SCREEN -> {
                Timber.tag(TAG).d("DetailOfSoftConsumptionActivity confirmed for %s", currentPkg)
                step = FlowStep.FINDING_START_SETTINGS
                resetWatchdog()
                // Give the detail screen time to fully render its rows
                handler.postDelayed({ tryFindAndClickStartSettings(profile) }, 200L)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // TYPE_WINDOW_CONTENT_CHANGED
    // ---------------------------------------------------------------------------

    private fun handleContentChanged(profile: OemProfile) {
        val currentStep = step
        if (currentStep == FlowStep.IDLE ||
            currentStep == FlowStep.DONE ||
            currentStep == FlowStep.ERROR ||
            currentStep == FlowStep.WAITING_AFTER_MASTER ||
            currentStep == FlowStep.GOING_BACK ||
            currentStep == FlowStep.OPENING_BATTERY_LIST
        ) {
            return
        }

        val root = rootInActiveWindow ?: run {
            outbound.trySend(NodeEvent.RootUnavailable)
            return
        }

        when (currentStep) {
            FlowStep.FINDING_APP_ROW -> {
                tryFindAndClickAppRow(profile)
            }
            FlowStep.FINDING_START_SETTINGS -> {
                tryFindAndClickStartSettings(profile)
            }
            FlowStep.WAITING_DIALOG -> {
                if (profile.isDialogVisible(root)) {
                    Timber.tag(TAG).d("Dialog visible for %s", currentPkg)
                    outbound.trySend(NodeEvent.DialogAppeared(currentPkg))
                    step = FlowStep.CLICKING_MASTER
                    handleMasterToggle(root, profile)
                } else {
                    findDialogRetries++
                    if (findDialogRetries > MAX_RETRIES_DIALOG) {
                        Timber.tag(TAG).w("Dialog never appeared for %s after %d checks",
                            currentPkg, findDialogRetries)
                        failCurrentPkg()
                    }
                }
            }
            FlowStep.CLICKING_AUTOSTART -> {
                val freshRoot = rootInActiveWindow ?: run {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                clickAutoStart(freshRoot, profile)
            }
            FlowStep.CLICKING_SECONDARY -> {
                val freshRoot = rootInActiveWindow ?: run {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                clickSecondaryStart(freshRoot, profile)
            }
            FlowStep.CLICKING_BACKGROUND -> {
                val freshRoot = rootInActiveWindow ?: run {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                clickBackgroundRun(freshRoot, profile)
            }
            FlowStep.CLICKING_OK -> {
                val freshRoot = rootInActiveWindow ?: run {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                clickOkButton(freshRoot, profile)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------
    // State machine step implementations
    // ---------------------------------------------------------------------------

    /**
     * Called via Handler after a short delay post-OPENING_BATTERY_LIST, and on
     * TYPE_WINDOW_CONTENT_CHANGED while in FINDING_APP_ROW.
     */
    private fun tryFindAndClickAppRow(profile: OemProfile) {
        if (step != FlowStep.FINDING_APP_ROW) return
        val root = rootInActiveWindow ?: run {
            outbound.trySend(NodeEvent.RootUnavailable)
            return
        }
        val row = profile.findAppRowInBatteryList(root, currentAppLabel)
        if (row == null) {
            findAppRowRetries++
            Timber.tag(TAG).d("App row '%s' not found yet (retry %d)", currentAppLabel, findAppRowRetries)
            if (findAppRowRetries > MAX_RETRIES_APP_ROW) {
                Timber.tag(TAG).w("App row '%s' not found after %d retries",
                    currentAppLabel, findAppRowRetries)
                outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
                resetToIdle()
            }
            // Will be retried on next TYPE_WINDOW_CONTENT_CHANGED
            return
        }
        val clicked = row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.tag(TAG).d("App row clicked for %s — waiting for DetailOfSoftConsumptionActivity", currentPkg)
            step = FlowStep.WAITING_DETAIL_SCREEN
            resetWatchdog()
        } else {
            Timber.tag(TAG).w("App row click failed for %s", currentPkg)
            outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
            resetToIdle()
        }
    }

    /**
     * Called via Handler after a short delay post-WAITING_DETAIL_SCREEN confirmation,
     * and on TYPE_WINDOW_CONTENT_CHANGED while in FINDING_START_SETTINGS.
     */
    private fun tryFindAndClickStartSettings(profile: OemProfile) {
        if (step != FlowStep.FINDING_START_SETTINGS) return
        val root = rootInActiveWindow ?: run {
            outbound.trySend(NodeEvent.RootUnavailable)
            return
        }
        val row = profile.findStartSettingsRow(root)
        if (row == null) {
            findStartSettingsRetries++
            Timber.tag(TAG).d("Starteinstellungen row not found yet (retry %d)", findStartSettingsRetries)
            if (findStartSettingsRetries > MAX_RETRIES_START_SETTINGS) {
                Timber.tag(TAG).w("Starteinstellungen row not found after %d retries",
                    findStartSettingsRetries)
                outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
                resetToIdle()
            }
            return
        }
        val clicked = row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.tag(TAG).d("Starteinstellungen row clicked for %s", currentPkg)
            outbound.trySend(NodeEvent.StartSettingsRowClicked(currentPkg))
            step = FlowStep.WAITING_DIALOG
            findDialogRetries = 0
            resetWatchdog()
        } else {
            Timber.tag(TAG).w("Starteinstellungen row click failed for %s — trying parent", currentPkg)
            val parent = row.parent
            val parentClicked = parent != null &&
                parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (parentClicked) {
                outbound.trySend(NodeEvent.StartSettingsRowClicked(currentPkg))
                step = FlowStep.WAITING_DIALOG
                findDialogRetries = 0
                resetWatchdog()
            } else {
                Timber.tag(TAG).e("Starteinstellungen row not clickable for %s", currentPkg)
                outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
                resetToIdle()
            }
        }
    }

    private fun handleMasterToggle(root: AccessibilityNodeInfo, profile: OemProfile) {
        val master = profile.findMasterToggle(root)
        if (master == null) {
            // No master toggle visible — proceed directly to auto-start sub-toggle
            Timber.tag(TAG).d("No master toggle found for %s — skipping to CLICKING_AUTOSTART", currentPkg)
            step = FlowStep.CLICKING_AUTOSTART
            clickAutoStart(root, profile)
            return
        }

        if (master.isChecked) {
            Timber.tag(TAG).d("Master toggle ON for %s — clicking to disable", currentPkg)
            val clicked = master.isClickable && master.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                Timber.tag(TAG).w("Master toggle click failed for %s", currentPkg)
                outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
                resetToIdle()
                return
            }
            outbound.trySend(NodeEvent.MasterToggleDisabled(currentPkg))
            step = FlowStep.WAITING_AFTER_MASTER
            resetWatchdog()
            // Wait for sub-toggles to become enabled
            val delayMs = profile.masterToggleOffDelayMs()
            handler.postDelayed({
                if (step != FlowStep.WAITING_AFTER_MASTER) return@postDelayed
                Timber.tag(TAG).d("Master delay elapsed for %s — advancing to CLICKING_AUTOSTART", currentPkg)
                step = FlowStep.CLICKING_AUTOSTART
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    clickAutoStart(freshRoot, profile)
                } else {
                    outbound.trySend(NodeEvent.RootUnavailable)
                    resetToIdle()
                }
            }, delayMs)
        } else {
            // Master already OFF — sub-toggles should already be independently accessible
            Timber.tag(TAG).d("Master toggle already OFF for %s", currentPkg)
            step = FlowStep.CLICKING_AUTOSTART
            clickAutoStart(root, profile)
        }
    }

    private fun clickAutoStart(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (step != FlowStep.CLICKING_AUTOSTART) return
        val toggle = profile.findAutoStartToggle(root)
        if (toggle == null) {
            Timber.tag(TAG).d("Auto-Start toggle not found for %s — continuing", currentPkg)
            advanceFromAutostart(root, profile)
            return
        }
        if (toggle.isChecked) {
            if (!toggle.isEnabled) {
                Timber.tag(TAG).w("Auto-Start toggle disabled (greyed) for %s — master still ON?", currentPkg)
                outbound.trySend(NodeEvent.ToggleDisabledByMaster(currentPkg))
                resetToIdle()
                return
            }
            val clicked = toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Timber.tag(TAG).d("Auto-Start toggle click %s for %s",
                if (clicked) "succeeded" else "failed", currentPkg)
        } else {
            Timber.tag(TAG).d("Auto-Start already OFF for %s", currentPkg)
        }
        advanceFromAutostart(root, profile)
    }

    private fun advanceFromAutostart(root: AccessibilityNodeInfo, profile: OemProfile) {
        step = FlowStep.CLICKING_SECONDARY
        clickSecondaryStart(root, profile)
    }

    private fun clickSecondaryStart(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (step != FlowStep.CLICKING_SECONDARY) return
        if (profile.disableSecondaryStart()) {
            val toggle = profile.findSecondaryStartToggle(root)
            if (toggle != null && toggle.isChecked && toggle.isEnabled) {
                toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.tag(TAG).d("Sekundärer Start toggled OFF for %s", currentPkg)
            } else if (toggle == null) {
                Timber.tag(TAG).d("Sekundärer Start toggle not found for %s", currentPkg)
            }
        } else {
            Timber.tag(TAG).d("disableSecondaryStart=false — skipping for %s", currentPkg)
        }
        step = FlowStep.CLICKING_BACKGROUND
        clickBackgroundRun(root, profile)
    }

    private fun clickBackgroundRun(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (step != FlowStep.CLICKING_BACKGROUND) return
        if (profile.disableBackgroundRun()) {
            val toggle = profile.findBackgroundRunToggle(root)
            if (toggle != null && toggle.isChecked && toggle.isEnabled) {
                toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.tag(TAG).d("Background run toggled OFF for %s", currentPkg)
            } else if (toggle == null) {
                Timber.tag(TAG).d("Background run toggle not found for %s", currentPkg)
            }
        } else {
            Timber.tag(TAG).d("disableBackgroundRun=false — skipping for %s", currentPkg)
        }
        step = FlowStep.CLICKING_OK
        clickOkButton(root, profile)
    }

    private fun clickOkButton(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (step != FlowStep.CLICKING_OK) return
        val ok = profile.findOkButton(root)
        if (ok == null) {
            Timber.tag(TAG).w("OK button not found for %s", currentPkg)
            outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
            resetToIdle()
            return
        }
        val clicked = ok.isClickable && ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.tag(TAG).d("OK button clicked for %s", currentPkg)
            step = FlowStep.GOING_BACK
            cancelWatchdog()
            // Navigate back twice to return to the battery list, then signal success
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    outbound.trySend(NodeEvent.AllTogglesDisabled(currentPkg))
                    Timber.tag(TAG).i("AllTogglesDisabled emitted for %s", currentPkg)
                    step = FlowStep.DONE
                }, 200L)
            }, 200L)
        } else {
            Timber.tag(TAG).w("OK button click failed for %s", currentPkg)
            outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
            resetToIdle()
        }
    }

    // ---------------------------------------------------------------------------
    // Command handling: Engine → Service
    // ---------------------------------------------------------------------------

    private fun handleCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.ClickToggleForPackage -> {
                currentPkg = cmd.targetPackage
                currentAppLabel = cmd.appLabel
                findAppRowRetries = 0
                findStartSettingsRetries = 0
                findDialogRetries = 0
                step = FlowStep.OPENING_BATTERY_LIST
                Timber.tag(TAG).d(
                    "ClickToggleForPackage pkg=%s label='%s' — armed OPENING_BATTERY_LIST",
                    currentPkg, currentAppLabel
                )
                resetWatchdog()

                // If the battery list screen is already the active window, skip straight to
                // FINDING_APP_ROW (handles the case where previous package left us on the list)
                val root = rootInActiveWindow
                if (root != null) {
                    val cls = root.className?.toString().orEmpty()
                    if (cls.contains("HwPowerManagerActivity") ||
                        root.packageName?.toString() == "com.hihonor.systemmanager"
                    ) {
                        Timber.tag(TAG).d("Battery list already active — skipping to FINDING_APP_ROW")
                        step = FlowStep.FINDING_APP_ROW
                        handler.postDelayed({
                            tryFindAndClickAppRow(profileResolver.active())
                        }, 100L)
                    }
                }
            }

            is ServiceCommand.VerifyToggleOff -> {
                val root = rootInActiveWindow
                if (root == null) {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                val profile = profileResolver.active()
                val toggle = profile.findAutoLaunchToggleNode(root)
                // After a successful run the dialog is dismissed; toggle being absent = success
                val isOff = toggle == null || !toggle.isChecked
                outbound.trySend(NodeEvent.VerifyResult(cmd.targetPackage, isOff = isOff))
            }

            ServiceCommand.GlobalBack -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            ServiceCommand.DisableSelf -> {
                Timber.tag(TAG).i("disableSelf() requested by engine")
                disableSelf()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Watchdog — 12 s total per package
    // ---------------------------------------------------------------------------

    private fun resetWatchdog() {
        handler.removeCallbacksAndMessages(watchdogToken)
        handler.postAtTime(
            {
                Timber.tag(TAG).w("Watchdog fired for pkg=%s step=%s", currentPkg, step)
                outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
                resetToIdle()
            },
            watchdogToken,
            SystemClock.uptimeMillis() + WATCHDOG_MS
        )
    }

    private fun cancelWatchdog() {
        handler.removeCallbacksAndMessages(watchdogToken)
    }

    private fun failCurrentPkg() {
        outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
        resetToIdle()
    }

    private fun resetToIdle() {
        cancelWatchdog()
        step = FlowStep.IDLE
    }

    companion object {
        private const val TAG = "A11ySvc"
        private const val WATCHDOG_MS = 12_000L
        private const val MAX_RETRIES_APP_ROW = 5
        private const val MAX_RETRIES_START_SETTINGS = 5
        private const val MAX_RETRIES_DIALOG = 8
    }
}
