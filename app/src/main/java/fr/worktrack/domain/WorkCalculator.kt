package fr.worktrack.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Every calculation uses integer minutes and retains the untouched civil-time interval. */
object WorkCalculator {
    fun calculate(entry: WorkEntry, rules: WorkRules): WorkEntry {
        rules.validate()
        val duration = entry.rawMinutes
        require(duration in 1..1440) { "Une activité doit durer entre 1 minute et 24 heures." }
        require(entry.startTime.second == 0 && entry.startTime.nano == 0 && entry.endTime.second == 0 && entry.endTime.nano == 0) {
            "Les horaires doivent être saisis à la minute."
        }
        require(entry.classificationOverride == null || entry.type in listOf(ActivityType.OTHER_EFFECTIVE, ActivityType.OTHER_UNCOUNTED)) {
            "Utilisez une activité personnalisée pour corriger la classification."
        }
        require(entry.classificationOverride == null || entry.overrideReason.isNotBlank()) { "Indiquez le motif de la correction." }
        var effective = 0; var nonEffective = 0; var deducted = 0; var uncounted = 0
        when {
            entry.type.isTravel -> {
                val from = requireNotNull(entry.departure) { "Choisissez le type de lieu de départ." }
                val to = requireNotNull(entry.arrival) { "Choisissez le type de lieu d'arrivée." }
                if (entry.type == ActivityType.TRAVEL) {
                    require(from.type != PlaceType.CUSTOMER_SITE || from.name.isNotBlank()) { "Renseignez le site de départ." }
                    require(to.type != PlaceType.CUSTOMER_SITE || to.name.isNotBlank()) { "Renseignez le site d'arrivée." }
                }
                when {
                    from.type == PlaceType.HOME && to.type == PlaceType.CUSTOMER_SITE ||
                        from.type == PlaceType.CUSTOMER_SITE && to.type == PlaceType.HOME -> {
                        deducted = minOf(duration, rules.returnHomeDeductionMinutes)
                        nonEffective = duration - deducted
                    }
                    from.type == PlaceType.HOME || to.type == PlaceType.HOME ||
                        from.type == PlaceType.OFFICE && to.type == PlaceType.OFFICE -> uncounted = duration
                    else -> effective = duration
                }
            }
            entry.type.isBreak -> Unit
            entry.type in listOf(ActivityType.OTHER_EFFECTIVE, ActivityType.OTHER_UNCOUNTED) -> when (entry.classificationOverride ?:
                if (entry.type == ActivityType.OTHER_EFFECTIVE) rules.otherProfessionalClassification else TimeClassification.UNCOUNTED) {
                TimeClassification.EFFECTIVE -> effective = duration
                TimeClassification.NON_EFFECTIVE -> nonEffective = duration
                TimeClassification.UNCOUNTED -> uncounted = duration
            }
            else -> effective = duration
        }
        val step = rules.roundDownStepMinutes
        val roundedEffective = if (step == 0) effective else effective / step * step
        val roundedNonEffective = if (step == 0) nonEffective else nonEffective / step * step
        return entry.copy(effectiveMinutes = roundedEffective, nonEffectiveMinutes = roundedNonEffective,
            deductedMinutes = deducted, uncountedMinutes = uncounted,
            roundingMinutes = effective + nonEffective - roundedEffective - roundedNonEffective,
            appliedRules = rules, ruleVersion = 3)
    }

    /** End time before start means the next day only when explicitly chosen by the editor. */
    fun interval(date: LocalDate, startMinute: Int, endMinute: Int, nextDay: Boolean): Pair<LocalDateTime, LocalDateTime> {
        require(startMinute in 0..1439 && endMinute in 0..1439)
        val start = date.atStartOfDay().plusMinutes(startMinute.toLong())
        val end = date.plusDays(if (nextDay) 1 else 0).atStartOfDay().plusMinutes(endMinute.toLong())
        require(Duration.between(start, end).toMinutes() in 1..1440) { "Vérifiez les horaires et l'option « Fin le lendemain »." }
        return start to end
    }

    fun touches(entry: WorkEntry, date: LocalDate) = entry.startTime < date.plusDays(1).atStartOfDay() && entry.endTime > date.atStartOfDay()

    fun dates(entries: List<WorkEntry>): List<LocalDate> = entries.flatMap { entry ->
        generateSequence(entry.startTime.toLocalDate()) { it.plusDays(1) }
            .takeWhile { it.atStartOfDay() < entry.endTime }.toList()
    }.distinct().sorted()

