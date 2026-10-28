package fr.worktrack.export

import fr.worktrack.domain.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/** The supplied form has exactly five civil days, always Monday to Friday. */
data class PointageWeek(val monday: LocalDate) {
    init { require(monday.dayOfWeek == DayOfWeek.MONDAY) }
    val friday: LocalDate get() = monday.plusDays(4)
    val number: Int get() = monday.get(WeekFields.ISO.weekOfWeekBasedYear())
    val period get() = ReportPeriod(monday, friday)
    val dates get() = (0L..4L).map(monday::plusDays)

    fun days(source: List<WorkEntry>): List<PointageDay> = dates.map { date ->
        PointageDay(date, source.filter { WorkCalculator.touches(it, date) }.sortedWith(compareBy({ it.start }, { it.end }, { it.id })))
    }

    companion object {
        val sheetNames = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi")
        fun containing(date: LocalDate) = PointageWeek(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
    }
}

data class PointageLine(val row: Int, val entry: WorkEntry, val date: LocalDate) {
    val start: LocalDateTime get() = maxOf(entry.startTime, date.atStartOfDay())
    val end: LocalDateTime get() = minOf(entry.endTime, date.plusDays(1).atStartOfDay())
    val summary get() = WorkCalculator.summarize(date, listOf(entry))
}

data class PointageDay(val date: LocalDate, val entries: List<WorkEntry>) {
    val summary get() = WorkCalculator.summarize(date, entries)

    /** Match the form's travel/work pairs without merging disjoint time intervals. */
    fun lines(): List<PointageLine> {
        val result = mutableListOf<PointageLine>()
        val assignedBreaks = mutableSetOf<Int>()
        val remaining = entries.filter { entry ->
            val pauseRow = when (entry.type) {
                ActivityType.MORNING_BREAK -> 13
                ActivityType.LUNCH_BREAK -> 14
                ActivityType.AFTERNOON_BREAK -> 15
                else -> null
            }
            if (pauseRow != null && assignedBreaks.add(pauseRow)) {
                result += PointageLine(pauseRow, entry, date)
                false
            } else true
        }
        val finalReturn = remaining.lastOrNull()?.takeIf { it.type.isTravel && it.arrival?.type == PlaceType.HOME }
        var cursor = 17
        remaining.forEach { entry ->
            val row = if (entry == finalReturn) 51 else {
                val wantsOdd = entry.type.isTravel
                if ((cursor % 2 == 1) != wantsOdd) cursor++
                cursor.also { cursor++ }
            }
            require(row <= (if (entry.type.isTravel) 51 else 50) && (row != 51 || entry == remaining.last())) {
                "Le ${date.format(ShortDate)} dépasse les lignes disponibles du modèle de pointage. Aucune activité n'a été supprimée. Adaptez les plages de cette journée avant de l'exporter."
            }
            result += PointageLine(row, entry, date)
        }
        return result.sortedBy { it.row }
    }
}
