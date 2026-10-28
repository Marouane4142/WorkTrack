package fr.worktrack.export

import fr.worktrack.domain.*
import java.time.LocalDate

data class ReportPeriod(val from: LocalDate, val through: LocalDate) {
    init { require(from <= through) { "La fin de période doit suivre le début." } }
    fun entries(source: List<WorkEntry>) = source.filter { it.startTime < through.plusDays(1).atStartOfDay() && it.endTime > from.atStartOfDay() }
    fun summaries(source: List<WorkEntry>) = WorkCalculator.summaries(entries(source)).filter { it.date in from..through }
}

enum class PointageFormat(val extension: String, val mime: String) {
    XLSX("xlsx", XlsxExporter.MIME),
    PDF("pdf", "application/pdf")
}
