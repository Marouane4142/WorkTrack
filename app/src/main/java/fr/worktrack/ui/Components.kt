package fr.worktrack.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.worktrack.domain.*
import fr.worktrack.ui.theme.*
import java.time.LocalDate

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (action != null) TextButton(onAction) { Text(action); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 4.dp).size(16.dp)) }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(French), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
fun StatTile(label: String, minutes: Int, icon: ImageVector, modifier: Modifier = Modifier,
    format: DurationFormat = DurationFormat.HOURS_MINUTES, secondary: Boolean = false) {
    val accent = if (secondary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Card(modifier, shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(22.dp), accent)
            Text(duration(minutes, format), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoCard(title: String, body: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: ImageVector = Icons.Outlined.Schedule,
    actionLabel: String? = null, action: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null) FilledTonalButton(onClick = action) { Text(actionLabel) }
    }
}

fun activityIcon(type: ActivityType): ImageVector = when {
    type.isTravel -> Icons.Outlined.DirectionsCar
    type.isBreak -> Icons.Outlined.Coffee
    type == ActivityType.OFFICE_WORK -> Icons.Outlined.Business
    type == ActivityType.OTHER_UNCOUNTED -> Icons.Outlined.PauseCircle
    else -> Icons.Outlined.Build
}

@Composable
fun ActivityCard(entry: WorkEntry, format: DurationFormat, onClick: () -> Unit) {
    val effective = entry.effectiveMinutes > 0
    val nonEffective = entry.nonEffectiveMinutes > 0
    val accent = if (effective) MaterialTheme.colorScheme.primary else if (nonEffective) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Card(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(activityIcon(entry.type), null, Modifier.size(22.dp), accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${entry.startTime.format(ClockFormat)} — ${entry.endTime.format(ClockFormat)}${if (entry.startTime.toLocalDate() != entry.endTime.toLocalDate()) " (+1 j)" else ""} · ${entry.type.displayLabel}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.caseNumber.isNotBlank()) Text("Affaire ${entry.caseNumber}", style = MaterialTheme.typography.labelSmall, color = accent)
                if (entry.type.isTravel) Text("Brut : ${duration(entry.rawMinutes)}${if (entry.deductedMinutes > 0) " · Franchise : ${entry.deductedMinutes} min" else ""}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(duration(entry.countedMinutes, format), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
                Text(if (effective) "Effectif" else if (nonEffective) "Non effectif" else "Exclu", style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
    }
}

@Composable
fun DetailLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

fun chooseDate(context: Context, current: LocalDate, onDate: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, current.year, current.monthValue - 1, current.dayOfMonth).show()
}
fun chooseTime(context: Context, minute: Int, onTime: (Int) -> Unit) {
    TimePickerDialog(context, { _, hour, minutes -> onTime(hour * 60 + minutes) }, minute / 60, minute % 60, true).show()
}
fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
