// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dagger.hilt.android.AndroidEntryPoint
import dev.labushuya.hushd.R
import dev.labushuya.hushd.feature.applist.AppListScreen
import dev.labushuya.hushd.service.overlay.OverlayService
import timber.log.Timber
import javax.inject.Inject
import dev.labushuya.hushd.core.automation.BulkAutostopEngine

private const val PREFS_NAME = "hushd_prefs"
private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var engine: BulkAutostopEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HushdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HushdNavHost(engine = engine)
                }
            }
        }
    }
}

@Composable
private fun HushdNavHost(engine: BulkAutostopEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var disclaimerAccepted by remember {
        mutableStateOf(prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false))
    }
    var a11yGranted by remember { mutableStateOf(PermissionHelper.isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(PermissionHelper.isOverlayPermissionGranted(context)) }

    // Re-check permissions every time the app resumes (user may have just granted them in Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yGranted = PermissionHelper.isAccessibilityServiceEnabled(context)
                overlayGranted = PermissionHelper.isOverlayPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        !disclaimerAccepted -> {
            DisclaimerScreen(
                onAccepted = {
                    prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
                    disclaimerAccepted = true
                }
            )
        }

        !a11yGranted || !overlayGranted -> {
            PermissionsScreen(
                a11yGranted = a11yGranted,
                overlayGranted = overlayGranted,
            )
        }

        else -> {
            AppListScreen(
                onStartBulk = { packages -> startAutomation(context, engine, packages) },
                onStartSingle = { pkg -> startAutomation(context, engine, listOf(pkg)) },
            )
        }
    }
}

/**
 * Starts the overlay service and triggers the engine for the given packages.
 * Pre-conditions (permissions) must already be verified by the caller.
 */
private fun startAutomation(
    context: Context,
    engine: BulkAutostopEngine,
    packages: List<String>,
) {
    if (packages.isEmpty()) return
    Timber.tag("MainActivity").i("startAutomation: ${packages.size} packages")
    OverlayService.start(context)
    engine.runFor(packages)
}

// ---------------------------------------------------------------------------
// Disclaimer screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisclaimerScreen(onAccepted: () -> Unit) {
    var checked by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.disclaimer_title)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.disclaimer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
                Text(
                    text = stringResource(R.string.disclaimer_accept_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .weight(1f),
                )
            }

            Button(
                onClick = onAccepted,
                enabled = checked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Weiter")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
