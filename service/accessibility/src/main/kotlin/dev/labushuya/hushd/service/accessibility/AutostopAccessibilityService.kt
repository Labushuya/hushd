// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Autostart Manager Maintainers
package dev.labushuya.hushd.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import dev.labushuya.hushd.core.automation.BulkAutostopEngine
import dev.labushuya.hushd.core.automation.NodeEvent
import dev.labushuya.hushd.core.automation.oem.OemProfileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * AccessibilityService, der den Auto-Start-Toggle in com.hihonor.systemmanager
 * (oder dem aktiven OEM-Pendant) klickt. Scoped via packageNames im XML — sieht
 * KEINE Events aus anderen Apps.
 *
 * Lifecycle: System bindet bei Aktivierung in den Accessibility-Settings.
 * onServiceConnected -> Channel an [BulkAutostopEngine] aushändigen.
 * onAccessibilityEvent -> ausschließlich auf TYPE_WINDOW_STATE_CHANGED und
 * TYPE_WINDOW_CONTENT_CHANGED hören (siehe accessibility_service_config.xml).
 */
@AndroidEntryPoint
class AutostopAccessibilityService : AccessibilityService() {

    @Inject lateinit var engine: BulkAutostopEngine
    @Inject lateinit var profileResolver: OemProfileResolver

    /**
     * Outbound-Channel: Service publiziert NodeEvents, Engine konsumiert.
     * BufferOverflow.DROP_OLDEST: bei Burst-Events lieber neueste behalten,
     * State-Machine arbeitet ohnehin Edge-getriggert.
     */
    private val outbound: Channel<NodeEvent> =
        Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: Channel<NodeEvent> get() = outbound

    private val serviceScope = MainScope()
    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogToken = Any()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.tag(TAG).i("Connected; profile=%s", profileResolver.active().id)
        engine.attachService(this)
        serviceScope.launch(Dispatchers.Main.immediate) {
            engine.commands.collect { cmd -> handleCommand(cmd) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val profile = profileResolver.active()
        if (!profile.expectedScreenSignature().any { sig -> cls.contains(sig) || pkg == sig }) {
            // fremder Screen -> nicht reagieren, aber Watchdog NICHT resetten
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Timber.tag(TAG).d("WindowStateChanged pkg=%s cls=%s", pkg, cls)
                resetWatchdog(timeoutMs = WATCHDOG_MS)
                val root = rootInActiveWindow ?: run {
                    outbound.trySend(NodeEvent.RootUnavailable)
                    return
                }
                outbound.trySend(NodeEvent.ScreenReady(packageName = pkg, className = cls, rootBoundsHash = root.hashCode()))
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                outbound.trySend(NodeEvent.ContentChanged)
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("onInterrupt")
        outbound.trySend(NodeEvent.Interrupted)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Timber.tag(TAG).i("onUnbind — user disabled service")
        cancelWatchdog()
        engine.detachService()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        outbound.close()
        super.onDestroy()
    }

    // --- Command-Handling: Engine -> Service ---

    private fun handleCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.ClickToggleForPackage -> {
                val root = rootInActiveWindow
                if (root == null) {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                val toggle = findToggleNode(root, cmd.targetPackage)
                if (toggle == null) {
                    outbound.trySend(NodeEvent.ToggleNotFound(cmd.targetPackage)); return
                }
                performClickOnToggle(toggle, cmd.targetPackage)
            }
            is ServiceCommand.VerifyToggleOff -> {
                val root = rootInActiveWindow
                if (root == null) {
                    outbound.trySend(NodeEvent.RootUnavailable); return
                }
                val toggle = findToggleNode(root, cmd.targetPackage)
                val off = toggle?.isChecked == false
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

    // --- Node-Traversal-Helper ---

    /**
     * Findet den Auto-Start-Toggle-Node für [targetPackage].
     * Strategie:
     *   1) Adapter-spezifischer Lookup (Profile-Strategy)
     *   2) Text-Match auf lokalisierte Labels (DE, EN, Fallback CN)
     *   3) Resource-ID-Fallback (driftet zwischen Builds -> letzte Wahl)
     */
    private fun findToggleNode(root: AccessibilityNodeInfo, targetPackage: String): AccessibilityNodeInfo? {
        val profile = profileResolver.active()
        profile.findAutoLaunchToggleNode(root)?.let { return it }

        // Fallback 1: Text-Match auf Auto-Launch-Labels
        val labels = listOf("Auto-launch", "Auto launch", "Autostart", "Auto-Start",
            "Automatischer Start", "自启动", "开机自启")
        for (label in labels) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            val toggle = matches.firstOrNull { it.isVisibleToUser }?.let { textNode ->
                // Switch im Parent-Subtree suchen
                bfsFind(textNode.parent ?: textNode) { n ->
                    n.isClickable &&
                        (n.className?.contains("Switch", ignoreCase = true) == true ||
                            n.className?.contains("CheckBox", ignoreCase = true) == true)
                }
            }
            if (toggle != null) return toggle
        }

        // Fallback 2: ResourceID (Drift-Risiko)
        val resourceIds = listOf(
            "com.hihonor.systemmanager:id/auto_start_switch",
            "com.hihonor.systemmanager:id/switchWidget",
            "com.huawei.systemmanager:id/auto_start_switch"
        )
        for (rid in resourceIds) {
            root.findAccessibilityNodeInfosByViewId(rid).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun bfsFind(
        start: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(start)
        var iter = 0
        while (queue.isNotEmpty() && iter++ < MAX_BFS_NODES) {
            val n = queue.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun performClickOnToggle(node: AccessibilityNodeInfo, targetPackage: String) {
        if (!node.isEnabled) {
            // greyed-out: Master-Toggle "Manage automatically" aktiv ODER bereits off
            Timber.tag(TAG).w("Toggle for %s is disabled — masked by master toggle?", targetPackage)
            outbound.trySend(NodeEvent.ToggleDisabledByMaster(targetPackage))
            return
        }
        val clicked = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            outbound.trySend(NodeEvent.ToggleClicked(targetPackage, viaGesture = false))
            return
        }
        // dispatchGesture-Fallback bewusst NICHT verwendet — siehe ADR-0003
        // (canPerformGestures=false im XML; reduziert Angriffsfläche).
        // TODO(v2): Fallback via Parent-Click oder ACTION_FOCUS prüfen.
        outbound.trySend(NodeEvent.ToggleNotClickable(targetPackage))
    }

    // --- Watchdog ---

    private fun resetWatchdog(timeoutMs: Long) {
        watchdog.removeCallbacksAndMessages(watchdogToken)
        watchdog.postAtTime(
            { outbound.trySend(NodeEvent.WatchdogTimeout) },
            watchdogToken,
            SystemClock.uptimeMillis() + timeoutMs
        )
    }

    private fun cancelWatchdog() {
        watchdog.removeCallbacksAndMessages(watchdogToken)
    }

    sealed interface ServiceCommand {
        data class ClickToggleForPackage(val targetPackage: String) : ServiceCommand
        data class VerifyToggleOff(val targetPackage: String) : ServiceCommand
        data object GlobalBack : ServiceCommand
        data object DisableSelf : ServiceCommand
    }

    companion object {
        private const val TAG = "A11ySvc"
        private const val WATCHDOG_MS = 8_000L
        private const val MAX_BFS_NODES = 2_000
    }
}
