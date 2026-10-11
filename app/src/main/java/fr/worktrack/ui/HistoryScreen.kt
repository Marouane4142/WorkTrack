package fr.worktrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(state: WorkTrackState, initialPeriod: String, onDay: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var period by rememberSaveable { mutableStateOf(initialPeriod) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    var query by rememberSaveable { mutableStateOf("") }
    var calendar by rememberSaveable { mutableStateOf(true) }
    var customFrom by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var customThrough by rememberSaveable { mutableStateOf(today.toString()) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var showTypes by remember { mutableStateOf(false) }
    val from = when (period) { "Mois" -> month.atDay(1); "Semaine" -> weekStart(today, state.settings.preferences.weekStartsOnMonday); "Période" -> LocalDate.parse(customFrom); else -> LocalDate.MIN }
    val through = when (period) { "Mois" -> month.atEndOfMonth(); "Semaine" -> from.plusDays(6); "Période" -> LocalDate.parse(customThrough); else -> LocalDate.MAX }
    val filtered = remember(state.entries, query, from, through, type) { state.entries.filter { e ->
        val inPeriod = (from == LocalDate.MIN || e.endTime > from.atStartOfDay()) && (through == LocalDate.MAX || e.startTime < through.plusDays(1).atStartOfDay())
        inPeriod && (type == null || e.type.name == type || (type == ActivityType.TRAVEL.name && e.type.isTravel) || (type == ActivityType.LUNCH_BREAK.name && e.type.isBreak)) && listOf(e.client, e.location, e.caseNumber, e.caseName, e.notes, e.origin, e.destination, e.type.displayLabel)
            .any { it.contains(query.trim(), ignoreCase = true) }
    } }
    val summaries = remember(filtered, from, through) { WorkCalculator.summaries(filtered).filter { it.date in from..through }.sortedByDescending { it.date } }
    val format = state.settings.preferences.durationFormat
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Votre historique", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Retrouvez chaque journée, chaque affaire.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Client, site, n° d'affaire…") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Effacer la recherche") } }, shape = RoundedCornerShape(18.dp))
        }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Mois", "Semaine", "Tout", "Période").forEach { label -> FilterChip(period == label, { period = label }, label = { Text(label) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f)) {
                    AssistChip({ showTypes = true }, label = { Text(type?.let { ActivityType.valueOf(it).displayLabel } ?: "Toutes les activités", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(17.dp)) })
                    DropdownMenu(showTypes, { showTypes = false }) {
                        DropdownMenuItem(text = { Text("Toutes les activités") }, onClick = { type = null; showTypes = false })
                        ActivityType.selectable.forEach { t -> DropdownMenuItem(text = { Text(t.displayLabel) }, onClick = { type = t.name; showTypes = false }) }
                    }
                }
                IconButton({ calendar = !calendar }) { Icon(if (calendar) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.CalendarMonth, if (calendar) "Vue liste" else "Vue calendrier") }
            }
        }
        if (period == "Période") item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ chooseDate(context, LocalDate.parse(customFrom)) { customFrom = it.toString(); if (it > LocalDate.parse(customThrough)) customThrough = it.toString() } }, Modifier.weight(1f)) { Text(LocalDate.parse(customFrom).format(ShortDate)) }
                OutlinedButton({ chooseDate(context, LocalDate.parse(customThrough)) { customThrough = it.toString(); if (it < LocalDate.parse(customFrom)) customFrom = it.toString() } }, Modifier.weight(1f)) { Text(LocalDate.parse(customThrough).format(ShortDate)) }
            }
        }
        if (period == "Mois") item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ monthText = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "Mois précédent") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", French)).replaceFirstChar { it.titlecase(French) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton({ monthText = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "Mois suivant") }
            }
        }
        if (calendar && period == "Mois") item { MonthCalendar(month, summaries.map { it.date }.toSet(), state.settings.preferences.weekStartsOnMonday, onDay) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Effectives · période", summaries.sumOf { it.effectiveMinutes }, Icons.Outlined.Schedule, Modifier.weight(1f), format)
                StatTile("Non effectives · période", summaries.sumOf { it.nonEffectiveMinutes }, Icons.Outlined.DirectionsCar, Modifier.weight(1f), format, true)
            }
        }
        item { SectionTitle("${summaries.size} journée${if (summaries.size > 1) "s" else ""} · ${summaries.sumOf { it.mealCount }} repas") }
        if (summaries.isEmpty()) item { EmptyState("Aucune journée sur cette période", "Changez la période ou les filtres pour retrouver vos activités.", Icons.Outlined.CalendarMonth) }
        items(summaries, key = { it.date.toString() }) { summary ->
            Card({ onDay(summary.date) }, Modifier.fillMaxWidth(), shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(summary.date.dayOfMonth.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(summary.date.format(DateTimeFormatter.ofPattern("EEE", French)).uppercase(French), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${duration(summary.effectiveMinutes, format)} · ${duration(summary.nonEffectiveMinutes, format)}", style = MaterialTheme.typography.titleSmall)
                        Text("${summary.entryCount} activités · ${summary.mealCount} repas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Consulter la journée", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(month: YearMonth, worked: Set<LocalDate>, monday: Boolean, onDay: (LocalDate) -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val names = if (monday) listOf("L", "M", "M", "J", "V", "S", "D") else listOf("D", "L", "M", "M", "J", "V", "S")
            Row { names.forEach { Text(it, Modifier.weight(1f).padding(vertical = 8.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val offset = if (monday) month.atDay(1).dayOfWeek.value - 1 else month.atDay(1).dayOfWeek.value % 7
            val rows = (offset + month.lengthOfMonth() + 6) / 7
            repeat(rows) { row ->
                Row {
                    repeat(7) { column ->
                        val day = row * 7 + column - offset + 1
                        if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(44.dp))
                        else {
                            val date = month.atDay(day); val active = date in worked; val today = date == LocalDate.now()
                            Column(Modifier.weight(1f).height(44.dp).background(if (today) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable { onDay(date) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(day.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (active || today) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(3.dp)); Box(Modifier.size(4.dp).background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}
