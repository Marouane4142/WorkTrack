package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import fr.worktrack.export.ReportPeriod
import java.time.LocalDate

@Composable
fun ExportsScreen(state: WorkTrackState, vm: WorkTrackViewModel, initialFrom: String, initialThrough: String) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var fromText by rememberSaveable { mutableStateOf(initialFrom.ifBlank { weekStart(today, state.settings.preferences.weekStartsOnMonday).toString() }) }
    var throughText by rememberSaveable { mutableStateOf(initialThrough.ifBlank { LocalDate.parse(fromText).plusDays(6).toString() }) }
    var selectedFormat by rememberSaveable { mutableStateOf("PDF") }
    val from = LocalDate.parse(fromText); val through = LocalDate.parse(throughText)
    val period = ReportPeriod(from, through)
    val summaries = remember(state.entries, period) { period.summaries(state.entries) }
    val count = period.entries(state.entries).size
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Vos heures, à partager.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Un relevé propre, prêt à être vérifié.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("1 · La période")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(false, { val start = weekStart(today, state.settings.preferences.weekStartsOnMonday); fromText = start.toString(); throughText = start.plusDays(6).toString() }, label = { Text("Cette semaine") })
                        FilterChip(false, { fromText = today.withDayOfMonth(1).toString(); throughText = today.withDayOfMonth(1).plusMonths(1).minusDays(1).toString() }, label = { Text("Ce mois") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ chooseDate(context, from) { fromText = it.toString(); if (it > through) throughText = it.toString() } }, Modifier.weight(1f)) { Text(from.format(ShortDate)) }
                        OutlinedButton({ chooseDate(context, through) { throughText = it.toString(); if (it < from) fromText = it.toString() } }, Modifier.weight(1f)) { Text(through.format(ShortDate)) }
                    }
                }
            }
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("2 · Le format")
                    ExportOption("PDF", "Relevé PDF", "Tableau, logo et signature · A4 paysage", Icons.Outlined.PictureAsPdf, selectedFormat) { selectedFormat = it }
                    ExportOption("Activites", "CSV des activités", "Horaires, trajets et affaires · Excel", Icons.Outlined.TableChart, selectedFormat) { selectedFormat = it }
                    ExportOption("Recapitulatif", "CSV journalier", "Totaux par jour, pauses et repas", Icons.AutoMirrored.Outlined.ViewList, selectedFormat) { selectedFormat = it }
                }
            }
        }
        item {
            InfoCard("Aperçu de la période", "$count activités · ${summaries.size} jours\n${duration(summaries.sumOf { it.effectiveMinutes })} effectives · ${duration(summaries.sumOf { it.nonEffectiveMinutes })} non effectives\n${summaries.sumOf { it.mealCount }} repas", Icons.AutoMirrored.Outlined.FactCheck)
        }
        item {
            Button({ vm.export(selectedFormat, period) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = count > 0 && !state.busy) {
                Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Enregistrer le relevé")
            }
        }
        item {
            OutlinedButton({ vm.export(selectedFormat, period, share = true) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = count > 0 && !state.busy) {
                Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Partager directement")
            }
        }
        if (count == 0) item { Text("Cette période ne contient aucune activité. Choisissez d'autres dates ou complétez votre historique.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Les CSV utilisent les heures décimales, un séparateur point-virgule et l'encodage UTF-8 pour Excel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExportOption(value: String, title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: String, onSelect: (String) -> Unit) {
    Surface(onClick = { onSelect(value) }, shape = RoundedCornerShape(16.dp), color = if (selected == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected == value, null)
        }
    }
}
