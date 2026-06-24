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
// Per-package state machine states
// ---------------------------------------------------------------------------

/**
 * Tracks where we are in the MagicOS battery-detail dialog flow for a single package.
 *
 * Flow:
 *   IDLE
 *   → OPENING_BATTERY_SCREEN  (intent fired)
 *   → WAITING_BATTERY_SCREEN  (waiting for TYPE_WINDOW_STATE_CHANGED to confirm screen)
 *   → CLICKING_START_SETTINGS (battery detail screen visible — click "Starteinstellungen" row)
 *   → WAITING_DIALOG          (row clicked — waiting for dialog to appear)
 *   → HANDLING_MASTER         (dialog visible — handle master toggle)
 *   → WAITING_AFTER_MASTER    (master toggle disabled — Handler delay before sub-toggles)
 *   → CLICKING_AUTOSTART      (click "Auto-Start" toggle if ON)
 *   → CLICKING_SECONDARY      (click "Sekundärer Start" toggle if ON and config says so)
 *   → CLICKING_BACKGROUND     (click "Im Hintergrund ausführen" toggle if ON and config says so)
 *   → CLICKING_OK             (click OK button)
 *   → WAITING_BACK            (OK clicked — short Handler delay then GLOBAL_ACTION_BACK)
 *   → DONE_PKG                (complete)
 *   → ERROR_PKG               (unrecoverable failure)
 */
enum class PerPkgState {
    IDLE,
    OPENING_BATTERY_SCREEN,
    WAITING_BATTERY_SCREEN,
    CLICKING_START_SETTINGS,
    WAITING_DIALOG,
    HANDLING_MASTER,
    WAITING_AFTER_MASTER,
    CLICKING_AUTOSTART,
    CLICKING_SECONDARY,
    CLICKING_BACKGROUND,
    CLICKING_OK,
    WAITING_BACK,
    DONE_PKG,
    ERROR_PKG
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * AccessibilityService implementing the MagicOS battery-detail dialog automation.
 *
 * The service drives a per-package state machine rather than a single global one,
 * enabling clean sequencing even across multiple events and Handler callbacks.
 *
 * Lifecycle: System binds at Accessibility activation.
 *   onServiceConnected → hand Channel to [BulkAutostopEngine]
 *   onAccessibilityEvent → advance per-package state machine
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

    // Per-package state machine
    @Volatile private var currentPkg: String = ""
    @Volatile private var perPkgState: PerPkgState = PerPkgState.IDLE

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
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val profile = profileResolver.active()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(pkg, cls, profile)
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

    private fun handleWindowStateChanged(pkg: String, cls: String, profile: OemProfile) {
        Timber.tag(TAG).d("WindowStateChanged pkg=%s cls=%s perPkgState=%s", pkg, cls, perPkgState)

        val isOurScreen = profile.expectedScreenSignature()
            .any { sig -> cls.contains(sig, ignoreCase = true) || pkg == sig }

        if (!isOurScreen) {
            // Not our screen — do not touch state or watchdog
            return
        }

        resetWatchdog(WATCHDOG_MS)

        // Publish generic ScreenReady for the engine's coroutine-based await
        val root = rootInActiveWindow
        if (root == null) {
            outbound.trySend(NodeEvent.RootUnavailable)
            return
        }
        outbound.trySend(
            NodeEvent.ScreenReady(
                packageName = pkg,
                className = cls,
                rootBoundsHash = root.hashCode()
            )
        )

        // Advance state machine: if we were waiting for this screen, proceed
        if (perPkgState == PerPkgState.WAITING_BATTERY_SCREEN) {
            Timber.tag(TAG).d("Battery screen confirmed for %s — advancing to CLICKING_START_SETTINGS", currentPkg)
            perPkgState = PerPkgState.CLICKING_START_SETTINGS
            clickStartSettingsRow(root, profile)
        }
    }

    // ---------------------------------------------------------------------------
    // TYPE_WINDOW_CONTENT_CHANGED
    // ---------------------------------------------------------------------------

