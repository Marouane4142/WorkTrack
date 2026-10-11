package fr.worktrack.export

import fr.worktrack.domain.*
import java.time.Duration
import java.time.LocalDate

data class ReportPeriod(val from: LocalDate, val through: LocalDate, val caseNumber: String = "") {
    init { require(from <= through) { "La fin de période doit suivre le début." } }
    fun entries(source: List<WorkEntry>) = source.filter { e ->
        e.startTime < through.plusDays(1).atStartOfDay() && e.endTime > from.atStartOfDay() &&
            (caseNumber.isBlank() || e.caseNumber.equals(caseNumber, ignoreCase = true))
    }
    fun summaries(source: List<WorkEntry>) = WorkCalculator.summaries(entries(source)).filter { it.date in from..through }
}

object CsvExporter {
    fun detailed(source: List<WorkEntry>, period: ReportPeriod): ByteArray {
        val entries = period.entries(source).sortedBy { it.start }
        val meals = period.summaries(source).associate { it.date to it.mealCount }
        val writtenMeals = mutableSetOf<LocalDate>()
        val text = StringBuilder()
        row(text, listOf("Date", "Début", "Fin", "Date fin", "Durée brute (min)", "Durée du trajet complet (min)",
            "Type", "Départ", "Type de départ", "Destination", "Type de destination", "Lieu", "N° AFFAIRE", "Heures effectives",
            "Heures non effectives", "Franchise (min)", "Non comptabilisé (min)", "Pauses (min)", "Arrondi déduit (min)",
            "Repas (journée)", "Observations", "Motif de correction"))
        entries.forEach { e ->
            WorkCalculator.dates(listOf(e)).filter { it in period.from..period.through }.forEach { date ->
                val s = WorkCalculator.summarize(date, listOf(e))
                val start = maxOf(e.startTime, date.atStartOfDay())
                val end = minOf(e.endTime, date.plusDays(1).atStartOfDay())
                row(text, listOf(date.format(ShortDate), start.format(ClockFormat), end.format(ClockFormat),
                    end.toLocalDate().format(ShortDate), Duration.between(start, end).toMinutes().toString(),
                    if (e.type.isTravel) e.rawMinutes.toString() else "", e.type.displayLabel, e.departure?.label.orEmpty(), e.departure?.type?.label.orEmpty(), e.arrival?.label.orEmpty(), e.arrival?.type?.label.orEmpty(),
                    e.location, e.caseNumber, decimalHours(s.effectiveMinutes), decimalHours(s.nonEffectiveMinutes),
                    s.deductedMinutes.toString(), s.uncountedMinutes.toString(), s.breakMinutes.toString(), s.roundingMinutes.toString(),
                    if (writtenMeals.add(date)) (meals[date] ?: 0).toString() else "", e.notes, e.overrideReason))
            }
        }
        return bom(text)
    }

    fun summary(source: List<WorkEntry>, period: ReportPeriod): ByteArray {
        val text = StringBuilder()
        row(text, listOf("Date", "Heures effectives", "Heures non effectives", "Pauses (min)", "Non comptabilisé (min)",
            "Franchises (min)", "Arrondi déduit (min)", "Effectives de nuit", "Non effectives de nuit", "Paniers repas", "Activités"))
        period.summaries(source).forEach { s -> row(text, listOf(s.date.format(ShortDate), decimalHours(s.effectiveMinutes),
            decimalHours(s.nonEffectiveMinutes), s.breakMinutes.toString(), s.uncountedMinutes.toString(), s.deductedMinutes.toString(),
            s.roundingMinutes.toString(), decimalHours(s.nightEffectiveMinutes), decimalHours(s.nightNonEffectiveMinutes), s.mealCount.toString(), s.entryCount.toString())) }
        return bom(text)
    }

    /** Quoting alone does not prevent Excel formula execution in user-entered text. */
    private fun safeText(text: String): String = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$text" else text
    private fun row(builder: StringBuilder, cells: List<String>) {
        builder.append(cells.joinToString(";") { "\"${safeText(it).replace("\"", "\"\"")}\"" }).append("\r\n")
    }
    private fun bom(builder: StringBuilder) = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + builder.toString().toByteArray(Charsets.UTF_8)
}
