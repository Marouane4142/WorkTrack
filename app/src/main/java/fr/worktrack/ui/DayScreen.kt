package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import java.time.LocalDate

@Composable
fun DayScreen(date: LocalDate, state: WorkTrackState, vm: WorkTrackViewModel,
    onAdd: () -> Unit, onEdit: (WorkEntry) -> Unit, onDuplicate: (WorkEntry) -> Unit, onExport: () -> Unit) {
    val entries = state.entries.filter { WorkCalculator.touches(it, date) }.sortedBy { it.start }
    val summary = state.summaries[date] ?: DaySummary(date)
    val format = state.settings.preferences.durationFormat
    var selected by remember { mutableStateOf<WorkEntry?>(null) }
    var deleting by remember { mutableStateOf<WorkEntry?>(null) }
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Eyebrow("${summary.entryCount} activités · ${summary.mealCount} repas")
            Text(date.format(LongDate).replaceFirstChar { it.titlecase(French) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Heures effectives", summary.effectiveMinutes, Icons.Outlined.Schedule, Modifier.weight(1f), format)
                StatTile("Heures non effectives", summary.nonEffectiveMinutes, Icons.Outlined.DirectionsCar, Modifier.weight(1f), format, true)
            }
        }
        item {
            Button(onAdd, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une plage horaire") }
        }
        if (WorkCalculator.hasOverlap(entries)) item { InfoCard("Chevauchements présents", "Certaines plages se superposent. Vérifiez les horaires pour éviter de compter deux fois la même durée.", Icons.Outlined.WarningAmber) }
        item { SectionTitle("Le fil de votre journée") }
        if (entries.isEmpty()) item { EmptyState("Cette journée est encore vide", "Ajoutez une intervention, un trajet ou une pause.", actionLabel = "Ajouter une activité", action = onAdd) }
        items(entries, key = { it.id }) { entry -> ActivityCard(entry, format) { selected = entry } }
        if (entries.isNotEmpty()) {
            item {
                Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Décompte détaillé", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        DetailLine("Effectives", "${duration(summary.effectiveMinutes)} · ${decimalHours(summary.effectiveMinutes)} h", true)
                        DetailLine("Non effectives", "${duration(summary.nonEffectiveMinutes)} · ${decimalHours(summary.nonEffectiveMinutes)} h", true)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        DetailLine("Pauses", duration(summary.breakMinutes))
                        DetailLine("Trajets et activités exclus", duration(summary.uncountedMinutes))
                        DetailLine("Franchises au retour", duration(summary.deductedMinutes))
                        if (summary.roundingMinutes > 0) DetailLine("Arrondi explicite déduit", duration(summary.roundingMinutes))
                        DetailLine("Effectives de nuit", duration(summary.nightEffectiveMinutes))
                        DetailLine("Non effectives de nuit", duration(summary.nightNonEffectiveMinutes))
                        DetailLine("Repas", summary.mealCount.toString())
                        val numbers = entries.map { it.caseNumber }.filter { it.isNotBlank() }.distinct()
                        if (numbers.isNotEmpty()) Text("Affaires : ${numbers.joinToString(" · ")}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onExport, Modifier.weight(1f), enabled = !state.busy) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Relevé") }
                    OutlinedButton({ chooseDate(context, date.plusDays(1)) { vm.duplicateDay(date, it) } }, Modifier.weight(1f), enabled = !state.busy) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copier le jour")
                    }
                }
            }
        }
    }
    selected?.let { entry -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.title) },
        text = { Column {
            DetailLine("Horaires", "${entry.startTime.format(ClockFormat)} → ${entry.endTime.format(ClockFormat)}")
            DetailLine("Durée brute", duration(entry.rawMinutes))
            DetailLine("Effectif", duration(entry.effectiveMinutes))
            DetailLine("Non effectif", duration(entry.nonEffectiveMinutes))
            DetailLine("Franchise appliquée", "${entry.deductedMinutes} min")
            DetailLine("Temps exclu", duration(entry.uncountedMinutes + entry.roundingMinutes + if (entry.type.isBreak) entry.rawMinutes else 0))
            if (entry.caseNumber.isNotBlank()) DetailLine("N° affaire", entry.caseNumber)
            if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
            if (entry.overrideReason.isNotBlank()) Text("Correction : ${entry.overrideReason}", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ selected = null; onEdit(entry) }) { Text("Modifier") } },
        dismissButton = { Row {
            TextButton({ selected = null; onDuplicate(entry) }) { Text("Dupliquer") }
            TextButton({ selected = null; deleting = entry }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
        } }) }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer cette activité ?") },
        text = { Text("${entry.type.displayLabel} · ${entry.startTime.format(ClockFormat)}–${entry.endTime.format(ClockFormat)}. Les totaux de la journée seront mis à jour.") },
        confirmButton = { TextButton({ deleting = null; vm.delete(entry) }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Annuler") } }) }
}
