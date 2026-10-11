package fr.worktrack.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import fr.worktrack.domain.*
import fr.worktrack.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

@Composable
fun DashboardScreen(state: WorkTrackState, onAdd: () -> Unit, onDay: (LocalDate) -> Unit, onWeek: () -> Unit, onExports: () -> Unit) {
    val today = LocalDate.now()
    val first = weekStart(today, state.settings.preferences.weekStartsOnMonday)
    val days = (0L..6L).map { state.summaries[first.plusDays(it)] ?: DaySummary(first.plusDays(it)) }
    val currentDay = state.summaries[today] ?: DaySummary(today)
    val month = state.summaries.values.filter { it.date.year == today.year && it.date.month == today.month }
    val format = state.settings.preferences.durationFormat
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", French)).replaceFirstChar { it.titlecase(French) },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (state.settings.profile.firstName.isBlank()) "Votre temps, au clair." else "Bonjour ${state.settings.profile.firstName}.",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Navy, Color(0xFF254864)))).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Cette semaine", Color(0xFFB3C9DB))
                    Surface(onClick = onWeek, shape = CircleShape, color = Color.White.copy(alpha = .09f)) {
                        Text("S${today.get(WeekFields.ISO.weekOfWeekBasedYear())} · ${first.dayOfMonth}–${first.plusDays(6).dayOfMonth} ${first.plusDays(6).format(DateTimeFormatter.ofPattern("MMM", French))}",
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFCCE2F1), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(duration(days.sumOf { it.effectiveMinutes }, format), style = MaterialTheme.typography.displaySmall,
                            color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                        Text("Heures effectives", color = Color(0xFFB3C9DB), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(duration(days.sumOf { it.nonEffectiveMinutes }, format), style = MaterialTheme.typography.titleLarge, color = Mint, fontWeight = FontWeight.SemiBold)
                        Text("Non effectives", color = Color(0xFFB3C9DB), style = MaterialTheme.typography.bodySmall)
                    }
                }
                WeekBars(days, today, onDay)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Legend("Effectif", Mint); Legend("Non effectif", MutedBlue)
                    }
                    IconButton(onWeek, Modifier.size(36.dp).background(Color.White.copy(alpha = .08f), CircleShape)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Voir ma semaine", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        item { SectionTitle("Aujourd'hui", "Compléter", { onDay(today) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Heures effectives", currentDay.effectiveMinutes, Icons.Outlined.Schedule, Modifier.weight(1f), format)
                StatTile("Heures non effectives", currentDay.nonEffectiveMinutes, Icons.Outlined.DirectionsCar, Modifier.weight(1f), format, true)
            }
        }
        item {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.DateRange, null, Modifier.size(24.dp), MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ce mois-ci", style = MaterialTheme.typography.titleSmall)
                        Text("${duration(month.sumOf { it.countedMinutes }, format)} comptabilisées", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${month.sumOf { it.mealCount }}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("repas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionTitle("Dernières activités", "Relevé", onExports) }
        if (state.entries.isEmpty()) item {
            EmptyState("Une première journée à suivre", "Interventions, trajets, pauses : chaque plage compte dans votre historique.", actionLabel = "Ajouter une activité", action = onAdd)
        } else items(state.entries.sortedByDescending { it.start }.take(4), key = { it.id }) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.startTime.toLocalDate().format(ShortDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActivityCard(entry, format) { onDay(entry.startTime.toLocalDate()) }
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape)); Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB3C9DB))
    }
}

@Composable
private fun WeekBars(days: List<DaySummary>, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val max = maxOf(480, days.maxOfOrNull { it.countedMinutes } ?: 0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        days.forEach { day ->
            val effective by animateFloatAsState(day.effectiveMinutes / max.toFloat(), label = "Heures effectives")
            val nonEffective by animateFloatAsState(day.nonEffectiveMinutes / max.toFloat(), label = "Heures non effectives")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onDay(day.date) }
                .semantics { contentDescription = "${day.date.format(LongDate)} : ${duration(day.effectiveMinutes)} effectives, ${duration(day.nonEffectiveMinutes)} non effectives" }
                .padding(top = 4.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.height(68.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(14.dp).fillMaxHeight().background(Color.White.copy(alpha = .04f), CircleShape))
                    Column(Modifier.width(14.dp).clip(RoundedCornerShape(8.dp))) {
                        if (nonEffective > 0) Box(Modifier.fillMaxWidth().height((68 * nonEffective).dp).background(MutedBlue))
                        Box(Modifier.fillMaxWidth().height(maxOf(4f, 68 * effective).dp).background(if (day.effectiveMinutes > 0) Mint else Color.White.copy(alpha = .18f)))
                    }
                }
                Text(day.date.format(DateTimeFormatter.ofPattern("EEEEE", French)).uppercase(French),
                    style = MaterialTheme.typography.labelSmall, color = if (day.date == today) Mint else Color(0xFFB3C9DB), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 940)
@Composable private fun HomePreview() { WorkTrackTheme { DashboardScreen(WorkTrackState(loading = false), {}, {}, {}, {}) } }
