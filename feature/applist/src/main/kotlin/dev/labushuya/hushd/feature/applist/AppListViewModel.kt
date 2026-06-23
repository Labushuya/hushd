// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.feature.applist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

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
}
