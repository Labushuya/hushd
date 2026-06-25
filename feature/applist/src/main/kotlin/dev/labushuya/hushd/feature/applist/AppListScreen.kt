// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.feature.applist

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Brand accent matches HushdTheme: Color(0xFFEF4444)
private val BrandRed = Color(0xFFEF4444)
private val AutostartEnabledContainer = Color(0xFFFFEDED)
private val AutostartEnabledContent = Color(0xFFB91C1C)
private val AutostartDisabledContainer = Color(0xFFDCFCE7)
private val AutostartDisabledContent = Color(0xFF15803D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel = hiltViewModel(),
    onStartBulk: (packages: List<String>) -> Unit,
    onStartSingle: (packageName: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val visibleApps = remember(state.apps, state.activeTab, state.statusFilter, state.query) {
        viewModel.visibleApps(state)
    }

    val userAppCount = remember(state.apps) { state.apps.count { !it.isSystemApp } }
    val systemAppCount = remember(state.apps) { state.apps.count { it.isSystemApp } }

    val allVisibleSelected = visibleApps.isNotEmpty() &&
        visibleApps.all { state.selection.contains(it.packageName) }

    var searchVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedAppForSheet by remember { mutableStateOf<AppItem?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val hasSelection = state.selection.isNotEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Hushd", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    actions = {
                        if (searchVisible) {
                            IconButton(onClick = {
                                searchVisible = false
                                viewModel.setQuery("")
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Suche schliessen",
                                )
                            }
                        } else {
                            IconButton(onClick = { searchVisible = true }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Suche oeffnen",
                                )
                            }
                        }
                    },
                )
                AnimatedVisibility(visible = searchVisible) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { viewModel.setQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text("Apps suchen...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }),
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                // Tab row: User-Apps / System-Apps
                TabRow(
                    selectedTabIndex = if (state.activeTab == AppTab.USER) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Tab(
                        selected = state.activeTab == AppTab.USER,
                        onClick = { viewModel.setTab(AppTab.USER) },
                        text = { Text("User-Apps ($userAppCount)") },
                    )
                    Tab(
                        selected = state.activeTab == AppTab.SYSTEM,
                        onClick = { viewModel.setTab(AppTab.SYSTEM) },
                        text = { Text("System-Apps ($systemAppCount)") },
                    )
                }

                // Sub-filter chips
                StatusFilterChipRow(
                    current = state.statusFilter,
                    onSelect = { viewModel.setStatusFilter(it) },
                )

                // Header row: select-all checkbox + selection counter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allVisibleSelected,
                        onCheckedChange = { checked ->
                            if (checked) viewModel.selectAllVisible() else viewModel.clearSelection()
                        },
                    )
                    Text(
                        text = if (state.selection.isEmpty()) "Alle auswaehlen" else "${state.selection.size} ausgewaehlt",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (state.selection.isEmpty()) 0.6f else 1f,
                        ),
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = hasSelection,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                ) {
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text("Abwaehlen")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onStartBulk(state.selection.toList()) },
                        enabled = hasSelection,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("Autostart deaktivieren (${state.selection.size})")
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = BrandRed)
                }
            }

            visibleApps.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Keine Apps gefunden",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = if (hasSelection) 80.dp else 0.dp),
                ) {
                    items(
                        items = visibleApps,
                        key = { it.packageName },
                    ) { app ->
                        AppRow(
                            app = app,
                            isSelected = state.selection.contains(app.packageName),
                            onCheckboxToggle = { viewModel.toggleSelection(app.packageName) },
                            onRowClick = { selectedAppForSheet = app },
                        )
                    }
                }
            }
        }
    }

    // Modal bottom sheet for single app actions
    if (selectedAppForSheet != null) {
        val sheetApp = selectedAppForSheet!!
        ModalBottomSheet(
            onDismissRequest = { selectedAppForSheet = null },
            sheetState = sheetState,
        ) {
            ListItem(
                headlineContent = { Text("Autostart deaktivieren") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = BrandRed,
                    )
                },
                modifier = Modifier.clickable {
                    onStartSingle(sheetApp.packageName)
                    selectedAppForSheet = null
                },
            )
            ListItem(
                headlineContent = { Text("App-Info oeffnen") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", sheetApp.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    selectedAppForSheet = null
                },
            )
            ListItem(
                headlineContent = { Text("Abbrechen") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { selectedAppForSheet = null },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status filter chip row
// ---------------------------------------------------------------------------

@Composable
private fun StatusFilterChipRow(
    current: StatusFilter,
    onSelect: (StatusFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = current == StatusFilter.ALL,
                onClick = { onSelect(StatusFilter.ALL) },
                label = { Text("Alle") },
            )
        }
        item {
            FilterChip(
                selected = current == StatusFilter.NOT_YET_DISABLED,
                onClick = { onSelect(StatusFilter.NOT_YET_DISABLED) },
                label = { Text("Noch nicht deaktiviert") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutostartEnabledContainer,
                    selectedLabelColor = AutostartEnabledContent,
                ),
            )
        }
        item {
            FilterChip(
                selected = current == StatusFilter.DISABLED_ONLY,
                onClick = { onSelect(StatusFilter.DISABLED_ONLY) },
                label = { Text("Deaktiviert") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutostartDisabledContainer,
                    selectedLabelColor = AutostartDisabledContent,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// App row
// ---------------------------------------------------------------------------

@Composable
private fun AppRow(
    app: AppItem,
    isSelected: Boolean,
    onCheckboxToggle: () -> Unit,
    onRowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onCheckboxToggle() },
        )

        Spacer(Modifier.width(4.dp))

        AppIconImage(packageName = app.packageName)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        AutostartStatusChip(status = app.autostartStatus)
    }
}

// ---------------------------------------------------------------------------
// App icon — loads from PackageManager, falls back to Icons.Default.Android
// ---------------------------------------------------------------------------

@Composable
private fun AppIconImage(packageName: String) {
    val pm = LocalContext.current.packageManager
    val bitmap: ImageBitmap? = remember(packageName) {
        runCatching { pm.getApplicationIcon(packageName) }
            .getOrNull()
            ?.toImageBitmap()
    }

    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Autostart status chip
// ---------------------------------------------------------------------------

@Composable
private fun AutostartStatusChip(status: AutostartStatus) {
    when (status) {
        AutostartStatus.ENABLED -> SuggestionChip(
            onClick = {},
            label = { Text("Autostart AN", style = MaterialTheme.typography.labelSmall) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Autostart aktiv",
                    modifier = Modifier.size(14.dp),
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = AutostartEnabledContainer,
                labelColor = AutostartEnabledContent,
                iconContentColor = AutostartEnabledContent,
            ),
        )

        AutostartStatus.DISABLED -> SuggestionChip(
            onClick = {},
            label = { Text("Autostart AUS", style = MaterialTheme.typography.labelSmall) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Autostart deaktiviert",
                    modifier = Modifier.size(14.dp),
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = AutostartDisabledContainer,
                labelColor = AutostartDisabledContent,
                iconContentColor = AutostartDisabledContent,
            ),
        )

        // UNKNOWN: no chip — most apps start as unknown, showing a badge for all is noise.
        // A chip only appears once the status has been confirmed by automation.
        AutostartStatus.UNKNOWN -> Unit
    }
}

// ---------------------------------------------------------------------------
// Drawable -> ImageBitmap conversion (no Coil — pure Android API)
// ---------------------------------------------------------------------------

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = when (this) {
        is BitmapDrawable -> this.bitmap
        is AdaptiveIconDrawable -> {
            val bmp = Bitmap.createBitmap(
                intrinsicWidth.coerceAtLeast(1),
                intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
            bmp
        }
        else -> {
            val bmp = Bitmap.createBitmap(
                intrinsicWidth.coerceAtLeast(1),
                intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
            bmp
        }
    }
    return bitmap.asImageBitmap()
}