    private fun handleContentChanged(profile: OemProfile) {
        val state = perPkgState
        if (state == PerPkgState.IDLE || state == PerPkgState.DONE_PKG ||
            state == PerPkgState.ERROR_PKG || state == PerPkgState.WAITING_BACK ||
            state == PerPkgState.WAITING_AFTER_MASTER
        ) {
            // Nothing to do in these states on content change
            return
        }

        val root = rootInActiveWindow ?: run {
            outbound.trySend(NodeEvent.RootUnavailable)
            return
        }

        when (state) {
            PerPkgState.WAITING_DIALOG -> {
                if (profile.isDialogVisible(root)) {
                    Timber.tag(TAG).d("Dialog appeared for %s", currentPkg)
                    outbound.trySend(NodeEvent.DialogAppeared(currentPkg))
                    perPkgState = PerPkgState.HANDLING_MASTER
                    handleMasterToggle(root, profile)
                }
            }
            PerPkgState.HANDLING_MASTER -> {
                // Re-check in case dialog rendered late
                if (profile.isDialogVisible(root)) {
                    handleMasterToggle(root, profile)
                }
            }
            PerPkgState.CLICKING_AUTOSTART -> {
                clickAutoStart(root, profile)
            }
            PerPkgState.CLICKING_SECONDARY -> {
                clickSecondaryStart(root, profile)
            }
            PerPkgState.CLICKING_BACKGROUND -> {
                clickBackgroundRun(root, profile)
            }
            PerPkgState.CLICKING_OK -> {
                clickOkButton(root, profile)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------
    // State machine step implementations
    // ---------------------------------------------------------------------------

    private fun clickStartSettingsRow(root: AccessibilityNodeInfo, profile: OemProfile) {
        val row = profile.findStartSettingsRow(root)
        if (row == null) {
            Timber.tag(TAG).w("Starteinstellungen row NOT found for %s", currentPkg)
            // Fall back to legacy single-toggle path
            handleLegacyToggleClick(root, profile)
            return
        }
        val clicked = row.isClickable && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.tag(TAG).d("Clicked Starteinstellungen row for %s", currentPkg)
            outbound.trySend(NodeEvent.StartSettingsRowClicked(currentPkg))
            perPkgState = PerPkgState.WAITING_DIALOG
        } else {
            Timber.tag(TAG).w("Starteinstellungen row click failed for %s — trying parent", currentPkg)
            val parent = row.parent
            val parentClicked = parent != null &&
                parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (parentClicked) {
                outbound.trySend(NodeEvent.StartSettingsRowClicked(currentPkg))
                perPkgState = PerPkgState.WAITING_DIALOG
            } else {
                Timber.tag(TAG).e("Starteinstellungen row not clickable for %s", currentPkg)
                outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
                perPkgState = PerPkgState.ERROR_PKG
            }
        }
    }

    private fun handleMasterToggle(root: AccessibilityNodeInfo, profile: OemProfile) {
        val master = profile.findMasterToggle(root)
        if (master == null) {
            // No master toggle visible — proceed directly to auto-start sub-toggle
            Timber.tag(TAG).d("No master toggle found for %s — skipping to CLICKING_AUTOSTART", currentPkg)
            perPkgState = PerPkgState.CLICKING_AUTOSTART
            clickAutoStart(root, profile)
            return
        }

        val isOn = master.isChecked || master.isSelected
        if (isOn) {
            Timber.tag(TAG).d("Master toggle is ON for %s — clicking to disable", currentPkg)
            val clicked = master.isClickable && master.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                Timber.tag(TAG).w("Master toggle click failed for %s", currentPkg)
                outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
                perPkgState = PerPkgState.ERROR_PKG
                return
            }
            outbound.trySend(NodeEvent.MasterToggleDisabled(currentPkg))
            perPkgState = PerPkgState.WAITING_AFTER_MASTER
            // Delay before sub-toggles become active
            val delay = profile.masterToggleOffDelayMs()
            handler.postDelayed(
                {
                    Timber.tag(TAG).d("Master delay elapsed for %s — advancing to CLICKING_AUTOSTART", currentPkg)
                    perPkgState = PerPkgState.CLICKING_AUTOSTART
                    val freshRoot = rootInActiveWindow
                    if (freshRoot != null) {
                        clickAutoStart(freshRoot, profile)
                    } else {
                        outbound.trySend(NodeEvent.RootUnavailable)
                        perPkgState = PerPkgState.ERROR_PKG
                    }
                },
                delay
            )
        } else {
            // Master already OFF
            Timber.tag(TAG).d("Master toggle already OFF for %s", currentPkg)
            perPkgState = PerPkgState.CLICKING_AUTOSTART
            clickAutoStart(root, profile)
        }
    }

    private fun clickAutoStart(root: AccessibilityNodeInfo, profile: OemProfile) {
        val toggle = profile.findAutoStartToggle(root)
        if (toggle == null) {
            Timber.tag(TAG).w("Auto-Start toggle not found for %s", currentPkg)
            // Not a fatal error — proceed to secondary
            advanceFromAutostart(root, profile)
            return
        }
        val isOn = toggle.isChecked || toggle.isSelected
        if (isOn) {
            if (!toggle.isEnabled) {
                Timber.tag(TAG).w("Auto-Start toggle is disabled (greyed) for %s — master still ON?", currentPkg)
                outbound.trySend(NodeEvent.ToggleDisabledByMaster(currentPkg))
                perPkgState = PerPkgState.ERROR_PKG
                return
            }
            val clicked = toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                Timber.tag(TAG).w("Auto-Start toggle click failed for %s", currentPkg)
            } else {
                Timber.tag(TAG).d("Auto-Start toggle clicked OFF for %s", currentPkg)
            }
        } else {
            Timber.tag(TAG).d("Auto-Start already OFF for %s", currentPkg)
        }
        advanceFromAutostart(root, profile)
    }

