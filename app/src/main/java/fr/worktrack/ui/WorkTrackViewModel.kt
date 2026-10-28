package fr.worktrack.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.worktrack.data.*
import fr.worktrack.domain.*
import fr.worktrack.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class WorkTrackState(val entries: List<WorkEntry> = emptyList(), val cases: List<BusinessCase> = emptyList(),
    val audit: List<AuditEvent> = emptyList(), val settings: AppSettings = AppSettings(),
    val summaries: Map<LocalDate, DaySummary> = emptyMap(), val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null)

@Serializable
data class EditorDraft(val id: String = UUID.randomUUID().toString(), val date: String,
    val startMinute: Int = 480, val endMinute: Int = 540, val nextDay: Boolean = false,
    val type: ActivityType = ActivityType.CUSTOMER_INTERVENTION, val origin: String = "", val destination: String = "",
    val client: String = "", val location: String = "", val caseName: String = "", val caseNumber: String = "",
    val notes: String = "", val classification: TimeClassification? = null, val overrideReason: String = "",
    val originType: PlaceType? = null, val destinationType: PlaceType? = null, val originAutomatic: Boolean = true,
    val locationAutomatic: Boolean = true) {
    fun entry(): WorkEntry {
        val (start, end) = WorkCalculator.interval(LocalDate.parse(date), startMinute, endMinute, nextDay)
        return WorkEntry(id = id, start = start.toString(), end = end.toString(), type = type,
            origin = if (type.isTravel) originType?.let { if (it == PlaceType.CUSTOMER_SITE) origin.trim() else it.label }.orEmpty() else "",
            destination = if (type.isTravel) destinationType?.let { if (it == PlaceType.CUSTOMER_SITE) destination.trim() else it.label }.orEmpty() else "",
            caseNumber = if (type == ActivityType.CUSTOMER_INTERVENTION) caseNumber else "",
            location = if (type == ActivityType.CUSTOMER_INTERVENTION) location else "", notes = notes,
            classificationOverride = classification, overrideReason = overrideReason,
            originType = if (type.isTravel) originType else null, destinationType = if (type.isTravel) destinationType else null)
    }
    companion object {
        fun from(entry: WorkEntry, newId: Boolean = false) = EditorDraft(id = if (newId) UUID.randomUUID().toString() else entry.id,
            date = entry.startTime.toLocalDate().toString(), startMinute = entry.startTime.hour * 60 + entry.startTime.minute,
            endMinute = entry.endTime.hour * 60 + entry.endTime.minute, nextDay = entry.endTime.toLocalDate() != entry.startTime.toLocalDate(),
            type = if (entry.type.isTravel) ActivityType.TRAVEL else if (entry.type.isBreak) ActivityType.LUNCH_BREAK else entry.type,
            origin = entry.departure?.label ?: entry.origin, destination = entry.arrival?.label ?: entry.destination,
            client = entry.client, location = entry.location,
            caseName = entry.caseName, caseNumber = entry.caseNumber, notes = entry.notes,
            classification = entry.classificationOverride, overrideReason = entry.overrideReason,
            originType = entry.departure?.type, destinationType = if (entry.type.isTravel) entry.arrival?.type else null,
            originAutomatic = false, locationAutomatic = false)
    }
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object Saved : UiEvent
    data class CreateDocument(val document: PendingDocument) : UiEvent
    data class DocumentSaved(val document: PendingDocument) : UiEvent
}

