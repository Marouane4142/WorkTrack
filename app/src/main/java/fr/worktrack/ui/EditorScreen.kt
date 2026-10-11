package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(state: WorkTrackState, draft: EditorDraft?, vm: WorkTrackViewModel, editing: Boolean) {
    if (draft == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val context = LocalContext.current
    val rules = vm.rulesFor(draft)
    val preview = runCatching { WorkCalculator.calculate(draft.entry(), rules) }
    val calculated = preview.getOrNull()
    val overlaps = calculated?.let { WorkCalculator.overlapping(it, state.entries) }.orEmpty()
    var confirmOverlap by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val travel = draft.type.isTravel
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Eyebrow(if (editing) "Ajustez votre plage horaire" else "Une activité, une plage horaire")
            OutlinedButton({ chooseDate(context, LocalDate.parse(draft.date)) { date -> vm.updateDraft { it.copy(date = date.toString()) }; vm.refreshPlaces() } }, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(LocalDate.parse(draft.date).format(LongDate))
            }
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Début")
                            TextButton({ chooseTime(context, draft.startMinute, vm::setStart) }, Modifier.fillMaxWidth()) {
                                Text(clock(draft.startMinute), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        VerticalDivider(Modifier.height(64.dp))
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Fin")
                            TextButton({ chooseTime(context, draft.endMinute, vm::setEnd) }, Modifier.fillMaxWidth()) {
                                Text(clock(draft.endMinute), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.nextDay, vm::setNextDay)
                            Text("Fin le lendemain", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ vm.setEnd(minOf(draft.endMinute + 15, 1439)) }, enabled = draft.endMinute < 1439) { Text("+ 15 min") }
                    }
                }
            }
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(draft.type.displayLabel, {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    readOnly = true, label = { Text("Type d'activité") }, leadingIcon = { Icon(activityIcon(draft.type), null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = RoundedCornerShape(16.dp))
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    ActivityType.selectable.forEach { type -> DropdownMenuItem(text = { Text(type.displayLabel) }, leadingIcon = { Icon(activityIcon(type), null, Modifier.size(20.dp)) }, onClick = {
                        expanded = false
                        vm.selectType(type)
                    }) }
                }
            }
            calculated?.let { entry ->
                val explanation = when {
                    travel && entry.nonEffectiveMinutes + entry.deductedMinutes > 0 -> "Domicile ↔ site : ${entry.deductedMinutes} min de franchise ; ${duration(entry.nonEffectiveMinutes)} non effectives."
                    travel && entry.effectiveMinutes > 0 -> "Trajet professionnel : durée entièrement effective."
                    travel -> "Ce trajet ne compte pas dans les heures. Sa durée brute est conservée."
                    entry.type.isBreak -> "La pause reste enregistrée et ne compte pas dans les heures."
                    else -> "Classification automatique. Durée brute conservée."
                }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Aperçu du décompte", MaterialTheme.colorScheme.onPrimaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${duration(entry.countedMinutes)} comptabilisées", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Brut ${duration(entry.rawMinutes)} · Effectif ${duration(entry.effectiveMinutes)} · Non effectif ${duration(entry.nonEffectiveMinutes)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (entry.roundingMinutes > 0) Text("Arrondi inférieur configuré : ${entry.roundingMinutes} min déduites.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } ?: InfoCard("Vérifiez la saisie", preview.exceptionOrNull()?.message ?: "Horaires invalides.", Icons.Outlined.ErrorOutline)
            if (editing) Text("Les règles enregistrées avec cette activité sont conservées lors de la modification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (overlaps.isNotEmpty()) InfoCard("Chevauchement détecté", "Cette plage croise ${overlaps.size} activité(s). Corrigez les horaires ou confirmez ce chevauchement à l'enregistrement.", Icons.Outlined.WarningAmber)
            if (travel) {
                SectionTitle("Le trajet")
                PlaceField("Départ", draft.originType, draft.origin,
                    onType = { type -> vm.updateDraft { it.copy(originType = type, origin = if (type == PlaceType.CUSTOMER_SITE) "" else type.label, originAutomatic = false) } },
                    onName = { name -> vm.updateDraft { it.copy(origin = name, originAutomatic = false) } })
                if (draft.originAutomatic && draft.originType != null) Text("Départ repris de votre dernière activité connue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlaceField("Destination", draft.destinationType, draft.destination,
                    onType = { type -> vm.updateDraft { it.copy(destinationType = type, destination = if (type == PlaceType.CUSTOMER_SITE) "" else type.label) } },
                    onName = { name -> vm.updateDraft { it.copy(destination = name) } })
            }
            if (draft.type == ActivityType.CUSTOMER_INTERVENTION) {
                SectionTitle("L'intervention")
                SuggestionField("Lieu / site", draft.location, state.entries.map { it.location } + state.cases.map { it.location }, vm::setLocation)
                SuggestionField("N° AFFAIRE", draft.caseNumber, state.cases.map { it.number }) { v ->
                    val known = state.cases.firstOrNull { it.number == v }
                    vm.updateDraft { it.copy(caseNumber = v, location = known?.location?.takeIf { site -> site.isNotBlank() } ?: it.location,
                        locationAutomatic = if (known?.location?.isNotBlank() == true) false else it.locationAutomatic) }
                }
            }
            if (draft.type in listOf(ActivityType.OTHER_EFFECTIVE, ActivityType.OTHER_UNCOUNTED)) {
                var classExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(classExpanded, { classExpanded = it }) {
                    val labels = mapOf(TimeClassification.EFFECTIVE to "Effective", TimeClassification.NON_EFFECTIVE to "Non effective", TimeClassification.UNCOUNTED to "Non comptabilisée")
                    OutlinedTextField(draft.classification?.let { labels[it] } ?: "Automatique", {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        readOnly = true, label = { Text("Classification personnalisée") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classExpanded) }, shape = RoundedCornerShape(16.dp))
                    ExposedDropdownMenu(classExpanded, { classExpanded = false }) {
                        DropdownMenuItem(text = { Text("Automatique") }, onClick = { classExpanded = false; vm.updateDraft { it.copy(classification = null, overrideReason = "") } })
                        labels.forEach { (classification, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { classExpanded = false; vm.updateDraft { it.copy(classification = classification) } }) }
                    }
                }
                if (draft.classification != null) OutlinedTextField(draft.overrideReason, { v -> vm.updateDraft { it.copy(overrideReason = v) } }, Modifier.fillMaxWidth(),
                    label = { Text("Motif de correction (obligatoire)") }, shape = RoundedCornerShape(16.dp))
            }
            OutlinedTextField(draft.notes, { v -> vm.updateDraft { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text("Notes (facultatif)") }, minLines = 3, maxLines = 6, shape = RoundedCornerShape(16.dp))
            if (editing) TextButton({ confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Supprimer cette activité", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Button({ if (overlaps.isNotEmpty()) confirmOverlap = true else vm.saveDraft() }, Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
                enabled = calculated != null && !state.busy) { Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(10.dp)); Text("Enregistrer l'activité") }
        }
    }
    if (confirmOverlap) AlertDialog(onDismissRequest = { confirmOverlap = false }, title = { Text("Enregistrer le chevauchement ?") },
        text = { Text("Les durées des plages superposées seront additionnées. Le chevauchement restera visible dans la journée.") },
        confirmButton = { TextButton({ confirmOverlap = false; vm.saveDraft(true) }) { Text("Enregistrer quand même") } },
        dismissButton = { TextButton({ confirmOverlap = false }) { Text("Corriger") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette activité ?") },
        confirmButton = { TextButton({ confirmDelete = false; state.entries.firstOrNull { it.id == draft.id }?.let { vm.delete(it, true) } }) { Text("Supprimer") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceField(label: String, type: PlaceType?, name: String, onType: (PlaceType) -> Unit, onName: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(type?.label.orEmpty(), {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                readOnly = true, label = { Text("$label · type de lieu") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = RoundedCornerShape(16.dp))
            ExposedDropdownMenu(expanded, { expanded = false }) {
                PlaceType.entries.forEach { place -> DropdownMenuItem(text = { Text(place.label) }, onClick = { expanded = false; onType(place) }) }
            }
        }
        if (type == PlaceType.CUSTOMER_SITE) OutlinedTextField(name, onName, Modifier.fillMaxWidth(),
            label = { Text("$label · nom du site") }, singleLine = true, shape = RoundedCornerShape(16.dp))
    }
}

@Composable
private fun SuggestionField(label: String, value: String, values: List<String>, onChange: (String) -> Unit) {
    val suggestions = values.filter { it.isNotBlank() && it != value && it.contains(value, ignoreCase = true) }.distinct().take(3)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.take(2).forEach { suggestion -> SuggestionChip({ onChange(suggestion) }, label = { Text(suggestion, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f)) }
        }
    }
}