    private fun advanceFromAutostart(root: AccessibilityNodeInfo, profile: OemProfile) {
        perPkgState = PerPkgState.CLICKING_SECONDARY
        clickSecondaryStart(root, profile)
    }

    private fun clickSecondaryStart(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (!profile.disableSecondaryStart()) {
            Timber.tag(TAG).d("disableSecondaryStart=false for %s — skipping", currentPkg)
            perPkgState = PerPkgState.CLICKING_BACKGROUND
            clickBackgroundRun(root, profile)
            return
        }
        val toggle = profile.findSecondaryStartToggle(root)
        if (toggle != null) {
            val isOn = toggle.isChecked || toggle.isSelected
            if (isOn && toggle.isEnabled) {
                toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.tag(TAG).d("Sekundärer Start toggled OFF for %s", currentPkg)
            }
        } else {
            Timber.tag(TAG).d("Sekundärer Start toggle not found for %s", currentPkg)
        }
        perPkgState = PerPkgState.CLICKING_BACKGROUND
        clickBackgroundRun(root, profile)
    }

    private fun clickBackgroundRun(root: AccessibilityNodeInfo, profile: OemProfile) {
        if (!profile.disableBackgroundRun()) {
            Timber.tag(TAG).d("disableBackgroundRun=false for %s — skipping", currentPkg)
            perPkgState = PerPkgState.CLICKING_OK
            clickOkButton(root, profile)
            return
        }
        val toggle = profile.findBackgroundRunToggle(root)
        if (toggle != null) {
            val isOn = toggle.isChecked || toggle.isSelected
            if (isOn && toggle.isEnabled) {
                toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.tag(TAG).d("Im Hintergrund ausführen toggled OFF for %s", currentPkg)
            }
        } else {
            Timber.tag(TAG).d("Background run toggle not found for %s", currentPkg)
        }
        perPkgState = PerPkgState.CLICKING_OK
        clickOkButton(root, profile)
    }

