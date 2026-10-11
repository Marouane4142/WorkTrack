package fr.worktrack.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "work_entries", indices = [Index("start"), Index("caseNumber")])
data class EntryEntity(@PrimaryKey val id: String, val start: String, val end: String,
    val caseNumber: String, val payload: String)

@Entity(tableName = "business_cases")
data class CaseEntity(@PrimaryKey val number: String, val payload: String)

@Entity(tableName = "audit_events", indices = [Index("at")])
data class AuditEntity(@PrimaryKey val id: String, val at: String, val payload: String)

@Entity(tableName = "legacy_settings")
data class LegacySettingEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface WorkDao {
    @Query("SELECT * FROM work_entries ORDER BY start") fun observeEntries(): Flow<List<EntryEntity>>
    @Query("SELECT * FROM work_entries ORDER BY start") suspend fun entries(): List<EntryEntity>
    @Query("SELECT * FROM business_cases ORDER BY number") fun observeCases(): Flow<List<CaseEntity>>
    @Query("SELECT * FROM business_cases ORDER BY number") suspend fun cases(): List<CaseEntity>
    @Query("SELECT * FROM audit_events ORDER BY at DESC") fun observeAudit(): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_events ORDER BY at DESC") suspend fun audit(): List<AuditEntity>
    @Query("SELECT * FROM legacy_settings WHERE `key` = :key") suspend fun legacySetting(key: String): LegacySettingEntity?
    @Upsert suspend fun upsertEntry(entry: EntryEntity)
    @Upsert suspend fun upsertEntries(entries: List<EntryEntity>)
    @Upsert suspend fun upsertCase(entry: CaseEntity)
    @Upsert suspend fun upsertCases(entries: List<CaseEntity>)
    @Upsert suspend fun upsertAudit(entries: List<AuditEntity>)
    @Upsert suspend fun upsertLegacySetting(entry: LegacySettingEntity)
    @Query("DELETE FROM legacy_settings WHERE `key` = :key") suspend fun deleteLegacySetting(key: String)
    @Insert suspend fun insertAudit(entry: AuditEntity)
    @Query("DELETE FROM work_entries WHERE id = :id") suspend fun deleteEntry(id: String)
    @Query("DELETE FROM work_entries") suspend fun clearEntries()
    @Query("DELETE FROM business_cases") suspend fun clearCases()
    @Query("DELETE FROM audit_events") suspend fun clearAudit()
    @Query("DELETE FROM legacy_settings") suspend fun clearLegacySettings()
}

@Database(entities = [EntryEntity::class, CaseEntity::class, AuditEntity::class, LegacySettingEntity::class], version = 2, exportSchema = true)
abstract class WorkDatabase : RoomDatabase() {
    abstract fun dao(): WorkDao

    companion object {
        val FROM_DOTNET = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS work_entries (id TEXT NOT NULL PRIMARY KEY, start TEXT NOT NULL, end TEXT NOT NULL, caseNumber TEXT NOT NULL, payload TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_entries_start ON work_entries(start)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_entries_caseNumber ON work_entries(caseNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS business_cases (number TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audit_events (id TEXT NOT NULL PRIMARY KEY, at TEXT NOT NULL, payload TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_at ON audit_events(at)")
                db.execSQL("CREATE TABLE IF NOT EXISTS legacy_settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                db.query("SELECT Payload FROM WorkEntries").use { cursor ->
                    while (cursor.moveToNext()) {
                        val entry = EntryCodec.decode(cursor.getString(0))
                        require(entry.id.isNotBlank() && entry.rawMinutes > 0) { "Ancienne activité invalide : migration arrêtée pour préserver les données." }
                        db.execSQL("INSERT INTO work_entries VALUES (?, ?, ?, ?, ?)", arrayOf(entry.id, entry.start, entry.end, entry.caseNumber, EntryCodec.encode(entry)))
                    }
                }
                db.execSQL("INSERT INTO legacy_settings SELECT SettingKey, SettingValue FROM Settings")
                // Original C# tables remain intact until the user explicitly erases their history.
            }
        }
    }
}
