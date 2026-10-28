package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.worktrack.export.PendingDocument

@Composable
fun SavedDocumentDialog(document: PendingDocument, onOpen: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, icon = { Icon(Icons.Outlined.CheckCircle, null) },
        title = { Text("Fichier enregistré") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(document.name)
                FilledTonalButton(onOpen, Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir")
                }
            }
        },
        confirmButton = { TextButton(onShare) { Text("Partager") } },
        dismissButton = { TextButton(onClose) { Text("Fermer") } })
}
