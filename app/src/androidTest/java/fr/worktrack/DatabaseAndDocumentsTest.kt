package fr.worktrack

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.worktrack.data.*
import fr.worktrack.domain.*
import fr.worktrack.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseAndDocumentsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sqliteVersionOneMigratesToRoomWithoutLosingRecordedData() = runBlocking {
        val name = "worktrack-migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE WorkEntries (Id TEXT PRIMARY KEY NOT NULL, EntryDate TEXT NOT NULL, Payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE Settings (SettingKey TEXT PRIMARY KEY NOT NULL, SettingValue TEXT NOT NULL)")
            val payload = """{"Id":"legacy","Start":"2026-10-05T08:00:00","End":"2026-10-05T10:00:00","Type":2,"CaseNumber":"00012","UncountedMinutes":120,"AppliedReturnDeductionMinutes":45,"AppliedMealThresholdMinutes":240,"AppliedMaxAutomaticMealsPerDay":1}"""
            db.execSQL("INSERT INTO WorkEntries VALUES (?, ?, ?)", arrayOf("legacy", "2026-10-05", payload))
            db.execSQL("INSERT INTO Settings VALUES ('WorkRules', ?)", arrayOf("""{"ReturnHomeDeductionMinutes":60,"MealThresholdMinutes":300,"MaxAutomaticMealsPerDay":1}"""))
            db.version = 1
        }
        val migrated = Room.databaseBuilder(context, WorkDatabase::class.java, name).addMigrations(WorkDatabase.FROM_DOTNET).build()
        try {
            val e = EntryCodec.decode(migrated.dao().entries().single().payload)
            assertEquals("legacy", e.id); assertEquals("00012", e.caseNumber)
            assertEquals(120, e.uncountedMinutes); assertEquals(120, e.rawMinutes)
            assertNotNull(migrated.dao().legacySetting("WorkRules"))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun repositoryPersistsRulesSnapshotsAndBackupRestoresAtomically() = runBlocking {
        val name = "worktrack-repository-test-${UUID.randomUUID()}.db"
        val preferenceFile = File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferenceFile }))
        val database = Room.databaseBuilder(context, WorkDatabase::class.java, name).build()
        val repository = LocalWorkRepository(database, preferences)
        try {
            repository.initialize()
            val oldTrip = WorkEntry(id = "old-trip", start = "2026-10-06T08:00", end = "2026-10-06T10:00", type = ActivityType.HOME_TO_CUSTOMER,
                uncountedMinutes = 120, appliedRules = WorkRules(returnHomeDeductionMinutes = 60), ruleVersion = 2, location = "Site India")
            database.dao().upsertEntry(EntryEntity(oldTrip.id, oldTrip.start, oldTrip.end, oldTrip.caseNumber, EntryCodec.encode(oldTrip)))
            repository.initialize()
            val corrected = repository.entries.first().single()
            assertEquals(120, corrected.rawMinutes); assertEquals(60, corrected.nonEffectiveMinutes); assertEquals(60, corrected.deductedMinutes)
            assertEquals(oldTrip.start, corrected.start); assertEquals(oldTrip.end, corrected.end)
            val auditCount = repository.audit.first().count { it.action == "Règle de trajet" }
            repository.initialize()
            assertEquals(auditCount, repository.audit.first().count { it.action == "Règle de trajet" })
            repository.delete(oldTrip.id)
            val original = WorkEntry(id = "persisted", start = "2026-10-05T08:00", end = "2026-10-05T12:00", caseNumber = "00042")
            repository.save(original)
            repository.updateSettings { it.copy(rules = it.rules.copy(mealThresholdMinutes = 300, returnHomeDeductionMinutes = 60)) }
            val saved = repository.entries.first().single()
            assertEquals(240, saved.appliedRules.mealThresholdMinutes)
            assertEquals(1, WorkCalculator.summarize(LocalDate.of(2026, 10, 5), listOf(saved)).mealCount)
            repository.save(original.copy(notes = "Correction de note"))
            assertEquals(240, repository.entries.first().single().appliedRules.mealThresholdMinutes)
            val backup = EntryCodec.json.decodeFromString<Backup>(repository.backup().toString(Charsets.UTF_8))
            repository.delete("persisted")
            repository.restore(backup, true)
            assertEquals("00042", repository.entries.first().single().caseNumber)
            assertEquals(300, repository.settings.first().rules.mealThresholdMinutes)
            assertTrue(repository.audit.first().any { it.action == "Restauration" })
            repository.recalculate(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5))
            assertEquals(300, repository.entries.first().single().appliedRules.mealThresholdMinutes)
        } finally { database.close(); scope.cancel(); context.deleteDatabase(name); preferenceFile.delete() }
    }
    @Test fun weeklyPdfKeepsFiveA4PagesEvenWhenFourDaysAreEmpty() {
        val entries = (0..0).map { index ->
            val date = LocalDate.of(2026, 10, 5).plusDays(index.toLong())
            WorkCalculator.calculate(WorkEntry(start = "${date}T08:00", end = "${date}T12:00", location = "Un site client avec un nom long ".repeat(4),
                caseNumber = "00-$index", notes = "Notes d'intervention ".repeat(15)), WorkRules())
        }
        val file = File(context.cacheDir, "test-pdf-${UUID.randomUUID()}.pdf")
        try {
            writePointagePdf(file, entries, Profile("Test", "Technicien", "Entreprise"))
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { pdf ->
                    assertEquals(5, pdf.pageCount)
                    repeat(5) { index -> pdf.openPage(index).use { page -> assertEquals(595, page.width); assertEquals(842, page.height) } }
                }
            }
        } finally { file.delete() }
    }
    @Test fun importedImagesArePortableAndAppearOnEveryPointagePdfPage() = runBlocking {
        fun image(color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(120, 40, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
            }
        }
        val input = File(context.cacheDir, "image-test-${UUID.randomUUID()}.png")
        val file = File(context.cacheDir, "image-pdf-test-${UUID.randomUUID()}.pdf")
        try {
            input.writeBytes(image(android.graphics.Color.GREEN))
            val imported = ImageImporter(context).read(android.net.Uri.fromFile(input))
            val profile = Profile("Jean", "Dupont", "Entreprise", imported, RasterImages.normalize(image(android.graphics.Color.BLUE)))
            val backup = Backup(entries = emptyList(), rules = WorkRules(), preferences = Preferences(), profile = profile)
            val restored = EntryCodec.json.decodeFromString<Backup>(EntryCodec.json.encodeToString(Backup.serializer(), backup))
            BackupValidator.validate(restored); assertEquals(profile, restored.profile)
            val entries = (0..4).map { index ->
                val date = LocalDate.of(2026, 10, 5).plusDays(index.toLong())
                WorkCalculator.calculate(WorkEntry(start = "${date}T08:00", end = "${date}T12:00", location = "Site India", notes = "Notes à conserver ".repeat(8)), WorkRules())
            }
            writePointagePdf(file, entries, restored.profile)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { pdf ->
                    assertEquals(5, pdf.pageCount)
                    repeat(pdf.pageCount) { index -> pdf.openPage(index).use { page ->
                        assertEquals(595, page.width); assertEquals(842, page.height)
                        val bitmap = android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        fun colored(top: Int, bottom: Int, green: Boolean): Int {
                            var count = 0
                            for (y in top until bottom) for (x in 50 until 350) {
                                val pixel = bitmap.getPixel(x, y)
                                if (android.graphics.Color.red(pixel) < 5 &&
                                    (if (green) android.graphics.Color.green(pixel) > 245 && android.graphics.Color.blue(pixel) < 5
                                    else android.graphics.Color.blue(pixel) > 245 && android.graphics.Color.green(pixel) < 5)) count++
                            }
                            return count
                        }
                        assertTrue("Logo présent sur la page $index", colored(90, 240, true) > 100)
                        assertTrue("Signature présente sur la page $index", colored(680, 790, false) > 100)
                        bitmap.recycle()
                    } }
                }
            }
        } finally { input.delete(); file.delete() }
    }
    @Test fun sharedDocumentPublishesExactRequestedFileName() = runBlocking {
        val name = "Feuilles_heures_hebdomadaire_DUPONT JEAN_2026-10-05.xlsx"
        val service = DocumentService(context)
        val document = service.prepare(name, XlsxExporter.MIME) { output -> context.assets.open(XlsxExporter.TEMPLATE).use { it.copyTo(output) } }
        val file = File(document.path)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(name, it.getString(0))
            }
        } finally { file.delete(); file.parentFile?.delete() }
    }
    private fun writePointagePdf(file: File, entries: List<WorkEntry>, profile: Profile) {
        val workbook = java.io.ByteArrayOutputStream()
        context.assets.open(XlsxExporter.TEMPLATE).use { XlsxExporter.write(workbook, it, entries, PointageWeek(LocalDate.of(2026, 10, 5)), profile) }
        file.outputStream().use { PointagePdfExporter.write(it, workbook.toByteArray()) }
    }
}
