// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shown when one or more required permissions are not yet granted.
 * Displays a card per missing permission with a direct deep-link button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    a11yGranted: Boolean,
    overlayGranted: Boolean,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Berechtigungen") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Hushd benötigt die folgenden Berechtigungen, bevor die Automatisierung gestartet werden kann.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "Barrierefreiheitsdienst",
                description = "Ermöglicht Hushd, UI-Steuerelemente im HONOR Phone Manager automatisch zu bedienen.",
                isGranted = a11yGranted,
                onActivate = { PermissionHelper.openAccessibilitySettings(context) },
            )

            PermissionCard(
                icon = Icons.Default.LayersClear,
                title = "Overlay-Berechtigung",
                description = "Zeigt den Fortschrittsbalken über dem Phone Manager an, während die Automatisierung läuft.",
                isGranted = overlayGranted,
                onActivate = { PermissionHelper.openOverlaySettings(context) },
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onActivate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isGranted)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isGranted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Erteilt",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Fehlend",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isGranted) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onActivate) {
                        Text("Aktivieren")
                    }
                }
            }
        }
    }
}
