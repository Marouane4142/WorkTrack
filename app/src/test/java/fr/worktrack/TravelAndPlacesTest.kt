package fr.worktrack

import fr.worktrack.data.EntryCodec
import fr.worktrack.domain.*
import fr.worktrack.export.ReportNames
import fr.worktrack.ui.EditorDraft
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TravelAndPlacesTest {
    private fun trip(from: PlaceType, to: PlaceType, minutes: Int = 120) = WorkEntry(start = "2026-10-05T08:00",
        end = LocalDateTime.parse("2026-10-05T08:00").plusMinutes(minutes.toLong()).toString(), type = ActivityType.TRAVEL,
        originType = from, destinationType = to, origin = "Site India", destination = "Leroy Merlin")

    @Test fun typedRoutesCalculateAllNinePlaceCombinations() {
        PlaceType.entries.forEach { from -> PlaceType.entries.forEach { to ->
            val e = WorkCalculator.calculate(trip(from, to), WorkRules())
            val homeSite = setOf(from, to) == setOf(PlaceType.HOME, PlaceType.CUSTOMER_SITE)
            val professional = from != PlaceType.HOME && to != PlaceType.HOME && !(from == PlaceType.OFFICE && to == PlaceType.OFFICE)
            assertEquals("$from → $to", if (professional) 120 else 0, e.effectiveMinutes)
            assertEquals("$from → $to", if (homeSite) 75 else 0, e.nonEffectiveMinutes)
            assertEquals(120, e.rawMinutes)
        } }
    }
    @Test fun bothDirectionsHandleShortAndLongTripsWithoutNegativeTotals() {
        listOf(30 to 0, 90 to 45, 120 to 75).forEach { (minutes, expected) ->
            listOf(PlaceType.HOME to PlaceType.CUSTOMER_SITE, PlaceType.CUSTOMER_SITE to PlaceType.HOME).forEach { (from, to) ->
                assertEquals(expected, WorkCalculator.calculate(trip(from, to, minutes), WorkRules()).nonEffectiveMinutes)
            }
        }
    }
    @Test fun genericTravelRequiresTypesAndRealSiteNames() {
        val e = trip(PlaceType.HOME, PlaceType.CUSTOMER_SITE)
        listOf(e.copy(originType = null), e.copy(destinationType = null), e.copy(destination = "")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { WorkCalculator.calculate(invalid, WorkRules()) }
        }
        val blank = EditorDraft(date = "2026-10-05", type = ActivityType.TRAVEL, originType = PlaceType.HOME, destinationType = PlaceType.CUSTOMER_SITE).entry()
        assertThrows(IllegalArgumentException::class.java) { WorkCalculator.calculate(blank, WorkRules()) }
    }
    @Test fun placeMemorySkipsPausesFutureActivitiesAndOngoingActivities() {
        val office = WorkEntry(start = "2026-10-05T08:00", end = "2026-10-05T09:00", type = ActivityType.OFFICE_WORK)
        val site = office.copy(id = "site", start = "2026-10-05T09:00", end = "2026-10-05T11:00", type = ActivityType.CUSTOMER_INTERVENTION, location = "Site India")
        val pause = office.copy(id = "pause", start = "2026-10-05T11:00", end = "2026-10-05T11:30", type = ActivityType.LUNCH_BREAK)
        val ongoing = site.copy(id = "ongoing", start = "2026-10-05T10:30", end = "2026-10-05T12:30", location = "Autre site")
        val future = site.copy(id = "future", start = "2026-10-05T13:00", end = "2026-10-05T14:00", location = "Site futur")
        assertEquals(KnownPlace(PlaceType.CUSTOMER_SITE, "Site India"), PlaceMemory.before(listOf(office, site, pause, ongoing, future), LocalDateTime.parse("2026-10-05T12:00")))
        assertEquals(PlaceType.OFFICE, PlaceMemory.before(listOf(office, site), LocalDateTime.parse("2026-10-05T11:00"), site.id)?.type)
    }
    @Test fun travelArrivalBecomesNextDepartureEvenAcrossDates() {
        val e = trip(PlaceType.CUSTOMER_SITE, PlaceType.HOME)
        assertEquals(KnownPlace(PlaceType.HOME, "Leroy Merlin"), PlaceMemory.before(listOf(e), LocalDateTime.parse("2026-10-06T08:00")))
        assertNull(PlaceMemory.before(listOf(e), LocalDateTime.parse("2026-10-05T07:00")))
    }
    @Test fun draftsRejectReversedSameDayIntervalsAndKeepOvernightExplicit() {
        val draft = EditorDraft(date = "2026-10-05", startMinute = 1380, endMinute = 60)
        assertThrows(IllegalArgumentException::class.java) { draft.entry() }
        assertEquals(120, draft.copy(nextDay = true).entry().rawMinutes)
        assertThrows(IllegalArgumentException::class.java) { draft.copy(startMinute = 60).entry() }
    }
    @Test fun oldProfileLabelsAreIgnoredAndTypedTravelSurvivesJson() {
        val profile = EntryCodec.json.decodeFromString<Profile>("""{"firstName":"Jean","home":"Chez moi","office":"Siège","employeeId":"42","role":"Technicien"}""")
        assertEquals("Domicile", profile.home); assertEquals("Bureau", profile.office)
        val e = WorkCalculator.calculate(trip(PlaceType.CUSTOMER_SITE, PlaceType.OFFICE), WorkRules())
        assertEquals(e, EntryCodec.decode(EntryCodec.encode(e)))
        assertEquals("Site India → Bureau", e.title)
    }
    @Test fun exportedFileNamesUseLastNameFirstNameAndFirstWeekDay() {
        val profile = Profile("Jean", "Dupont")
        assertEquals("Feuilles_heures_hebdomadaire_DUPONT JEAN_2026-10-05", ReportNames.base(profile, LocalDate.of(2026, 10, 9)))
        assertEquals("Feuilles_heures_hebdomadaire_DUPONT JEAN_2026-10-04", ReportNames.base(profile, LocalDate.of(2026, 10, 9), false))
        assertFalse(ReportNames.base(profile.copy(lastName = "Dupont/Paris"), LocalDate.of(2026, 10, 9)).contains('/'))
    }
    @Test fun portableProfileImagesSurviveCompleteBackup() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3uoAAAAASUVORK5CYII="
        val backup = Backup(entries = listOf(WorkCalculator.calculate(trip(PlaceType.HOME, PlaceType.CUSTOMER_SITE), WorkRules())),
            rules = WorkRules(), preferences = Preferences(), profile = Profile(companyLogo = png, signature = png))
        val restored = EntryCodec.json.decodeFromString<Backup>(EntryCodec.json.encodeToString(backup))
        BackupValidator.validate(restored); assertEquals(backup, restored)
    }
    @Test fun historicalRoutesRecoverIndividualSiteNames() {
        val legacy = WorkEntry(start = "2026-10-05T08:00", end = "2026-10-05T09:00", type = ActivityType.CUSTOMER_TO_CUSTOMER,
            location = "Site Hotel → Site India")
        assertEquals("Site Hotel", legacy.departure?.name)
        assertEquals("Site India", legacy.arrival?.name)
        val old = EntryCodec.decode("""{"start":"2026-10-05T08:00","end":"2026-10-05T10:00","type":"HOME_TO_CUSTOMER","uncountedMinutes":120}""")
        assertTrue(old.ruleVersion < 3)
    }
    @Test fun interventionSiteRequiresThePrecedingCompletedTripToEndAtAClientSite() {
        val siteTrip = trip(PlaceType.OFFICE, PlaceType.CUSTOMER_SITE).copy(destination = "Site India")
        val start = LocalDateTime.parse("2026-10-05T10:00")
        assertEquals("Site India", PlaceMemory.interventionSiteBefore(listOf(siteTrip), start))
        assertNull(PlaceMemory.interventionSiteBefore(listOf(siteTrip), start.minusMinutes(1)))
        assertNull(PlaceMemory.interventionSiteBefore(listOf(siteTrip), start, siteTrip.id))
        assertNull(PlaceMemory.interventionSiteBefore(listOf(siteTrip.copy(destinationType = PlaceType.OFFICE)), start))
        val work = WorkEntry(start = "2026-10-05T10:00", end = "2026-10-05T11:00", type = ActivityType.OFFICE_WORK)
        assertNull(PlaceMemory.interventionSiteBefore(listOf(siteTrip, work), start.plusHours(1)))
        val legacy = siteTrip.copy(type = ActivityType.HOME_TO_CUSTOMER, originType = null, destinationType = null)
        assertEquals("Site India", PlaceMemory.interventionSiteBefore(listOf(legacy), start))
        val edited = EditorDraft.from(work.copy(type = ActivityType.CUSTOMER_INTERVENTION, location = "Site corrigé"))
        assertFalse(edited.locationAutomatic); assertEquals("Site corrigé", edited.location)
    }
}
