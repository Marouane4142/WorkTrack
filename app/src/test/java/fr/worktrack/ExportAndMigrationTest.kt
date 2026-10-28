package fr.worktrack

import fr.worktrack.data.EntryCodec
import fr.worktrack.domain.*
import fr.worktrack.export.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ExportAndMigrationTest {
    private val date = LocalDate.of(2026, 10, 5)
    private val period = ReportPeriod(date, date)
    private fun work() = WorkCalculator.calculate(WorkEntry(start = "2026-10-05T08:00", end = "2026-10-05T12:00",
        caseNumber = "00042", location = "Site;\"test\"", notes = "Une ligne\nUne autre"), WorkRules())
    @Test fun dateFilterClipsMidnightWorkWithoutLosingOriginalTrace() {
        val e = WorkCalculator.calculate(work().copy(start = "2026-10-04T23:00", end = "2026-10-05T02:00"), WorkRules())
        assertEquals(120, period.summaries(listOf(e)).single().effectiveMinutes)
        assertEquals("2026-10-04T23:00", e.start)
        assertEquals(180, e.rawMinutes)
    }
    @Test fun pointageOffersOnlyWorkbookAndPdfWithTheSameMondayToFridayPeriod() {
        assertEquals(listOf("xlsx", "pdf"), PointageFormat.entries.map { it.extension })
        val week = PointageWeek.containing(LocalDate.of(2026, 10, 11))
        assertEquals(date, week.monday); assertEquals(date.plusDays(4), week.friday)
    }
    @Test fun csharpPayloadMigrationRetainsIdsStringsRawTimesAndRecordedTotals() {
        val old = """{"Id":"stable-id","Start":"2026-10-05T07:15:00","End":"2026-10-05T09:15:00","Type":2,"CaseNumber":"0007A","EffectiveMinutes":0,"NonEffectiveMinutes":75,"DeductedMinutes":45,"UncountedMinutes":0,"AppliedReturnDeductionMinutes":45,"AppliedMealThresholdMinutes":240,"AppliedMaxAutomaticMealsPerDay":1,"RuleVersion":1} """
        val entry = EntryCodec.decode(old)
        assertEquals("stable-id", entry.id); assertEquals("0007A", entry.caseNumber)
        assertEquals(120, entry.rawMinutes); assertEquals(75, entry.nonEffectiveMinutes)
        // The codec preserves the snapshot; the repository applies versioned rule upgrades.
        assertEquals(75, WorkCalculator.calculate(entry, WorkRules()).nonEffectiveMinutes)
        assertEquals(75, EntryCodec.decode(EntryCodec.encode(entry)).nonEffectiveMinutes)
    }
    @Test fun legacyCustomRulesAreImported() {
        val rule = EntryCodec.decodeRules("""{"ReturnHomeDeductionMinutes":60,"MealThresholdMinutes":300,"MaxAutomaticMealsPerDay":2}""")
        assertEquals(60, rule.returnHomeDeductionMinutes); assertEquals(300, rule.mealThresholdMinutes)
    }
    @Test fun backupRoundTripPreservesAllApplicationData() {
        val backup = Backup(entries = listOf(work()), rules = WorkRules(), preferences = Preferences(Appearance.DARK), profile = Profile("Jean"),
            cases = listOf(BusinessCase("00042")), audit = listOf(AuditEvent(action = "Ajout", description = "Test", after = work())))
        val restored = EntryCodec.json.decodeFromString<Backup>(EntryCodec.json.encodeToString(backup))
        BackupValidator.validate(restored)
        assertEquals(backup, restored)
    }
    @Test fun malformedBackupAndDuplicateIdsAreRejected() {
        val backup = Backup(entries = listOf(work(), work().copy(id = work().id)), rules = WorkRules(), preferences = Preferences(), profile = Profile())
        val e = work()
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(backup.copy(entries = listOf(e, e))) }
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(backup.copy(entries = listOf(e.copy(effectiveMinutes = 900)))) }
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(backup.copy(format = "Other")) }
    }
}