@HiltViewModel
class WorkTrackViewModel @Inject constructor(private val repository: WorkRepository,
    private val documents: DocumentService, private val handle: SavedStateHandle, private val images: ImageImporter) : ViewModel() {
    private val initialized = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val data = refresh.flatMapLatest {
        combine(repository.entries, repository.cases, repository.audit, repository.settings) { entries, cases, audit, settings ->
            WorkTrackState(entries, cases, audit, settings, WorkCalculator.summaries(entries).associateBy { it.date }, loading = false)
        }.flowOn(Dispatchers.Default).catch { error ->
            emit(WorkTrackState(loading = false, error = error.message ?: "Impossible de lire les données locales."))
        }
    }
    val state: StateFlow<WorkTrackState> = combine(data, initialized, busy, failure) { data, ready, busy, error ->
        data.copy(loading = !ready, busy = busy, error = error ?: data.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkTrackState())
    private val eventChannel = kotlinx.coroutines.channels.Channel<UiEvent>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    val draft: StateFlow<EditorDraft?> = handle.getStateFlow<String?>("editor", null).map { it?.let { EntryCodec.json.decodeFromString<EditorDraft>(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, handle.get<String>("editor")?.let { EntryCodec.json.decodeFromString(it) })

    init { retry() }
    fun retry() {
        viewModelScope.launch {
            try {
                repository.initialize()
                failure.value = null
                refresh.value++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure.value = e.message ?: "Impossible de lire les données locales." }
            finally { initialized.value = true }
        }
    }

    fun beginEditor(id: String?, date: LocalDate, duplicateId: String? = null) {
        val route = "$id/$date/$duplicateId"
        if (handle.get<String>("editor_route") == route && handle.get<String>("editor") != null) return
        val original = state.value.entries.firstOrNull { it.id == (duplicateId ?: id) }
        val last = state.value.entries.filter { it.startTime.toLocalDate() == date }.maxByOrNull { it.end }
        val now = LocalTime.now()
        val start = (if (last != null && last.endTime.toLocalDate() == date) last.endTime.hour * 60 + last.endTime.minute
            else if (date == LocalDate.now()) (now.hour * 60 + now.minute) / 15 * 15 else 480
        ).coerceAtMost(1438)
        val newDraft = if (original != null) EditorDraft.from(original, duplicateId != null) else withAutomaticPlaces(EditorDraft(
            date = date.toString(), startMinute = start, endMinute = minOf(start + 60, 1439)))
        handle["editor_route"] = route
        updateDraft { newDraft }
    }

    fun updateDraft(transform: (EditorDraft) -> EditorDraft) {
        val current = handle.get<String>("editor")?.let { EntryCodec.json.decodeFromString<EditorDraft>(it) }
            ?: EditorDraft(date = LocalDate.now().toString())
        handle["editor"] = EntryCodec.json.encodeToString(transform(current))
    }
    fun discardDraft() { handle["editor"] = null; handle["editor_route"] = null }

    fun selectType(type: ActivityType) = updateDraft { current ->
        withAutomaticPlaces(current.copy(type = type, classification = null, overrideReason = ""))
    }
    fun setLocation(name: String) = updateDraft { it.copy(location = name, locationAutomatic = false) }
    fun setStart(minute: Int) = changeTime(minute, null)
    fun setEnd(minute: Int) = changeTime(null, minute)
    fun setNextDay(nextDay: Boolean) {
        val current = draft.value ?: return
        if (!nextDay && current.endMinute <= current.startMinute) {
            message("Choisissez une fin postérieure au début avant de revenir à une seule journée."); return
        }
        updateDraft { it.copy(nextDay = nextDay) }
    }
    private fun changeTime(start: Int?, end: Int?) {
        val current = draft.value ?: return
        val revised = current.copy(startMinute = start ?: current.startMinute, endMinute = end ?: current.endMinute)
        val error = runCatching { revised.entry() }.exceptionOrNull()
        if (error != null) { message("La fin doit être postérieure au début. Pour passer minuit, choisissez explicitement « Fin le lendemain »."); return }
        updateDraft { withAutomaticPlaces(revised) }
    }
    fun refreshPlaces() = updateDraft(::withAutomaticPlaces)
    private fun withAutomaticPlaces(current: EditorDraft): EditorDraft {
        val start = LocalDate.parse(current.date).atStartOfDay().plusMinutes(current.startMinute.toLong())
        val previous = PlaceMemory.before(state.value.entries, start, current.id)
        return current.copy(
            originType = if (current.originAutomatic) previous?.type else current.originType,
            origin = if (current.originAutomatic) previous?.label.orEmpty() else current.origin,
            location = if (current.locationAutomatic && current.type == ActivityType.CUSTOMER_INTERVENTION)
                PlaceMemory.interventionSiteBefore(state.value.entries, start, current.id).orEmpty() else current.location)
    }

    fun rulesFor(draft: EditorDraft): WorkRules {
        val original = state.value.entries.firstOrNull { it.id == draft.id }
        val rule = original?.appliedRules ?: state.value.settings.rules
        val first = state.value.entries.filter { it.id != draft.id && WorkCalculator.touches(it, LocalDate.parse(draft.date)) }.minByOrNull { it.start }
        return rule.copy(mealThresholdMinutes = first?.appliedRules?.mealThresholdMinutes ?: rule.mealThresholdMinutes,
            maxAutomaticMealsPerDay = first?.appliedRules?.maxAutomaticMealsPerDay ?: rule.maxAutomaticMealsPerDay)
    }
    fun saveDraft(allowOverlap: Boolean = false) = action {
        val current = draft.value ?: error("Aucune activité à enregistrer.")
        repository.save(current.entry(), allowOverlap)
        discardDraft(); eventChannel.send(UiEvent.Saved)
    }
    fun delete(entry: WorkEntry, closeEditor: Boolean = false) = action {
        repository.delete(entry.id)
        if (closeEditor) { discardDraft(); eventChannel.send(UiEvent.Saved) }
        else eventChannel.send(UiEvent.Message("Activité supprimée."))
    }
    fun duplicateDay(from: LocalDate, to: LocalDate) = action {
        repository.duplicateDay(from, to); eventChannel.send(UiEvent.Message("Journée copiée au ${to.format(ShortDate)}."))
    }
    fun settings(transform: (AppSettings) -> AppSettings) = action {
        repository.updateSettings(transform); eventChannel.send(UiEvent.Message("Réglages enregistrés."))
    }
    fun profileImage(uri: Uri, signature: Boolean) = action {
        val encoded = images.read(uri)
        repository.updateSettings { it.copy(profile = if (signature) it.profile.copy(signature = encoded) else it.profile.copy(companyLogo = encoded)) }
        eventChannel.send(UiEvent.Message(if (signature) "Signature enregistrée." else "Logo de l'entreprise enregistré."))
    }
    fun recalculate(from: LocalDate, through: LocalDate) = action {
        repository.recalculate(from, through); eventChannel.send(UiEvent.Message("La période a été recalculée avec les règles actuelles."))
    }
    fun erase() = action { repository.erase(); eventChannel.send(UiEvent.Message("L'historique a été effacé.")) }

    fun export(kind: String, period: ReportPeriod, share: Boolean = false) = action {
        val source = state.value.entries
        val profile = state.value.settings.profile
        val actualPeriod = if (kind == "XLSX") PointageWeek.containing(period.from).period else period
        require(actualPeriod.entries(source).isNotEmpty()) { "Ajoutez une activité ou choisissez une période contenant des données." }
        val name = ReportNames.base(profile, period.from, state.value.settings.preferences.weekStartsOnMonday)
        val document = when (kind) {
            "XLSX" -> documents.pointage(source, PointageWeek.containing(period.from), profile)
            "PDF" -> documents.prepare("$name.pdf", "application/pdf") { PdfExporter.write(it, source, period, profile) }
            "Recapitulatif" -> documents.prepare("$name.csv", "text/csv") { it.write(CsvExporter.summary(source, period)) }
            else -> documents.prepare("$name.csv", "text/csv") { it.write(CsvExporter.detailed(source, period)) }
        }
        if (share) documents.share(document) else requestSave(document)
    }
    fun backup() = action {
        val bytes = repository.backup()
        val document = documents.prepare("WorkTrack_Sauvegarde_${LocalDate.now()}.json", "application/json") { it.write(bytes) }
        requestSave(document)
    }
    private suspend fun requestSave(document: PendingDocument) {
        handle["pending_path"] = document.path; handle["pending_name"] = document.name; handle["pending_mime"] = document.mime
        eventChannel.send(UiEvent.CreateDocument(document))
    }
    fun finishDocument(uri: Uri?) {
        if (uri == null) { handle["pending_path"] = null; return }
        val path = handle.get<String>("pending_path") ?: return
        val document = PendingDocument(path, handle["pending_name"] ?: "WorkTrack", handle["pending_mime"] ?: "application/octet-stream")
        action { documents.save(document, uri); handle["pending_path"] = null; eventChannel.send(UiEvent.DocumentSaved(document)) }
    }
    fun share(document: PendingDocument) = action { documents.share(document) }
    suspend fun readBackup(uri: Uri) = documents.readBackup(uri)
    fun restore(backup: Backup, replace: Boolean) = action {
        repository.restore(backup, replace); eventChannel.send(UiEvent.Message("Sauvegarde ${if (replace) "restaurée" else "fusionnée"}."))
    }
    fun message(text: String) { viewModelScope.launch { eventChannel.send(UiEvent.Message(text)) } }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                eventChannel.send(UiEvent.Message(e.message ?: "L'opération n'a pas abouti."))
            } finally { busy.value = false }
        }
    }
}
