package fr.worktrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.worktrack.domain.*
import fr.worktrack.export.RasterImages
import fr.worktrack.BuildConfig
import java.time.LocalDate

@Composable
fun SettingsScreen(state: WorkTrackState, vm: WorkTrackViewModel, onRestore: () -> Unit, onPickImage: (Boolean) -> Unit) {
    var profileEditor by remember { mutableStateOf(false) }
    var rulesEditor by remember { mutableStateOf(false) }
    var journal by remember { mutableStateOf(false) }
    var recalculate by remember { mutableStateOf(false) }
    var eraseStep by remember { mutableIntStateOf(0) }
    var resetRules by remember { mutableStateOf(false) }
    val profile = state.settings.profile; val preferences = state.settings.preferences; val rules = state.settings.rules
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("À votre façon.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Votre profil, vos règles, vos préférences.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card({ profileEditor = true }, Modifier.fillMaxWidth(), shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, Modifier.size(38.dp), MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.fullName.ifBlank { "Compléter mon profil" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(profile.company.ifBlank { "Nom et entreprise sur vos relevés" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Edit, "Modifier le profil", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SettingsCard("Vos relevés", Icons.Outlined.Description) {
                Text("Ajoutez le logo de votre entreprise et votre signature aux feuilles Excel et PDF.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProfileImage("Logo de l'entreprise", profile.companyLogo, state.busy, onPick = { onPickImage(false) },
                    onClear = { vm.settings { it.copy(profile = it.profile.copy(companyLogo = "")) } })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ProfileImage("Votre signature", profile.signature, state.busy, onPick = { onPickImage(true) },
                    onClear = { vm.settings { it.copy(profile = it.profile.copy(signature = "")) } })
            }
        }
        item {
            SettingsCard("Apparence", Icons.Outlined.Palette) {
                Text("Thème", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mapOf(Appearance.SYSTEM to "Système", Appearance.LIGHT to "Clair", Appearance.DARK to "Sombre").forEach { (value, label) ->
                        FilterChip(preferences.appearance == value, { vm.settings { it.copy(preferences = it.preferences.copy(appearance = value)) } }, label = { Text(label) }, enabled = !state.busy)
                    }
                }
                Text("Affichage des durées", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(preferences.durationFormat == DurationFormat.HOURS_MINUTES, { vm.settings { it.copy(preferences = it.preferences.copy(durationFormat = DurationFormat.HOURS_MINUTES)) } }, label = { Text("1 h 30") }, enabled = !state.busy)
                    FilterChip(preferences.durationFormat == DurationFormat.DECIMAL, { vm.settings { it.copy(preferences = it.preferences.copy(durationFormat = DurationFormat.DECIMAL)) } }, label = { Text("1,50 h") }, enabled = !state.busy)
                }
                Text("Premier jour de la semaine", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "Lundi", false to "Dimanche").forEach { (monday, label) -> FilterChip(preferences.weekStartsOnMonday == monday,
                        { vm.settings { it.copy(preferences = it.preferences.copy(weekStartsOnMonday = monday)) } }, label = { Text(label) }, enabled = !state.busy) }
                }
            }
        }
        item {
            SettingsCard("Règles de temps", Icons.Outlined.Tune) {
                DetailLine("Franchise domicile ↔ site", "${rules.returnHomeDeductionMinutes} min", true)
                DetailLine("Seuil repas effectif", duration(rules.mealThresholdMinutes))
                DetailLine("Maximum de repas / jour", rules.maxAutomaticMealsPerDay.toString())
                DetailLine("Arrondi inférieur", if (rules.roundDownStepMinutes == 0) "Désactivé" else "${rules.roundDownStepMinutes} min")
                DetailLine("Plage de nuit", "${clock(rules.nightStartMinute)}–${clock(rules.nightEndMinute)}")
                FilledTonalButton({ rulesEditor = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Modifier les règles") }
                TextButton({ recalculate = true }, Modifier.fillMaxWidth(), enabled = !state.busy && state.entries.isNotEmpty()) { Text("Recalculer une ancienne période") }
                TextButton({ resetRules = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Rétablir les règles par défaut") }
            }
        }
        item {
            SettingsCard("Vos données", Icons.Outlined.Storage) {
                Text("${state.entries.size} activités · ${state.cases.size} affaires\nEnregistrées sur cet appareil, sans compte.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(vm::backup, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Outlined.Backup, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Sauvegarder mes données") }
                OutlinedButton(onRestore, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Outlined.Restore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Restaurer une sauvegarde") }
                TextButton({ journal = true }, Modifier.fillMaxWidth()) { Text("Journal des modifications") }
                TextButton({ eraseStep = 1 }, Modifier.fillMaxWidth(), enabled = !state.busy && state.entries.isNotEmpty()) { Text("Effacer l'historique", color = MaterialTheme.colorScheme.error) }
            }
        }
        item { Text("WorkTrack ${BuildConfig.VERSION_NAME} · Votre temps de travail, partout.\nLes règles sont enregistrées avec chaque activité. Une modification des réglages s'applique aux nouvelles saisies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (profileEditor) ProfileDialog(profile, onDismiss = { profileEditor = false }) { updated -> profileEditor = false; vm.settings { it.copy(profile = updated) } }
    if (rulesEditor) RulesDialog(rules, onDismiss = { rulesEditor = false }) { updated -> rulesEditor = false; vm.settings { it.copy(rules = updated) } }
    if (journal) AuditDialog(state.audit) { journal = false }
    if (recalculate) RecalculationDialog(state, onDismiss = { recalculate = false }) { from, through -> recalculate = false; vm.recalculate(from, through) }
    if (resetRules) AlertDialog(onDismissRequest = { resetRules = false }, title = { Text("Rétablir les règles ?") },
        text = { Text("Franchise domicile ↔ site de 45 minutes, seuil repas de 4 heures, aucun arrondi. L'historique sera conservé.") },
        confirmButton = { TextButton({ resetRules = false; vm.settings { it.copy(rules = WorkRules()) } }) { Text("Rétablir") } },
        dismissButton = { TextButton({ resetRules = false }) { Text("Annuler") } })
    if (eraseStep > 0) AlertDialog(onDismissRequest = { eraseStep = 0 }, title = { Text(if (eraseStep == 1) "Effacer votre historique ?" else "Dernière confirmation") },
        text = { Text(if (eraseStep == 1) "Vos activités, affaires et modifications seront effacées. Pensez à enregistrer une sauvegarde avant de poursuivre."
            else "Confirmez l'effacement des ${state.entries.size} activités. Votre profil et vos réglages seront conservés.") },
        confirmButton = { TextButton({ if (eraseStep == 1) eraseStep = 2 else { eraseStep = 0; vm.erase() } }) { Text(if (eraseStep == 1) "Continuer" else "Effacer définitivement", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ eraseStep = 0 }) { Text("Annuler") } })
}

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(4.dp)); content()
        }
    }
}

@Composable
private fun ProfileImage(title: String, encoded: String, busy: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val bitmap = remember(encoded) { RasterImages.decode(encoded) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    if (bitmap != null) Surface(shape = RoundedCornerShape(14.dp), color = androidx.compose.ui.graphics.Color.White) {
        Image(bitmap.asImageBitmap(), title, Modifier.fillMaxWidth().height(96.dp).padding(12.dp), contentScale = ContentScale.Fit)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onPick, enabled = !busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (encoded.isBlank()) "Ajouter une image" else "Changer l'image")
        }
        if (encoded.isNotBlank()) IconButton(onClear, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Retirer $title") }
    }
}

@Composable
private fun ProfileDialog(original: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var profile by remember { mutableStateOf(original) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Votre profil") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileField("Prénom", profile.firstName) { profile = profile.copy(firstName = it) }
            ProfileField("Nom", profile.lastName) { profile = profile.copy(lastName = it) }
            ProfileField("Entreprise", profile.company) { profile = profile.copy(company = it) }
        }
    }, confirmButton = { TextButton({ onSave(profile) }) { Text("Enregistrer") } }, dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit) { OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp)) }

