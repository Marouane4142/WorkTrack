package fr.worktrack.data

import androidx.room.withTransaction
import fr.worktrack.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface WorkRepository {
    val entries: Flow<List<WorkEntry>>
    val cases: Flow<List<BusinessCase>>
    val audit: Flow<List<AuditEvent>>
    val settings: Flow<AppSettings>
    suspend fun initialize()
    suspend fun save(entry: WorkEntry, allowOverlap: Boolean = false)
    suspend fun delete(id: String)
    suspend fun duplicateDay(from: LocalDate, to: LocalDate)
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun recalculate(from: LocalDate, through: LocalDate)
    suspend fun backup(): ByteArray
    suspend fun restore(backup: Backup, replace: Boolean)
    suspend fun erase()
}

@Singleton
class LocalWorkRepository @Inject constructor(private val database: WorkDatabase, private val store: SettingsStore) : WorkRepository {
    private val dao = database.dao()
    private val mutex = Mutex()
    override val entries = dao.observeEntries().map { rows -> rows.map { EntryCodec.decode(it.payload) } }
    override val cases = dao.observeCases().map { rows -> rows.map { EntryCodec.json.decodeFromString<BusinessCase>(it.payload) } }
    override val audit = dao.observeAudit().map { rows -> rows.map { EntryCodec.json.decodeFromString<AuditEvent>(it.payload) } }
    override val settings = store.settings

    override suspend fun initialize() = mutex.withLock {
        recoverSettings()
        val current = store.current()
        if (!current.legacyImported) {
            val legacy = dao.legacySetting("WorkRules")
            store.save(current.copy(rules = legacy?.let { EntryCodec.decodeRules(it.value) } ?: current.rules, legacyImported = true))
            database.withTransaction {
                dao.entries().map { EntryCodec.decode(it.payload) }.forEach { saveCase(it) }
            }
        }
        database.withTransaction { upgradeOutboundTravel() }
    }

    /** Apply the requested outbound rule once, retaining each trip's recorded franchise and raw times. */
    private suspend fun upgradeOutboundTravel() {
        dao.entries().map { EntryCodec.decode(it.payload) }.filter {
            it.ruleVersion < 3 && it.type.isTravel && it.departure?.type == PlaceType.HOME && it.arrival?.type == PlaceType.CUSTOMER_SITE
        }.forEach { original ->
            val revised = WorkCalculator.calculate(original.copy(updatedAt = Instant.now().toString()), original.appliedRules)
            dao.upsertEntry(entity(revised))
            dao.insertAudit(auditEntity(AuditEvent(action = "Règle de trajet", description = "Domicile → site : franchise puis temps non effectif.", before = original, after = revised)))
        }
    }

    private fun entity(e: WorkEntry) = EntryEntity(e.id, e.start, e.end, e.caseNumber, EntryCodec.encode(e))
    private fun auditEntity(e: AuditEvent) = AuditEntity(e.id, e.at, EntryCodec.json.encodeToString(e))
    private suspend fun saveCase(e: WorkEntry) {
        if (e.caseNumber.isNotBlank()) dao.upsertCase(CaseEntity(e.caseNumber,
            EntryCodec.json.encodeToString(BusinessCase(e.caseNumber, e.caseName, e.client, e.location))))
    }

    override suspend fun save(entry: WorkEntry, allowOverlap: Boolean) = mutex.withLock {
        val existing = dao.entries().map { EntryCodec.decode(it.payload) }
        val original = existing.firstOrNull { it.id == entry.id }
        val settings = store.current()
        // Editing an existing activity retains its recorded calculation settings.
        var applicableRules = original?.appliedRules ?: settings.rules
        val dayRule = existing.filter { it.id != entry.id && WorkCalculator.touches(it, entry.startTime.toLocalDate()) }.minByOrNull { it.start }
        if (dayRule != null) applicableRules = applicableRules.copy(mealThresholdMinutes = dayRule.appliedRules.mealThresholdMinutes,
            maxAutomaticMealsPerDay = dayRule.appliedRules.maxAutomaticMealsPerDay)
        val calculated = WorkCalculator.calculate(entry.copy(createdAt = original?.createdAt ?: entry.createdAt,
            updatedAt = Instant.now().toString()), applicableRules)
        require(allowOverlap || WorkCalculator.overlapping(calculated, existing).isEmpty()) { "Cette activité chevauche une autre plage horaire." }
        database.withTransaction {
            dao.upsertEntry(entity(calculated)); saveCase(calculated)
            dao.insertAudit(auditEntity(AuditEvent(action = if (original == null) "Ajout" else "Modification",
                description = "${calculated.startTime.toLocalDate()} · ${calculated.title}", before = original, after = calculated)))
        }
    }

    override suspend fun delete(id: String) = mutex.withLock {
        database.withTransaction {
            val original = dao.entries().firstOrNull { it.id == id }?.let { EntryCodec.decode(it.payload) }
            dao.deleteEntry(id)
            if (original != null) dao.insertAudit(auditEntity(AuditEvent(action = "Suppression",
                description = "${original.startTime.toLocalDate()} · ${original.title}", before = original)))
        }
    }

