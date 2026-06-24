// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.feature.applist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image

// Brand accent matches HushdTheme: Color(0xFFEF4444)
private val BrandRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel = hiltViewModel(),
    onStartBulk: (packages: List<String>) -> Unit,
    onStartSingle: (packageName: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-derive visible list in UI (visible() is private in VM)
    val visibleApps = remember(state.apps, state.filter, state.query) {
        state.apps
            .asSequence()
            .filter { app ->
                when (state.filter) {
                    AppFilter.ALL -> true
                    AppFilter.USER_ONLY -> !app.isSystemApp
                    AppFilter.SYSTEM_ONLY -> app.isSystemApp
                }
            }
            .filter { app ->
                if (state.query.isBlank()) true
                else app.label.contains(state.query, ignoreCase = true) ||
                    app.packageName.contains(state.query, ignoreCase = true)
            }
            .toList()
    }

    var searchVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

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
                        placeholder = { Text("Apps suchen…") },
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
                FilterChipRow(
                    current = state.filter,
                    onSelect = { viewModel.setFilter(it) },
                )
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
                    TextButton(onClick = { viewModel.selectAllVisible() }) {
                        Text("Alle waehlen")
                    }
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text("Abwaehlen")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onStartBulk(state.selection.toList()) },
                        enabled = hasSelection,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandRed,
                        ),
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
                            onToggleSelection = { viewModel.toggleSelection(app.packageName) },
                            onLongPress = { onStartSingle(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    current: AppFilter,
    onSelect: (AppFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = current == AppFilter.ALL,
                onClick = { onSelect(AppFilter.ALL) },
                label = { Text("Alle") },
            )
        }
        item {
            FilterChip(
                selected = current == AppFilter.USER_ONLY,
                onClick = { onSelect(AppFilter.USER_ONLY) },
                label = { Text("Nur User-Apps") },
            )
        }
        item {
            FilterChip(
                selected = current == AppFilter.SYSTEM_ONLY,
                onClick = { onSelect(AppFilter.SYSTEM_ONLY) },
                label = { Text("System-Apps") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggleSelection,
                onLongClick = onLongPress,
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSystemApp) {
                    Spacer(Modifier.width(6.dp))
                    SystemBadge()
                }
            }
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                fontSize = 10.sp,
            )
            if (app.versionName != null) {
                Text(
                    text = "v${app.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                )
            }
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelection() },
        )
    }
}

@Composable
private fun AppIcon(packageName: String) {
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
        // Fallback: colored circle with first letter of package name
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = packageName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SystemBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = "SYS",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 9.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Drawable -> ImageBitmap conversion (no Coil in catalog — pure Android API)
// ---------------------------------------------------------------------------

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = when (this) {
        is BitmapDrawable -> this.bitmap
        is AdaptiveIconDrawable -> {
            // AdaptiveIconDrawable requires explicit rendering onto a canvas
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