@Composable
private fun RulesDialog(original: WorkRules, onDismiss: () -> Unit, onSave: (WorkRules) -> Unit) {
    var deduction by rememberSaveable { mutableStateOf(original.returnHomeDeductionMinutes.toString()) }
    var threshold by rememberSaveable { mutableStateOf(original.mealThresholdMinutes.toString()) }
    var maximum by rememberSaveable { mutableStateOf(original.maxAutomaticMealsPerDay.toString()) }
    var round by rememberSaveable { mutableIntStateOf(original.roundDownStepMinutes) }
    var nightStart by rememberSaveable { mutableIntStateOf(original.nightStartMinute) }
    var nightEnd by rememberSaveable { mutableIntStateOf(original.nightEndMinute) }
    var classification by remember { mutableStateOf(original.otherProfessionalClassification) }
    val context = LocalContext.current
    val rule = runCatching { WorkRules(deduction.toInt(), threshold.toInt(), maximum.toInt(), round, nightStart, nightEnd, classification).also { it.validate() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Règles de calcul") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ces règles s'appliquent aux nouvelles activités. Les anciens relevés gardent leurs réglages.", style = MaterialTheme.typography.bodySmall)
            NumericRuleField("Franchise domicile ↔ site (minutes)", deduction) { deduction = it }
            NumericRuleField("Seuil repas effectif (minutes)", threshold) { threshold = it }
            NumericRuleField("Maximum de repas / jour", maximum) { maximum = it }
            Text("Arrondi inférieur du temps comptabilisé", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(0, 5, 15, 30).forEach { step -> FilterChip(round == step, { round = step }, label = { Text(if (step == 0) "Aucun" else "$step") }) } }
            Text("Le brut reste exact. Seul le temps comptabilisé est arrondi à l'inférieur, si activé.", style = MaterialTheme.typography.bodySmall)
            Text("Horaires de nuit (sans majoration)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ chooseTime(context, nightStart) { nightStart = it } }, Modifier.weight(1f)) { Text(clock(nightStart)) }
                OutlinedButton({ chooseTime(context, nightEnd) { nightEnd = it } }, Modifier.weight(1f)) { Text(clock(nightEnd)) }
            }
            Text("Autre activité professionnelle", style = MaterialTheme.typography.labelLarge)
            listOf(TimeClassification.EFFECTIVE to "Effective", TimeClassification.NON_EFFECTIVE to "Non effective", TimeClassification.UNCOUNTED to "Non comptabilisée").forEach { (value, label) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(classification == value, { classification = value }); Text(label) }
            }
            if (rule.isFailure) Text("Vérifiez les valeurs (franchise et seuil entre 0/1 et 1 440 min ; maximum 0 à 10).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ rule.getOrNull()?.let(onSave) }, enabled = rule.isSuccess) { Text("Enregistrer") } }, dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

@Composable private fun NumericRuleField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.all(Char::isDigit) && it.length <= 4) onChange(it) }, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
}

