// service-overlay/src/main/kotlin/de/delos/autostartmgr/service/overlay/ui/OverlayContent.kt
package dev.labushuya.hushd.service.overlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.labushuya.hushd.core.automation.BulkAutostopEngine.State

@Composable
fun OverlayContent(state: State, onCancel: () -> Unit) {
    Card(modifier = Modifier.padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            val (text, progress) = when (state) {
                is State.Idle -> "Bereit" to null
                is State.PermissionCheck -> "Prüfe Berechtigungen…" to null
                is State.Iterating -> "${state.idx + 1}/${state.total}" to (state.idx.toFloat() / state.total)
                is State.OpeningSettings -> "Öffne Einstellungen…" to null
                is State.AwaitingToggle -> "Warte auf Toggle…" to null
                is State.Clicking -> "Klicke…" to null
                is State.Verifying -> "Prüfe Ergebnis…" to null
                is State.Cooldown -> "Pause…" to null
                is State.Done -> "Fertig (${state.ok} OK / ${state.failed.size} Fehler)" to 1f
                is State.Error -> "Fehler: ${state.cause}" to null
            }
            Text(text = text)
            progress?.let { LinearProgressIndicator(progress = { it }) }
            Row { TextButton(onClick = onCancel) { Text("Abbrechen") } }
        }
    }
}
