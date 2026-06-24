// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.service.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.labushuya.hushd.core.automation.BulkAutostopEngine.State
import kotlinx.coroutines.delay

/** Brand accent red — matches HushdTheme. */
private val BrandRed = Color(0xFFEF4444)

/** Dark card background — matches HushdTheme dark surface. */
private val DarkSurface = Color(0xFF0F172A)

/** Auto-dismiss delay after State.Done */
private const val DONE_DISMISS_MS = 3_000L

/**
 * Floating overlay card that reflects [BulkAutostopEngine.State] in real-time.
 *
 * Shows nothing (empty [Box]) when state is [State.Idle] or [State.PermissionCheck].
 * Auto-dismisses after [DONE_DISMISS_MS] when state reaches [State.Done] by calling [onCancel].
 */
@Composable
fun OverlayContent(
    state: State,
    onCancel: () -> Unit,
) {
    val visible = state !is State.Idle && state !is State.PermissionCheck
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Card(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 280.dp)
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
        ) {
            when (state) {
                is State.Iterating,
                is State.OpeningSettings,
                is State.AwaitingToggle,
                is State.Clicking,
                is State.Verifying,
                is State.Cooldown -> RunningContent(state = state, onCancel = onCancel)

                is State.Done -> DoneContent(state = state, onDismiss = onCancel)

                is State.Error -> ErrorContent(state = state, onClose = onCancel)

                // Idle / PermissionCheck handled by AnimatedVisibility above
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Running states: Iterating / OpeningSettings / AwaitingToggle / Clicking /
//                 Verifying / Cooldown
// ---------------------------------------------------------------------------

@Composable
private fun RunningContent(state: State, onCancel: () -> Unit) {
    val (idx, total, currentPkg) = when (state) {
        is State.Iterating -> Triple(state.idx, state.total, state.currentPkg)
        is State.OpeningSettings -> Triple(null, null, state.pkg)
        is State.AwaitingToggle -> Triple(null, null, state.pkg)
        is State.Clicking -> Triple(null, null, state.pkg)
        is State.Verifying -> Triple(null, null, state.pkg)
        is State.Cooldown -> Triple(null, null, state.pkg)
        else -> Triple(null, null, "")
    }
    val progress: Float? = when (state) {
        is State.Iterating -> if (state.total > 0) state.idx.toFloat() / state.total else null
        else -> null
    }
    val phaseLabel = when (state) {
        is State.OpeningSettings -> "Öffne Einstellungen…"
        is State.AwaitingToggle -> "Warte auf Screen…"
        is State.Clicking -> "Klicke Toggle…"
        is State.Verifying -> "Prüfe Ergebnis…"
        is State.Cooldown -> "Pause…"
        else -> null
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (idx != null && total != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "App ${idx + 1} / $total",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                if (phaseLabel != null) {
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        } else if (phaseLabel != null) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
        }

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = BrandRed,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(6.dp))
        } else {
            // Indeterminate for non-Iterating running states
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = BrandRed,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(6.dp))
        }

        if (currentPkg.isNotEmpty()) {
            Text(
                text = currentPkg,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) {
                Text("Abbrechen", color = BrandRed)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Done state
// ---------------------------------------------------------------------------

@Composable
private fun DoneContent(state: State.Done, onDismiss: () -> Unit) {
    // Auto-dismiss after 3 seconds
    LaunchedEffect(Unit) {
        delay(DONE_DISMISS_MS)
        onDismiss()
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF22C55E), // green-500
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Fertig: ${state.ok} Apps",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        if (state.failed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⚠ ${state.failed.size} Fehler",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFBBF24), // amber-400
                )
            }
            Spacer(Modifier.height(4.dp))
            Column {
                state.failed.take(4).forEach { (pkg, _) ->
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.failed.size > 4) {
                    Text(
                        text = "…und ${state.failed.size - 4} weitere",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            }
            // Manual dismiss for error list — also useful when auto-dismiss fires too quickly
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Schließen", color = BrandRed)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun ErrorContent(state: State.Error, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = BrandRed,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Fehler",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.cause.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.pkg != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.pkg,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) {
                Text("Schließen", color = BrandRed)
            }
        }
    }
}