@Composable
private fun RecalculationDialog(state: WorkTrackState, onDismiss: () -> Unit, onRecalculate: (LocalDate, LocalDate) -> Unit) {
    var fromText by rememberSaveable { mutableStateOf(state.entries.minByOrNull { it.start }?.startTime?.toLocalDate()?.toString() ?: LocalDate.now().toString()) }
    var throughText by rememberSaveable { mutableStateOf(state.entries.maxByOrNull { it.start }?.startTime?.toLocalDate()?.toString() ?: LocalDate.now().toString()) }
    val from = LocalDate.parse(fromText); val through = LocalDate.parse(throughText)
    val count = state.entries.count { it.startTime.toLocalDate() in from..through }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Recalculer une période") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Les activités commençant sur cette période seront recalculées avec vos règles actuelles. Les horaires bruts seront conservés et chaque changement sera tracé.")
        OutlinedButton({ chooseDate(context, from) { fromText = it.toString(); if (it > through) throughText = it.toString() } }, Modifier.fillMaxWidth()) { Text("Du ${from.format(ShortDate)}") }
        OutlinedButton({ chooseDate(context, through) { throughText = it.toString(); if (it < from) fromText = it.toString() } }, Modifier.fillMaxWidth()) { Text("Au ${through.format(ShortDate)}") }
        Text("$count activités concernées.", fontWeight = FontWeight.Bold)
    } }, confirmButton = { TextButton({ onRecalculate(from, through) }, enabled = count > 0) { Text("Confirmer le recalcul") } }, dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

@Composable
private fun AuditDialog(events: List<AuditEvent>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Journal des modifications") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (events.isEmpty()) Text("Aucune modification enregistrée.")
            events.take(80).forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val timestamp = runCatching { java.time.Instant.parse(event.at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) }.getOrDefault(event.at)
                    Text("${event.action} · $timestamp", style = MaterialTheme.typography.labelLarge)
                    Text(event.description, style = MaterialTheme.typography.bodySmall)
                    if (event.before != null && event.after != null) {
                        Text("Avant : ${event.before.startTime.format(ClockFormat)}–${event.before.endTime.format(ClockFormat)}, ${duration(event.before.countedMinutes)}, affaire ${event.before.caseNumber.ifBlank { "—" }}\nAprès : ${event.after.startTime.format(ClockFormat)}–${event.after.endTime.format(ClockFormat)}, ${duration(event.after.countedMinutes)}, affaire ${event.after.caseNumber.ifBlank { "—" }}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fermer") } })
}
