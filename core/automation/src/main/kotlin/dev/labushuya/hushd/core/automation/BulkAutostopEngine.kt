// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.labushuya.hushd.core.automation.oem.OemProfileResolver
import dev.labushuya.hushd.service.accessibility.AutostopAccessibilityService
import dev.labushuya.hushd.service.accessibility.AutostopAccessibilityService.ServiceCommand
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
    private var serviceRef: WeakReference<AutostopAccessibilityService>? = null
    private var runJob: Job? = null
    @Volatile private var cancelRequested: Boolean = false

    fun attachService(svc: AutostopAccessibilityService) {
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

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * Hauptentry: läuft sequenziell über alle Pakete.
     * Single-Operation-Lock via Mutex.
     */
    fun runFor(packages: List<String>): Flow<State> {
        runJob?.cancel()
        cancelRequested = false
        runJob = scope.launch {
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

    private suspend fun processOne(pkg: String, profile: dev.labushuya.hushd.core.automation.oem.OemProfile): AutomationError? {
        // 1) Settings öffnen
        for (attempt in 0 until profile.maxRetries()) {
            _state.value = State.OpeningSettings(pkg, attempt)
            val intent: Intent = profile.openSettingsForPackage(ctx, pkg)
                ?: return AutomationError.SettingsScreenNotResolved
            runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }.onFailure { return AutomationError.SettingsScreenNotResolved }

            // 2) Warten auf ScreenReady (via NodeEvent)
            _state.value = State.AwaitingToggle(pkg)
            val ready = awaitEvent<NodeEvent.ScreenReady>(timeoutMs = profile.screenReadyTimeoutMs())
            if (ready == null) {
                continue // retry
            }
            // 3) Click
            _state.value = State.Clicking(pkg)
            _commands.emit(ServiceCommand.ClickToggleForPackage(pkg))
            val click = awaitClickOutcome(pkg, timeoutMs = profile.clickTimeoutMs())
            when (click) {
                ClickOutcome.Clicked -> Unit
                ClickOutcome.NotFound -> return AutomationError.ToggleNotFound
                ClickOutcome.MaskedByMaster -> return AutomationError.ToggleMaskedByMaster
                ClickOutcome.Timeout -> return AutomationError.WatchdogTimeout
            }
            // 4) Verify
            _state.value = State.Verifying(pkg)
            _commands.emit(ServiceCommand.VerifyToggleOff(pkg))
            val verify = awaitEventMatching<NodeEvent.VerifyResult>(timeoutMs = profile.verifyTimeoutMs()) { it.pkg == pkg }
            // 5) Back navigieren
            _commands.emit(ServiceCommand.GlobalBack)
            delay(150)
            _commands.emit(ServiceCommand.GlobalBack)
            return if (verify?.isOff == true) null else AutomationError.Unexpected("verify-failed")
        }
        return AutomationError.SettingsScreenNotResolved
    }

    // --- Event-await Helpers ---

    private val inbox = MutableSharedFlow<NodeEvent>(replay = 0, extraBufferCapacity = 64)

    private suspend fun onNodeEvent(ev: NodeEvent) {
        inbox.emit(ev)
    }

    private suspend inline fun <reified T : NodeEvent> awaitEvent(timeoutMs: Long): T? =
        withTimeoutOrNull(timeoutMs) {
            var found: T? = null
            inbox.collect { ev -> if (ev is T) { found = ev; return@collect } }
            found
        }

    private suspend inline fun <reified T : NodeEvent> awaitEventMatching(
        timeoutMs: Long, crossinline predicate: (T) -> Boolean
    ): T? = withTimeoutOrNull(timeoutMs) {
        var found: T? = null
        inbox.collect { ev -> if (ev is T && predicate(ev)) { found = ev; return@collect } }
        found
    }

    private enum class ClickOutcome { Clicked, NotFound, MaskedByMaster, Timeout }

    private suspend fun awaitClickOutcome(pkg: String, timeoutMs: Long): ClickOutcome =
        withTimeoutOrNull(timeoutMs) {
            var out: ClickOutcome = ClickOutcome.Timeout
            inbox.collect { ev ->
                when (ev) {
                    is NodeEvent.ToggleClicked -> if (ev.pkg == pkg) { out = ClickOutcome.Clicked; return@collect }
                    is NodeEvent.ToggleNotFound -> if (ev.pkg == pkg) { out = ClickOutcome.NotFound; return@collect }
                    is NodeEvent.ToggleDisabledByMaster -> if (ev.pkg == pkg) { out = ClickOutcome.MaskedByMaster; return@collect }
                    is NodeEvent.WatchdogTimeout -> { out = ClickOutcome.Timeout; return@collect }
                    else -> Unit
                }
            }
            out
        } ?: ClickOutcome.Timeout
}