    private fun clickOkButton(root: AccessibilityNodeInfo, profile: OemProfile) {
        val ok = profile.findOkButton(root)
        if (ok == null) {
            Timber.tag(TAG).w("OK button not found for %s", currentPkg)
            outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
            perPkgState = PerPkgState.ERROR_PKG
            return
        }
        val clicked = ok.isClickable && ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.tag(TAG).d("OK button clicked for %s", currentPkg)
            perPkgState = PerPkgState.WAITING_BACK
            // Short delay then navigate back
            handler.postDelayed(
                {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    outbound.trySend(NodeEvent.AllTogglesDisabled(currentPkg))
                    perPkgState = PerPkgState.DONE_PKG
                    Timber.tag(TAG).i("AllTogglesDisabled emitted for %s", currentPkg)
                },
                200L
            )
        } else {
            Timber.tag(TAG).w("OK button click failed for %s", currentPkg)
            outbound.trySend(NodeEvent.ToggleNotClickable(currentPkg))
            perPkgState = PerPkgState.ERROR_PKG
        }
    }

    // ---------------------------------------------------------------------------
    // Legacy single-toggle path (fallback when Starteinstellungen row is absent)
    // ---------------------------------------------------------------------------

    /**
     * Falls back to the old single-toggle logic for devices that show the toggle
     * directly on the battery detail screen without the dialog (or for non-battery screens).
     */
    private fun handleLegacyToggleClick(root: AccessibilityNodeInfo, profile: OemProfile) {
        val toggle = profile.findAutoLaunchToggleNode(root)
        if (toggle == null) {
            outbound.trySend(NodeEvent.ToggleNotFound(currentPkg))
            perPkgState = PerPkgState.ERROR_PKG
            return
        }
        performClickOnToggle(toggle, currentPkg)
        perPkgState = PerPkgState.DONE_PKG
    }

    // ---------------------------------------------------------------------------
    // Command handling: Engine → Service
    // ---------------------------------------------------------------------------

    private fun handleCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.ClickToggleForPackage -> {
                // Set the target package and arm the state machine
                currentPkg = cmd.targetPackage
                perPkgState = PerPkgState.WAITING_BATTERY_SCREEN
                Timber.tag(TAG).d("ClickToggleForPackage %s — armed WAITING_BATTERY_SCREEN", currentPkg)

                // If the battery screen is already visible, advance immediately
                val root = rootInActiveWindow
                if (root != null) {
                    val profile = profileResolver.active()
                    val alreadyOnScreen = profile.expectedScreenSignature().any { sig ->
                        root.packageName?.contains(sig, ignoreCase = true) == true
                    }
                    if (alreadyOnScreen) {
                        Timber.tag(TAG).d("Battery screen already active for %s", currentPkg)
                        perPkgState = PerPkgState.CLICKING_START_SETTINGS
                        clickStartSettingsRow(root, profile)
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
                val off = toggle == null || (!toggle.isChecked && !toggle.isSelected)
                outbound.trySend(NodeEvent.VerifyResult(cmd.targetPackage, isOff = off))
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
    // Legacy click helper (used by fallback path)
    // ---------------------------------------------------------------------------

    private fun performClickOnToggle(node: AccessibilityNodeInfo, targetPackage: String) {
        if (!node.isEnabled) {
            Timber.tag(TAG).w("Toggle for %s is disabled — masked by master toggle?", targetPackage)
            outbound.trySend(NodeEvent.ToggleDisabledByMaster(targetPackage))
            return
        }
        val clicked = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            outbound.trySend(NodeEvent.ToggleClicked(targetPackage, viaGesture = false))
        } else {
            outbound.trySend(NodeEvent.ToggleNotClickable(targetPackage))
        }
    }

    // ---------------------------------------------------------------------------
    // Watchdog
    // ---------------------------------------------------------------------------

    private fun resetWatchdog(timeoutMs: Long) {
        handler.removeCallbacksAndMessages(watchdogToken)
        handler.postAtTime(
            { outbound.trySend(NodeEvent.WatchdogTimeout) },
            watchdogToken,
            SystemClock.uptimeMillis() + timeoutMs
        )
    }

    private fun cancelWatchdog() {
        handler.removeCallbacksAndMessages(watchdogToken)
    }

    companion object {
        private const val TAG = "A11ySvc"
        private const val WATCHDOG_MS = 10_000L
    }
}
