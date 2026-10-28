package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import fr.worktrack.export.PointageFormat
import fr.worktrack.export.PointageWeek
import java.time.LocalDate

@Composable
fun ExportsScreen(state: WorkTrackState, vm: WorkTrackViewModel, initialFrom: String, initialThrough: String) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var dateText by rememberSaveable { mutableStateOf(initialFrom.ifBlank { initialThrough.ifBlank { today.toString() } }) }
    var formatName by rememberSaveable { mutableStateOf(PointageFormat.XLSX.name) }
    val format = PointageFormat.entries.firstOrNull { it.name == formatName } ?: PointageFormat.XLSX
    val week = PointageWeek.containing(LocalDate.parse(dateText))
    val summaries = remember(state.entries, week) { week.period.summaries(state.entries) }
    val count = remember(state.entries, week) { week.period.entries(state.entries).size }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Vos heures, à partager.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Votre feuille de pointage, remplie pour la semaine.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("1 · La semaine")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(false, { dateText = today.toString() }, label = { Text("Cette semaine") })
                        FilterChip(false, { dateText = week.monday.minusWeeks(1).toString() }, label = { Text("Semaine précédente") })
                    }
                    OutlinedButton({ chooseDate(context, week.monday) { dateText = it.toString() } }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.DateRange, null); Spacer(Modifier.width(8.dp))
                        Text("S${week.number} · ${week.monday.format(ShortDate)} au ${week.friday.format(ShortDate)}")
                    }
                    Text("Lundi → vendredi · 5 jours dans le même relevé", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("2 · Le format")
                    ExportOption(PointageFormat.XLSX, "Feuille de pointage Excel", "Votre modèle · 5 onglets · XLSX", Icons.Outlined.TableChart, format) { formatName = it.name }
                    ExportOption(PointageFormat.PDF, "Feuille de pointage PDF", "La même feuille · 5 pages · Une page par jour", Icons.Outlined.PictureAsPdf, format) { formatName = it.name }
                }
            }
        }
        item {
            val activityLabel = if (count == 1) "activité" else "activités"
            val dayLabel = if (summaries.size == 1) "jour" else "jours"
            InfoCard("Aperçu de la semaine", "$count $activityLabel · ${summaries.size} $dayLabel\n${duration(summaries.sumOf { it.effectiveMinutes })} effectives · ${duration(summaries.sumOf { it.nonEffectiveMinutes })} non effectives\n${summaries.sumOf { it.mealCount }} repas", Icons.AutoMirrored.Outlined.FactCheck)
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("Les cinq feuilles")
                    week.dates.forEachIndexed { index, date ->
                        val summary = summaries.firstOrNull { it.date == date }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${PointageWeek.sheetNames[index]} ${date.format(ShortDate)}", style = MaterialTheme.typography.bodySmall)
                            Text("${duration(summary?.effectiveMinutes ?: 0)} / ${duration(summary?.nonEffectiveMinutes ?: 0)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Effectives / non effectives · Les jours sans activité gardent leur feuille datée.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.entries.any { WorkCalculator.touches(it, week.monday.plusDays(5)) || WorkCalculator.touches(it, week.monday.plusDays(6)) }) item {
            InfoCard("Activités du week-end", "Le modèle s'arrête au vendredi. Les activités du samedi et du dimanche restent dans l'historique et ne sont pas déplacées sur les jours ouvrés.", Icons.Outlined.Info)
        }
        item {
            Button({ vm.export(format, week) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = count > 0 && !state.busy) {
                Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Enregistrer la semaine en ${if (format == PointageFormat.XLSX) "Excel" else "PDF"}")
            }
        }
        item {
            OutlinedButton({ vm.export(format, week, share = true) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = count > 0 && !state.busy) {
                Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Partager directement")
            }
        }
        if (count == 0) item { Text("Cette semaine ne contient aucune activité. Choisissez une autre semaine ou complétez votre historique.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Votre modèle, son logo et votre signature, en Excel ou en PDF. Les heures non effectives sont déjà diminuées des franchises applicables ; les primes ne sont pas renseignées.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExportOption(value: PointageFormat, title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                         selected: PointageFormat, onSelect: (PointageFormat) -> Unit) {
    Surface(onClick = { onSelect(value) }, shape = RoundedCornerShape(16.dp), color = if (selected == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            RadioButton(selected == value, null)
        }
    }
}
