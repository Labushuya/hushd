// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.feature.applist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.labushuya.hushd.core.automation.BulkAutostopEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val versionName: String?,
    val versionCode: Long
)

enum class AppFilter { ALL, USER_ONLY, SYSTEM_ONLY }

data class AppListUiState(
    val isLoading: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val filter: AppFilter = AppFilter.USER_ONLY,
    val selection: Set<String> = emptySet(),
    val query: String = ""
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engine: BulkAutostopEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    /** Reflects the engine's run progress; UI can collect this to show an inline status. */
    val engineState: StateFlow<BulkAutostopEngine.State> = engine.state

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val items = withContext(Dispatchers.IO) { loadPackages() }
            _state.update { it.copy(isLoading = false, apps = items) }
        }
    }

    private fun loadPackages(): List<AppItem> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = pm.getInstalledApplications(flags)
        return installed.map { info ->
            AppItem(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                versionName = runCatching { pm.getPackageInfo(info.packageName, 0).versionName }.getOrNull(),
                versionCode = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(info.packageName, 0).longVersionCode
                }.getOrDefault(0L)
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun setFilter(f: AppFilter) = _state.update { it.copy(filter = f) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleSelection(pkg: String) = _state.update { s ->
        s.copy(selection = if (s.selection.contains(pkg)) s.selection - pkg else s.selection + pkg)
    }

    fun selectAllVisible() = _state.update { s ->
        s.copy(selection = visible(s).map { it.packageName }.toSet())
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /**
     * Returns the currently visible (filtered + searched) subset of [AppListUiState.apps].
     * Exposed publicly so the UI can use it for display without re-deriving filter logic.
     */
    fun visibleApps(s: AppListUiState = _state.value): List<AppItem> = visible(s)

    private fun visible(s: AppListUiState): List<AppItem> =
        s.apps.asSequence()
            .filter { a ->
                when (s.filter) {
                    AppFilter.ALL -> true
                    AppFilter.USER_ONLY -> !a.isSystemApp
                    AppFilter.SYSTEM_ONLY -> a.isSystemApp
                }
            }
            .filter { if (s.query.isBlank()) true else it.label.contains(s.query, ignoreCase = true) || it.packageName.contains(s.query, ignoreCase = true) }
            .toList()

    /**
     * Kicks off a bulk autostop run for the currently selected packages.
     *
     * Preconditions (caller must verify before invoking):
     *  1. [android.provider.Settings.canDrawOverlays] is true
     *  2. Accessibility service is bound (check via AccessibilityManager)
     *
     * The [OverlayService][dev.labushuya.hushd.service.overlay.OverlayService] must be
     * started by the caller before calling this method so the overlay window is visible
     * during the run.
     */
    fun startBulkRun() {
        val packages = _state.value.selection.toList()
        if (packages.isEmpty()) return
        engine.runFor(packages)
    }

    /**
     * Cancels a running bulk autostop job. No-op when engine is idle.
     */
    fun cancelBulkRun() {
        engine.requestCancel()
    }
}

