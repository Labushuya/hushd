// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.labushuya.hushd.core.automation.oem.OemProfileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sealed event class für Service -> Engine.
 */
sealed interface NodeEvent {
    data class ScreenReady(val packageName: String, val className: String, val rootBoundsHash: Int) : NodeEvent
    data object ContentChanged : NodeEvent
    data object RootUnavailable : NodeEvent
    data class ToggleNotFound(val pkg: String) : NodeEvent
    data class ToggleClicked(val pkg: String, val viaGesture: Boolean) : NodeEvent
    data class ToggleNotClickable(val pkg: String) : NodeEvent
    data class ToggleDisabledByMaster(val pkg: String) : NodeEvent
    data class VerifyResult(val pkg: String, val isOff: Boolean) : NodeEvent
    data object WatchdogTimeout : NodeEvent
    data object Interrupted : NodeEvent
    // MagicOS battery-detail dialog flow events
    data class StartSettingsRowClicked(val pkg: String) : NodeEvent
    data class DialogAppeared(val pkg: String) : NodeEvent
    data class MasterToggleDisabled(val pkg: String) : NodeEvent
    /** Emitted when all dialog toggles are disabled and OK was tapped. Treated as success. */
    data class AllTogglesDisabled(val pkg: String) : NodeEvent
}

sealed interface AutomationError {
    data object MissingA11yPermission : AutomationError
    data object MissingOverlayPermission : AutomationError
    data object SettingsScreenNotResolved : AutomationError
    data object ToggleNotFound : AutomationError
    data object ToggleMaskedByMaster : AutomationError
    data object WatchdogTimeout : AutomationError
    data object UserCancelled : AutomationError
    data class Unexpected(val msg: String) : AutomationError
}

/**
 * State-Machine + Iteration über Package-Liste.
 * Singleton, lebt außerhalb von ViewModels (überlebt Config-Change / Process-Death wäre extra),
 * @Singleton-Hilt-Scope.
 */