    override suspend fun duplicateDay(from: LocalDate, to: LocalDate) = mutex.withLock {
        require(from != to) { "Choisissez une autre date pour la copie." }
        val all = dao.entries().map { EntryCodec.decode(it.payload) }
        val source = all.filter { it.startTime.toLocalDate() == from }
        require(source.isNotEmpty()) { "Aucune activité ne commence à cette date." }
        val delta = java.time.temporal.ChronoUnit.DAYS.between(from, to)
        val settings = store.current()
        val dayRule = all.filter { WorkCalculator.touches(it, to) }.minByOrNull { it.start }?.appliedRules
        val copyRules = settings.rules.copy(mealThresholdMinutes = dayRule?.mealThresholdMinutes ?: settings.rules.mealThresholdMinutes,
            maxAutomaticMealsPerDay = dayRule?.maxAutomaticMealsPerDay ?: settings.rules.maxAutomaticMealsPerDay)
        val copies = source.map { WorkCalculator.calculate(it.copy(id = UUID.randomUUID().toString(),
            start = it.startTime.plusDays(delta).toString(), end = it.endTime.plusDays(delta).toString(),
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()), copyRules) }
        require(!WorkCalculator.hasOverlap(copies) && copies.none { WorkCalculator.overlapping(it, all).isNotEmpty() }) {
            "La copie créerait un chevauchement. Choisissez une journée libre."
        }
        database.withTransaction {
            dao.upsertEntries(copies.map(::entity)); copies.forEach { saveCase(it) }
            dao.insertAudit(auditEntity(AuditEvent(action = "Duplication", description = "${source.size} activités : $from → $to")))
        }
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        store.update(transform)
        dao.insertAudit(auditEntity(AuditEvent(action = "Réglages", description = "Profil, préférences ou règles actualisés. Historique conservé.")))
    }

    override suspend fun recalculate(from: LocalDate, through: LocalDate) = mutex.withLock {
        require(from <= through)
        val rules = store.current().rules
        database.withTransaction {
            dao.entries().map { EntryCodec.decode(it.payload) }
                .filter { it.startTime.toLocalDate() in from..through }.forEach { original ->
                    val revised = WorkCalculator.calculate(original.copy(updatedAt = Instant.now().toString()), rules)
                    dao.upsertEntry(entity(revised))
                    dao.insertAudit(auditEntity(AuditEvent(action = "Recalcul explicite", description = "${original.startTime.toLocalDate()} · ${original.title}", before = original, after = revised)))
                }
        }
    }

    override suspend fun backup(): ByteArray = mutex.withLock {
        database.withTransaction {
            val settings = store.current()
            EntryCodec.json.encodeToString(Backup(entries = dao.entries().map { EntryCodec.decode(it.payload) },
                rules = settings.rules, preferences = settings.preferences, profile = settings.profile,
                cases = dao.cases().map { EntryCodec.json.decodeFromString<BusinessCase>(it.payload) },
                audit = dao.audit().map { EntryCodec.json.decodeFromString<AuditEvent>(it.payload) })).toByteArray(Charsets.UTF_8)
        }
    }

    private suspend fun recoverSettings() {
        dao.legacySetting("pending_settings")?.let {
            store.save(EntryCodec.json.decodeFromString(it.value))
            dao.deleteLegacySetting("pending_settings")
        }
    }

    override suspend fun restore(backup: Backup, replace: Boolean) = mutex.withLock {
        BackupValidator.validate(backup)
        val existingIds = dao.entries().map { it.id }.toSet()
        val existingCases = if (replace) emptySet() else dao.cases().map { it.number }.toSet()
        database.withTransaction {
            if (replace) { dao.clearEntries(); dao.clearCases(); dao.clearAudit() }
            // Merge preserves local versions of matching stable IDs.
            val imported = if (replace) backup.entries else backup.entries.filter { it.id !in existingIds }
            dao.upsertEntries(imported.map(::entity))
            if (replace) {
                dao.upsertAudit(backup.audit.map(::auditEntity))
                dao.upsertLegacySetting(LegacySettingEntity("pending_settings", EntryCodec.json.encodeToString(
                    AppSettings(backup.rules, backup.preferences, backup.profile, true))))
            }
            imported.filter { it.caseNumber !in existingCases }.forEach { saveCase(it) }
            dao.upsertCases(backup.cases.filter { it.number !in existingCases }.map { CaseEntity(it.number, EntryCodec.json.encodeToString(it)) })
            dao.insertAudit(auditEntity(AuditEvent(action = "Restauration", description = "${imported.size} activités ${if (replace) "restaurées" else "fusionnées"}.")))
            upgradeOutboundTravel()
        }
        // A durable journal recovers settings if the app closes between Room and DataStore writes.
        recoverSettings()
    }

    override suspend fun erase() = mutex.withLock {
        database.withTransaction {
            dao.clearEntries(); dao.clearCases(); dao.clearAudit(); dao.clearLegacySettings()
            database.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS WorkEntries")
            database.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS Settings")
        }
    }
}
