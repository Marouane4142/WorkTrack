package fr.worktrack.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import fr.worktrack.R
import fr.worktrack.domain.*
import fr.worktrack.export.PendingDocument
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data object HomeRoute : NavKey
@Serializable data class HistoryRoute(val period: String = "Mois") : NavKey
@Serializable data class ExportsRoute(val from: String = "", val through: String = "") : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class DayRoute(val date: String) : NavKey
@Serializable data class EditorRoute(val id: String? = null, val date: String, val duplicateId: String? = null) : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkTrackApp(viewModel: WorkTrackViewModel, state: WorkTrackState) {
    val backStack = rememberNavBackStack(HomeRoute)
    val current = backStack.lastOrNull() ?: HomeRoute
    val isEditor = current is EditorRoute
    val isRoot = current !is DayRoute && !isEditor
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compactAddButton = windowWidth < 380.dp || density.fontScale > 1.25f
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var exitEditor by remember { mutableStateOf(false) }
    var savedDocument by remember { mutableStateOf<PendingDocument?>(null) }
    var importedBackup by remember { mutableStateOf<Backup?>(null) }
    var replaceBackup by remember { mutableStateOf(false) }
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.finishDocument(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { try { importedBackup = viewModel.readBackup(uri) } catch (e: Exception) { viewModel.message(e.message ?: "Sauvegarde invalide.") } }
    }
    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.profileImage(it, signature = false) }
    }
    val signatureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.profileImage(it, signature = true) }
    }
    fun back() { if (isEditor && draft != null) exitEditor = true else if (backStack.size > 1) backStack.removeLastOrNull() }
    fun root(route: NavKey) { backStack.clear(); backStack.add(route) }
    fun add(date: LocalDate = LocalDate.now()) { backStack.add(EditorRoute(date = date.toString())) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> when (event) {
            is UiEvent.Message -> scope.launch { snackbars.showSnackbar(event.text) }
            UiEvent.Saved -> if (backStack.lastOrNull() is EditorRoute && backStack.size > 1) backStack.removeLastOrNull()
            is UiEvent.CreateDocument -> saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(event.document.mime).putExtra(Intent.EXTRA_TITLE, event.document.name))
            is UiEvent.DocumentSaved -> savedDocument = event.document
        } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isRoot) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.ic_worktrack), "Logo WorkTrack", Modifier.size(38.dp))
                    Text("WorkTrack", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            } else TopAppBar(title = { Text(if (isEditor) if (current.id == null) "Nouvelle activité" else "Modifier l'activité" else "Votre journée") },
                navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if (isRoot) {
                val labels = listOf("Accueil", "Historique", "Exports", "Réglages")
                val icons = listOf(Icons.Outlined.SpaceDashboard, Icons.Outlined.CalendarMonth, Icons.Outlined.FileDownload, Icons.Outlined.Tune)
                val selected = when (current) { is HistoryRoute -> 1; is ExportsRoute -> 2; SettingsRoute -> 3; else -> 0 }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    labels.forEachIndexed { index, label -> NavigationBarItem(selected == index, onClick = {
                        root(when (index) { 1 -> HistoryRoute(); 2 -> ExportsRoute(); 3 -> SettingsRoute; else -> HomeRoute })
                    }, icon = { Icon(icons[index], label) }, label = { Text(label) }, alwaysShowLabel = true) }
                }
            }
        },
        floatingActionButton = {
            if (current == HomeRoute || current is HistoryRoute) {
                val addModifier = Modifier.semantics { contentDescription = "Nouvelle activité" }
                if (compactAddButton) FloatingActionButton(onClick = { add() }, modifier = addModifier,
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Outlined.Add, null)
                } else ExtendedFloatingActionButton(onClick = { add() }, modifier = addModifier,
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Nouvelle activité") },
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            }
        }, snackbarHost = { SnackbarHost(snackbars) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (state.error != null) EmptyState("Lecture des données interrompue", state.error, Icons.Outlined.ErrorOutline,
                "Réessayer", viewModel::retry)
            else NavDisplay(backStack = backStack, onBack = ::back, modifier = Modifier.fillMaxSize(), entryProvider = entryProvider {
                entry<HomeRoute> { DashboardScreen(state, onAdd = { add() }, onDay = { backStack.add(DayRoute(it.toString())) },
                    onWeek = { root(HistoryRoute("Semaine")) }, onExports = { root(ExportsRoute()) }) }
                entry<HistoryRoute> { route -> HistoryScreen(state, route.period, onDay = { backStack.add(DayRoute(it.toString())) }) }
                entry<ExportsRoute> { route -> ExportsScreen(state, viewModel, route.from, route.through) }
                entry<SettingsRoute> { SettingsScreen(state, viewModel,
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onPickImage = { signature -> if (signature) signatureLauncher.launch(arrayOf("image/*")) else logoLauncher.launch(arrayOf("image/*")) }) }
                entry<DayRoute> { route -> DayScreen(LocalDate.parse(route.date), state, viewModel,
                    onAdd = { add(LocalDate.parse(route.date)) }, onEdit = { backStack.add(EditorRoute(it.id, it.startTime.toLocalDate().toString())) },
                    onDuplicate = { backStack.add(EditorRoute(date = it.startTime.toLocalDate().toString(), duplicateId = it.id)) },
                    onExport = { backStack.add(ExportsRoute(route.date, route.date)) }) }
                entry<EditorRoute> { route ->
                    LaunchedEffect(route) { viewModel.beginEditor(route.id, LocalDate.parse(route.date), route.duplicateId) }
                    EditorScreen(state, draft, viewModel, route.id != null)
                }
            })
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (exitEditor) AlertDialog(onDismissRequest = { exitEditor = false }, title = { Text("Quitter la saisie ?") },
        text = { Text("Les modifications de cette activité ne seront pas enregistrées.") },
        confirmButton = { TextButton({ exitEditor = false; viewModel.discardDraft(); backStack.removeLastOrNull() }) { Text("Quitter") } },
        dismissButton = { TextButton({ exitEditor = false }) { Text("Continuer la saisie") } })
    savedDocument?.let { document -> SavedDocumentDialog(document,
        onOpen = { savedDocument = null; viewModel.open(document) },
        onShare = { savedDocument = null; viewModel.share(document) }, onClose = { savedDocument = null }) }
    importedBackup?.let { backup -> AlertDialog(onDismissRequest = { importedBackup = null; replaceBackup = false },
        title = { Text(if (replaceBackup) "Remplacer l'historique ?" else "Restaurer une sauvegarde") },
        text = { Text(if (replaceBackup) "Les ${state.entries.size} activités actuelles et les réglages seront remplacés par cette sauvegarde de ${backup.entries.size} activités."
            else "${backup.entries.size} activités. La fusion ajoute les activités manquantes et conserve vos réglages et vos activités actuelles.") },
        confirmButton = { TextButton({ viewModel.restore(backup, replaceBackup); importedBackup = null; replaceBackup = false }) {
            Text(if (replaceBackup) "Confirmer le remplacement" else "Fusionner") } },
        dismissButton = { Row {
            if (!replaceBackup) TextButton({ replaceBackup = true }) { Text("Remplacer…") }
            TextButton({ importedBackup = null; replaceBackup = false }) { Text("Annuler") }
        } }) }
}