@Singleton
class BulkAutostopEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val resolver: OemProfileResolver
) {

    sealed interface State {
        data object Idle : State
        data object PermissionCheck : State
        data class Iterating(val idx: Int, val total: Int, val currentPkg: String) : State
        data class OpeningSettings(val pkg: String, val attempt: Int) : State
        data class AwaitingToggle(val pkg: String) : State
        data class Clicking(val pkg: String) : State
        data class Verifying(val pkg: String) : State
        data class Cooldown(val pkg: String, val msLeft: Long) : State
        data class Done(val ok: Int, val failed: List<Pair<String, AutomationError>>) : State
        data class Error(val pkg: String?, val cause: AutomationError) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<ServiceCommand>(replay = 0, extraBufferCapacity = 16)
    val commands: Flow<ServiceCommand> = _commands.asSharedFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serviceRef: WeakReference<A11yServiceHandle>? = null
    private var engineJob: Job? = null
    @Volatile private var cancelRequested: Boolean = false

    fun attachService(svc: A11yServiceHandle) {
        serviceRef = WeakReference(svc)
        // Engine konsumiert outbound-events
        scope.launch {
            for (ev in svc.events) onNodeEvent(ev)
        }
    }

    fun detachService() {
        serviceRef?.clear()
        serviceRef = null
    }

    /**
     * Cancels any running engine job and resets state to Idle immediately.
     * The OverlayService observes Idle and removes itself.
     */
    fun cancel() {
        engineJob?.cancel()
        engineJob = null
        cancelRequested = false
        _state.value = State.Idle
    }

    /**
     * Signals the running loop to stop at the next iteration boundary.
     * Prefer [cancel] when an immediate stop is needed (e.g. user taps Abbrechen).
     */
    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * Hauptentry: läuft sequenziell über alle Pakete.
     * Single-Operation-Lock via Mutex.
     */
    fun runFor(packages: List<String>): Flow<State> {
        engineJob?.cancel()
        cancelRequested = false
        engineJob = scope.launch {
            mutex.withLock { runInternal(packages) }
        }
        return state
    }

    private suspend fun runInternal(packages: List<String>) {
        _state.value = State.PermissionCheck
        val svc = serviceRef?.get() ?: run {
            _state.value = State.Error(null, AutomationError.MissingA11yPermission); return
        }
        val profile = resolver.active()
        val failed = mutableListOf<Pair<String, AutomationError>>()
        var ok = 0

        // Open the battery list once — the service navigates from there per-package
        val listIntent = runCatching {
            profile.openBatteryListScreen(ctx)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrElse {
            Timber.tag(TAG).e(it, "Failed to build battery list intent")
            _state.value = State.Error(null, AutomationError.SettingsScreenNotResolved)
            return
        }
        runCatching { ctx.startActivity(listIntent) }.onFailure {
            Timber.tag(TAG).e(it, "Failed to launch battery list activity")
            _state.value = State.Error(null, AutomationError.SettingsScreenNotResolved)
            return
        }

        for ((i, pkg) in packages.withIndex()) {
            if (cancelRequested) {
                _state.value = State.Error(pkg, AutomationError.UserCancelled); break
            }
            _state.value = State.Iterating(idx = i, total = packages.size, currentPkg = pkg)
            val result = processOne(pkg, profile)
            if (result == null) ok++ else failed += pkg to result
            _state.value = State.Cooldown(pkg, profile.cooldownMs())
            delay(profile.cooldownMs())
        }
        _state.value = State.Done(ok = ok, failed = failed)
    }

    private suspend fun processOne(
        pkg: String,
        profile: dev.labushuya.hushd.core.automation.oem.OemProfile
    ): AutomationError? {
        val appLabel = resolveAppLabel(pkg)

        for (attempt in 0 until profile.maxRetries()) {
            _state.value = State.OpeningSettings(pkg, attempt)

            // 1) Arm the service for this package — it will locate the app row in the battery
            //    list that is already (or will soon be) visible and drive the full dialog flow.
            //    The battery list intent was already launched in runInternal before the loop.
            _state.value = State.Clicking(pkg)
            _commands.emit(ServiceCommand.ClickToggleForPackage(pkg, appLabel))

            // 2) Wait for the full dialog flow to complete (AllTogglesDisabled = success) or fail
            val click = awaitClickOutcome(
                pkg,
                timeoutMs = profile.screenReadyTimeoutMs() + profile.clickTimeoutMs()
            )
            when (click) {
                ClickOutcome.Clicked -> Unit
                ClickOutcome.NotFound -> {
                    if (attempt < profile.maxRetries() - 1) continue
                    return AutomationError.ToggleNotFound
                }
                ClickOutcome.MaskedByMaster -> return AutomationError.ToggleMaskedByMaster
                ClickOutcome.Timeout -> {
                    if (attempt < profile.maxRetries() - 1) continue
                    return AutomationError.WatchdogTimeout
                }
            }

            // 3) Verify — after the dialog is dismissed the toggle should be OFF
            _state.value = State.Verifying(pkg)
            _commands.emit(ServiceCommand.VerifyToggleOff(pkg))
            val verify = awaitEventMatching<NodeEvent.VerifyResult>(
                timeoutMs = VERIFY_TIMEOUT_MS
            ) { it.pkg == pkg }

            return if (verify?.isOff == true) null else AutomationError.Unexpected("verify-failed")
        }
        return AutomationError.SettingsScreenNotResolved
    }

    /**
     * Resolves the user-visible application label for [pkg].
     * Falls back to [pkg] itself if the label cannot be read (app uninstalled mid-run, etc.).
     */
    private fun resolveAppLabel(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    // --- Event-await Helpers ---

    private val inbox = MutableSharedFlow<NodeEvent>(replay = 0, extraBufferCapacity = 64)

    private suspend fun onNodeEvent(ev: NodeEvent) {
        inbox.emit(ev)
    }

    /**
     * Suspends until the inbox emits an event of type [T], or [timeoutMs] elapses.
     *
     * FIX: The previous implementation used `inbox.collect { return@collect }` which only exits
     * the lambda, not the collect call — SharedFlow.collect() never returns naturally, so the
     * coroutine always ran until the timeout even after matching an event.
     * `filterIsInstance<T>().first()` short-circuits correctly on the first matching emission.
     */
    private suspend inline fun <reified T : NodeEvent> awaitEvent(timeoutMs: Long): T? =
        withTimeoutOrNull(timeoutMs) {
            inbox.filterIsInstance<T>().first()
        }

    /**
     * Like [awaitEvent] but also applies [predicate] to the matched event.
     *
     * Uses `filter { it is T && predicate(it as T) }.first()` so the coroutine suspends cleanly
     * and resumes as soon as a matching event arrives rather than burning the full timeout.
     */
    private suspend inline fun <reified T : NodeEvent> awaitEventMatching(
        timeoutMs: Long, crossinline predicate: (T) -> Boolean
    ): T? = withTimeoutOrNull(timeoutMs) {
        @Suppress("UNCHECKED_CAST")
        inbox.filter { it is T && predicate(it as T) }.first() as T
    }

    private enum class ClickOutcome { Clicked, NotFound, MaskedByMaster, Timeout }

    /**
     * Awaits a click-outcome event for [pkg].
     *
     * Also handles [NodeEvent.ToggleNotClickable] (which the previous impl silently ignored,
     * causing every not-clickable case to burn the full 4 s timeout before returning Timeout).
     */
    private suspend fun awaitClickOutcome(pkg: String, timeoutMs: Long): ClickOutcome =
        withTimeoutOrNull(timeoutMs) {
            inbox.filter { ev ->
                when (ev) {
                    is NodeEvent.ToggleClicked -> ev.pkg == pkg
                    is NodeEvent.AllTogglesDisabled -> ev.pkg == pkg
                    is NodeEvent.ToggleNotFound -> ev.pkg == pkg
                    is NodeEvent.ToggleNotClickable -> ev.pkg == pkg
                    is NodeEvent.ToggleDisabledByMaster -> ev.pkg == pkg
                    is NodeEvent.WatchdogTimeout -> true
                    else -> false
                }
            }.first().let { ev ->
                when (ev) {
                    is NodeEvent.ToggleClicked -> ClickOutcome.Clicked
                    // AllTogglesDisabled = full dialog flow succeeded → treat as Clicked
                    is NodeEvent.AllTogglesDisabled -> ClickOutcome.Clicked
                    is NodeEvent.ToggleNotFound -> ClickOutcome.NotFound
                    is NodeEvent.ToggleNotClickable -> ClickOutcome.NotFound  // treat as not found
                    is NodeEvent.ToggleDisabledByMaster -> ClickOutcome.MaskedByMaster
                    else -> ClickOutcome.Timeout
                }
            }
        } ?: ClickOutcome.Timeout

    companion object {
        private const val TAG = "BulkEngine"
        private const val VERIFY_TIMEOUT_MS = 3_000L
    }
}
