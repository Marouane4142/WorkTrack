package fr.worktrack

import fr.worktrack.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WorkCalculatorTest {
    private val date = LocalDate.of(2026, 10, 5)
    private fun entry(type: ActivityType, minutes: Int, rules: WorkRules = WorkRules(), start: String = "2026-10-05T08:00"): WorkEntry {
        val begin = LocalDateTime.parse(start)
        return WorkCalculator.calculate(WorkEntry(start = begin.toString(), end = begin.plusMinutes(minutes.toLong()).toString(), type = type), rules)
    }
    @Test fun outboundClientTripDeductsFranchiseAndPreservesRawTime() {
        val e = entry(ActivityType.HOME_TO_CUSTOMER, 120)
        assertEquals(120, e.rawMinutes); assertEquals(0, e.uncountedMinutes)
        assertEquals(0, e.effectiveMinutes); assertEquals(75, e.nonEffectiveMinutes); assertEquals(45, e.deductedMinutes)
    }
    @Test fun returnCasesApplyFranchiseOncePerTrip() {
        listOf(30 to 0, 45 to 0, 90 to 45, 120 to 75, 195 to 150).forEach { (raw, expected) ->
            val e = entry(ActivityType.CUSTOMER_TO_HOME, raw)
            assertEquals(expected, e.nonEffectiveMinutes); assertEquals(minOf(raw, 45), e.deductedMinutes)
            assertEquals(raw, e.rawMinutes); assertEquals(0, e.effectiveMinutes)
        }
    }
    @Test fun customizedFranchiseAffectsBothHomeSiteDirections() {
        val rules = WorkRules(returnHomeDeductionMinutes = 60)
        assertEquals(60, entry(ActivityType.CUSTOMER_TO_HOME, 120, rules).nonEffectiveMinutes)
        assertEquals(60, entry(ActivityType.HOME_TO_CUSTOMER, 120, rules).nonEffectiveMinutes)
        assertEquals(120, entry(ActivityType.CUSTOMER_TO_CUSTOMER, 120, rules).effectiveMinutes)
    }
    @Test fun homeOfficeTripsAreExcludedBothWays() {
        listOf(ActivityType.HOME_TO_OFFICE, ActivityType.OFFICE_TO_HOME).forEach { type ->
            val e = entry(type, 120); assertEquals(120, e.uncountedMinutes); assertEquals(0, e.countedMinutes)
        }
    }
    @Test fun officeSiteAndSiteSiteAreFullyEffective() {
        listOf(ActivityType.OFFICE_TO_CUSTOMER, ActivityType.CUSTOMER_TO_OFFICE, ActivityType.CUSTOMER_TO_CUSTOMER).forEach { type ->
            val e = entry(type, 60); assertEquals(60, e.effectiveMinutes); assertEquals(0, e.deductedMinutes)
        }
    }
    @Test fun pausesAreExcludedButSummarized() {
        val source = listOf(ActivityType.LUNCH_BREAK, ActivityType.MORNING_BREAK, ActivityType.AFTERNOON_BREAK).map { entry(it, 15) }
        assertEquals(45, WorkCalculator.summarize(date, source).breakMinutes)
        assertEquals(0, WorkCalculator.summarize(date, source).countedMinutes)
    }
    @Test fun mealThresholdUsesEffectiveTimeOnly() {
        assertEquals(0, WorkCalculator.summarize(date, listOf(entry(ActivityType.OFFICE_WORK, 225))).mealCount)
        assertEquals(1, WorkCalculator.summarize(date, listOf(entry(ActivityType.OFFICE_WORK, 240))).mealCount)
        assertEquals(1, WorkCalculator.summarize(date, listOf(entry(ActivityType.OFFICE_WORK, 600))).mealCount)
        assertEquals(0, WorkCalculator.summarize(date, listOf(entry(ActivityType.OFFICE_WORK, 225), entry(ActivityType.CUSTOMER_TO_HOME, 120))).mealCount)
    }
    @Test fun savedRulesAreStableWhenSettingsChange() {
        val e = entry(ActivityType.OFFICE_WORK, 240)
        assertEquals(1, WorkCalculator.summarize(date, listOf(e), WorkRules(mealThresholdMinutes = 300)).mealCount)
        assertEquals(240, e.appliedRules.mealThresholdMinutes)
        val saved = entry(ActivityType.CUSTOMER_TO_HOME, 120)
        WorkCalculator.calculate(saved, WorkRules(returnHomeDeductionMinutes = 90))
        assertEquals(75, saved.nonEffectiveMinutes)
    }
    @Test fun midnightSplitsCivilDatesAndNightHours() {
        val e = entry(ActivityType.CUSTOMER_INTERVENTION, 150, start = "2026-10-05T22:30")
        val days = WorkCalculator.summaries(listOf(e))
        assertEquals(90, days[0].effectiveMinutes); assertEquals(60, days[1].effectiveMinutes)
        assertEquals(150, days.sumOf { it.nightEffectiveMinutes }); assertEquals(150, e.rawMinutes)
    }
    @Test fun midnightReturnFranchiseIsNotRepeatedAtMidnight() {
        val e = entry(ActivityType.CUSTOMER_TO_HOME, 150, start = "2026-10-05T23:30")
        val days = WorkCalculator.summaries(listOf(e))
        assertEquals(listOf(30, 15), days.map { it.deductedMinutes })
        assertEquals(listOf(0, 105), days.map { it.nonEffectiveMinutes })
    }
    @Test fun mealsAreLimitedPerCivilDateAcrossMidnight() {
        val e = entry(ActivityType.CUSTOMER_INTERVENTION, 420, start = "2026-10-05T21:00")
        val days = WorkCalculator.summaries(listOf(e))
        assertEquals(listOf(0, 1), days.map { it.mealCount })
    }
    @Test fun explicitRoundingPreservesRawMinutes() {
        val e = entry(ActivityType.OFFICE_WORK, 67, WorkRules(roundDownStepMinutes = 15))
        assertEquals(67, e.rawMinutes); assertEquals(60, e.effectiveMinutes); assertEquals(7, e.roundingMinutes)
        assertEquals(67, entry(ActivityType.OFFICE_WORK, 67).effectiveMinutes)
    }
    @Test fun adjacentIntervalsAreValidButCrossMidnightOverlapsAreDetected() {
        val e = entry(ActivityType.OFFICE_WORK, 60)
        val adjacent = entry(ActivityType.OFFICE_WORK, 30, start = "2026-10-05T09:00")
        assertFalse(WorkCalculator.hasOverlap(listOf(e, adjacent)))
        val cross = entry(ActivityType.OFFICE_WORK, 180, start = "2026-10-05T23:00")
        val next = entry(ActivityType.OFFICE_WORK, 60, start = "2026-10-06T00:30")
        assertEquals(1, WorkCalculator.overlapping(next, listOf(cross)).size)
        assertTrue(WorkCalculator.hasOverlap(listOf(cross, next)))
    }
    @Test fun zeroOrNegativeDurationRequiresCorrectionInsteadOfCreatingADay() {
        assertThrows(IllegalArgumentException::class.java) { WorkCalculator.interval(date, 480, 480, false) }
        assertThrows(IllegalArgumentException::class.java) { WorkCalculator.interval(date, 1380, 60, false) }
        val (a, b) = WorkCalculator.interval(date, 1380, 60, true)
        assertEquals(120L, java.time.Duration.between(a, b).toMinutes())
    }
    @Test fun customClassificationRequiresDocumentedReason() {
        val e = WorkEntry(start = "2026-10-05T08:00", end = "2026-10-05T09:00", type = ActivityType.OTHER_EFFECTIVE, classificationOverride = TimeClassification.NON_EFFECTIVE)
        assertThrows(IllegalArgumentException::class.java) { WorkCalculator.calculate(e, WorkRules()) }
        assertEquals(60, WorkCalculator.calculate(e.copy(overrideReason = "Déplacement particulier"), WorkRules()).nonEffectiveMinutes)
    }
    @Test fun anonymizedReferenceWeekProducesCorrectDailyAndWeeklyTotals() {
        val entries = javaClass.getResourceAsStream("/reference-week.csv")!!.bufferedReader().useLines { rows -> rows.drop(1).map { row ->
            val cells = row.split(';')
            WorkCalculator.calculate(WorkEntry(start = "2026-10-${cells[0].padStart(2, '0')}T${cells[1]}",
                end = "2026-10-${cells[0].padStart(2, '0')}T${cells[2]}", type = ActivityType.valueOf(cells[3]), location = cells[4]), WorkRules())
        }.toList() }
        assertEquals(35, entries.size)
        assertFalse(WorkCalculator.hasOverlap(entries))
        val days = WorkCalculator.summaries(entries)
        assertEquals(listOf(405, 345, 375, 435, 285), days.map { it.effectiveMinutes })
        assertEquals(listOf(75, 225, 105, 45, 60), days.map { it.nonEffectiveMinutes })
        assertEquals(1845, days.sumOf { it.effectiveMinutes })
        assertEquals(510, days.sumOf { it.nonEffectiveMinutes })
        assertEquals(5, days.sumOf { it.mealCount })
    }

    @Test fun nightHoursMatchCivilMinuteReferenceAcrossCustomWindows() {
        val windows = listOf(1320 to 360, 480 to 1020, 1439 to 0, 0 to 1439, 540 to 480)
        windows.forEach { (nightStart, nightEnd) ->
            val rules = WorkRules(nightStartMinute = nightStart, nightEndMinute = nightEnd, roundDownStepMinutes = 15)
            listOf(0, 359, 360, 1319, 1320, 1439).forEach { startMinute ->
                listOf(1, 30, 91, 1440).forEach { raw ->
                    listOf(ActivityType.OFFICE_WORK, ActivityType.CUSTOMER_TO_HOME).forEach { type ->
                        val e = entry(type, raw, rules, date.atStartOfDay().plusMinutes(startMinute.toLong()).toString())
                        val countedStart = e.startTime.plusMinutes(e.deductedMinutes.toLong())
                        WorkCalculator.summaries(listOf(e)).forEach { day ->
                            val expected = (0 until e.countedMinutes).count { offset ->
                                val civilTime = countedStart.plusMinutes(offset.toLong())
                                val minute = civilTime.hour * 60 + civilTime.minute
                                civilTime.toLocalDate() == day.date &&
                                    (if (nightStart < nightEnd) minute in nightStart until nightEnd
                                    else minute >= nightStart || minute < nightEnd)
                            }
                            assertEquals("$type $nightStart/$nightEnd start=$startMinute duration=$raw date=${day.date}",
                                expected, day.nightEffectiveMinutes + day.nightNonEffectiveMinutes)
                        }
                    }
                }
            }
        }
    }
}