    private fun intersectMinutes(start: LocalDateTime, minutes: Int, date: LocalDate): Int {
        if (minutes <= 0) return 0
        val a = maxOf(start, date.atStartOfDay())
        val b = minOf(start.plusMinutes(minutes.toLong()), date.plusDays(1).atStartOfDay())
        return maxOf(0, Duration.between(a, b).toMinutes().toInt())
    }

    private fun nightMinutes(start: LocalDateTime, minutes: Int, date: LocalDate, rules: WorkRules): Int {
        if (minutes <= 0) return 0
        val dayStart = date.atStartOfDay()
        val from = Duration.between(dayStart, start).toMinutes().coerceAtLeast(0)
        val through = Duration.between(dayStart, start.plusMinutes(minutes.toLong())).toMinutes().coerceAtMost(1440)
        if (from >= through) return 0
        fun overlap(a: Int, b: Int) = (minOf(through, b.toLong()) - maxOf(from, a.toLong())).coerceAtLeast(0).toInt()
        // Intersect at most two night windows, independent of the activity's duration.
        return if (rules.nightStartMinute < rules.nightEndMinute)
            overlap(rules.nightStartMinute, rules.nightEndMinute)
        else overlap(0, rules.nightEndMinute) + overlap(rules.nightStartMinute, 1440)
    }

    fun summarize(date: LocalDate, source: List<WorkEntry>, fallbackRules: WorkRules = WorkRules()): DaySummary {
        val entries = source.filter { touches(it, date) }.sortedBy { it.start }
        var effective = 0; var nonEffective = 0; var breaks = 0; var uncounted = 0
        var deductions = 0; var rounding = 0; var nightEffective = 0; var nightNonEffective = 0
        entries.forEach { e ->
            // A travel franchise belongs to the beginning of the trip; rounded minutes to its end.
            val countedStart = e.startTime.plusMinutes(e.deductedMinutes.toLong())
            effective += intersectMinutes(countedStart, e.effectiveMinutes, date)
            nonEffective += intersectMinutes(countedStart, e.nonEffectiveMinutes, date)
            deductions += intersectMinutes(e.startTime, e.deductedMinutes, date)
            uncounted += intersectMinutes(e.startTime, e.uncountedMinutes, date)
            if (e.type.isBreak) breaks += intersectMinutes(e.startTime, e.rawMinutes, date)
            rounding += intersectMinutes(countedStart.plusMinutes(e.countedMinutes.toLong()), e.roundingMinutes, date)
            nightEffective += nightMinutes(countedStart, e.effectiveMinutes, date, e.appliedRules)
            nightNonEffective += nightMinutes(countedStart, e.nonEffectiveMinutes, date, e.appliedRules)
        }
        val mealRule = entries.firstOrNull()?.appliedRules ?: fallbackRules
        val meals = if (entries.isNotEmpty() && effective >= mealRule.mealThresholdMinutes) mealRule.maxAutomaticMealsPerDay else 0
        return DaySummary(date, effective, nonEffective, breaks, uncounted, deductions, rounding,
            nightEffective, nightNonEffective, meals, entries.size)
    }

    fun summaries(source: List<WorkEntry>): List<DaySummary> {
        // Group civil-day slices once, rather than scanning the entire history for every day.
        val byDay = sortedMapOf<LocalDate, MutableList<WorkEntry>>()
        source.forEach { entry ->
            var date = entry.startTime.toLocalDate()
            while (date.atStartOfDay() < entry.endTime) {
                byDay.getOrPut(date) { mutableListOf() }.add(entry)
                date = date.plusDays(1)
            }
        }
        return byDay.map { (date, entries) -> summarize(date, entries) }
    }

    fun overlapping(entry: WorkEntry, source: List<WorkEntry>): List<WorkEntry> = source.filter {
        it.id != entry.id && entry.startTime < it.endTime && it.startTime < entry.endTime
    }

    fun hasOverlap(source: List<WorkEntry>): Boolean {
        var previousEnd: LocalDateTime? = null
        source.sortedBy { it.start }.forEach { entry ->
            if (previousEnd != null && entry.startTime < previousEnd) return true
            previousEnd = maxOf(previousEnd ?: entry.endTime, entry.endTime)
        }
        return false
    }
}
