package fr.worktrack.export

import android.graphics.*
import android.graphics.pdf.PdfDocument
import fr.worktrack.domain.*
import java.io.OutputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A4 landscape, searchable tables, repeated company headers and signature footers. */
object PdfExporter {
    fun write(destination: OutputStream, source: List<WorkEntry>, period: ReportPeriod, profile: Profile) {
        val document = PdfDocument()
        val logo = RasterImages.decode(profile.companyLogo)
        val signature = RasterImages.decode(profile.signature)
        try {
            val writer = TableWriter(document, profile, period, logo, signature)
            writer.table("DÉTAIL DES ACTIVITÉS", listOf(49f, 36f, 36f, 42f, 90f, 145f, 139f, 58f, 46f, 54f, 42f, 57f),
                listOf("Date", "Début", "Fin", "Brut", "Activité", "Départ / site", "Destination", "N° affaire", "Eff. (h)", "Non eff. (h)", "Franch. (min)", "Notes"))
            val entries = period.entries(source).sortedBy { it.start }
            if (entries.isEmpty()) writer.row(listOf("Aucune activité enregistrée sur cette période.") + List(11) { "" })
            entries.forEach { entry ->
                WorkCalculator.dates(listOf(entry)).filter { it in period.from..period.through }.forEach { date ->
                    val summary = WorkCalculator.summarize(date, listOf(entry))
                    val start = maxOf(entry.startTime, date.atStartOfDay())
                    val end = minOf(entry.endTime, date.plusDays(1).atStartOfDay())
                    val notes = buildList {
                        if (entry.notes.isNotBlank()) add(entry.notes)
                        if (entry.overrideReason.isNotBlank()) add("Correction : ${entry.overrideReason}")
                        if (summary.roundingMinutes > 0) add("Arrondi : ${summary.roundingMinutes} min")
                    }.joinToString("\n")
                    writer.row(listOf(date.format(ShortDate), start.format(ClockFormat), if (end.toLocalDate() != date) "24:00" else end.format(ClockFormat),
                        Duration.between(start, end).toMinutes().let { String.format(French, "%02d:%02d", it / 60, it % 60) }, entry.type.displayLabel,
                        if (entry.type.isTravel) entry.departure?.label.orEmpty() else if (entry.type == ActivityType.OFFICE_WORK) "Bureau" else entry.location,
                        if (entry.type.isTravel) entry.arrival?.label.orEmpty() else "", entry.caseNumber,
                        decimalHours(summary.effectiveMinutes), decimalHours(summary.nonEffectiveMinutes), summary.deductedMinutes.toString(), notes))
                }
            }
            val summaries = period.summaries(source)
            writer.table("RÉCAPITULATIF JOURNALIER", listOf(94f, 89f, 99f, 87f, 88f, 87f, 91f, 92f, 67f),
                listOf("Date", "Effectives (h)", "Non effectives (h)", "Pauses (h)", "Hors décompte (h)", "Franchise (h)", "Nuit eff. (h)", "Nuit non eff. (h)", "Repas"))
            summaries.forEach { s -> writer.row(listOf(s.date.format(ShortDate), decimalHours(s.effectiveMinutes), decimalHours(s.nonEffectiveMinutes),
                decimalHours(s.breakMinutes), decimalHours(s.uncountedMinutes + s.roundingMinutes), decimalHours(s.deductedMinutes),
                decimalHours(s.nightEffectiveMinutes), decimalHours(s.nightNonEffectiveMinutes), s.mealCount.toString())) }
            writer.row(listOf("TOTAL", decimalHours(summaries.sumOf { it.effectiveMinutes }), decimalHours(summaries.sumOf { it.nonEffectiveMinutes }),
                decimalHours(summaries.sumOf { it.breakMinutes }), decimalHours(summaries.sumOf { it.uncountedMinutes + it.roundingMinutes }),
                decimalHours(summaries.sumOf { it.deductedMinutes }), decimalHours(summaries.sumOf { it.nightEffectiveMinutes }),
                decimalHours(summaries.sumOf { it.nightNonEffectiveMinutes }), summaries.sumOf { it.mealCount }.toString()), total = true)
            writer.finish()
            document.writeTo(destination)
        } finally { document.close(); logo?.recycle(); signature?.recycle() }
    }

    private class TableWriter(val document: PdfDocument, val profile: Profile, val period: ReportPeriod, val logo: Bitmap?, val signature: Bitmap?) {
        val navy = Color.rgb(20, 43, 69)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 7.6f; typeface = Typeface.DEFAULT }
        val grid = Paint().apply { color = Color.rgb(210, 219, 228); strokeWidth = 0.45f; style = Paint.Style.STROKE }
        val fill = Paint().apply { color = Color.rgb(243, 247, 250) }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var page: PdfDocument.Page? = null
        val canvas get() = page!!.canvas
        var pageNumber = 0
        var y = 0f
        var title = ""
        var widths = emptyList<Float>()
        var headings = emptyList<String>()
        var alternate = false
        val bottom = 510f
        val lineHeight = 9.4f

        fun table(label: String, columns: List<Float>, headers: List<String>) {
            require(columns.sum() == 794f && columns.size == headers.size)
            title = label; widths = columns; headings = headers
            if (page == null || y + 85f > bottom) newPage() else { y += 12f; tableHeader() }
        }
        fun newPage() {
            finish()
            page = document.startPage(PdfDocument.PageInfo.Builder(842, 595, ++pageNumber).create())
            logo?.let { fit(it, RectF(24f, 18f, 96f, 64f)) }
            val left = if (logo == null) 24f else 108f
            text.textSize = 14f; text.typeface = Typeface.DEFAULT_BOLD; text.color = navy
            canvas.drawText("FEUILLE D'HEURES HEBDOMADAIRE", left, 31f, text)
            text.textSize = 9f; text.typeface = Typeface.DEFAULT
            canvas.drawText("Du ${period.from.format(ShortDate)} au ${period.through.format(ShortDate)}", left, 46f, text)
            y = 59f
            wrap("${profile.company.ifBlank { "Entreprise : __________________" }}    •    ${profile.fullName.ifBlank { "Nom : __________________" }}", 818f - left).forEach {
                canvas.drawText(it, left, y, text); y += 11f
            }
            y = maxOf(y, 76f)
            tableHeader()
        }
        fun tableHeader() {
            text.textSize = 8f; text.typeface = Typeface.DEFAULT_BOLD; text.color = navy
            canvas.drawText(title, 24f, y + 8f, text); y += 14f
            text.textSize = 7.4f
            val lines = headings.mapIndexed { i, heading -> wrap(heading, widths[i] - 6f) }
            val height = lines.maxOf { it.size } * lineHeight + 6f
            fill.color = navy; canvas.drawRect(24f, y, 818f, y + height, fill)
            text.color = Color.WHITE
            drawCells(lines, height)
            y += height
            text.color = navy; text.typeface = Typeface.DEFAULT; text.textSize = 7.6f
        }
        fun row(values: List<String>, total: Boolean = false) {
            text.typeface = if (total) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val wrapped = values.mapIndexed { i, value -> wrap(value, widths[i] - 6f) }
            var offset = 0
            val count = wrapped.maxOf { it.size }
            while (offset < count) {
                val required = minOf(count - offset, 40) * lineHeight + 5f
                if (y + minOf(required, 18f) > bottom || (required < 350f && y + required > bottom)) newPage()
                text.typeface = if (total) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val available = ((bottom - y - 5f) / lineHeight).toInt().coerceAtLeast(1)
                val take = minOf(count - offset, available)
                val height = take * lineHeight + 5f
                fill.color = if (total) Color.rgb(218, 237, 231) else if (alternate) Color.rgb(243, 247, 250) else Color.WHITE
                canvas.drawRect(24f, y, 818f, y + height, fill)
                drawCells(wrapped.map { it.drop(offset).take(take) }, height)
                y += height; offset += take
                if (offset < count) newPage()
            }
            alternate = !alternate
            text.typeface = Typeface.DEFAULT
        }
        fun drawCells(lines: List<List<String>>, height: Float) {
            var x = 24f
            lines.forEachIndexed { index, cell ->
                cell.forEachIndexed { line, value -> canvas.drawText(value, x + 3f, y + 10f + line * lineHeight, text) }
                canvas.drawRect(x, y, x + widths[index], y + height, grid)
                x += widths[index]
            }
        }
        fun wrap(value: String, width: Float): List<String> = buildList {
            value.lines().forEach { paragraph ->
                var rest = paragraph
                if (rest.isEmpty()) add("")
                while (rest.isNotEmpty()) {
                    val size = text.breakText(rest, true, width, null).coerceAtLeast(1)
                    val cut = if (size < rest.length) rest.lastIndexOf(' ', size - 1).takeIf { it > 0 } ?: size else size
                    add(rest.take(cut)); rest = rest.drop(cut).trimStart()
                }
            }
        }
        fun fit(bitmap: Bitmap, bounds: RectF) {
            val scale = minOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
            val width = bitmap.width * scale; val height = bitmap.height * scale
            val target = RectF(bounds.centerX() - width / 2, bounds.centerY() - height / 2, bounds.centerX() + width / 2, bounds.centerY() + height / 2)
            canvas.drawBitmap(bitmap, null, target, bitmapPaint)
        }
        fun finish() {
            page?.let {
                canvas.drawLine(24f, 519f, 818f, 519f, grid)
                text.color = navy; text.textSize = 8f; text.typeface = Typeface.DEFAULT
                canvas.drawText("Signature", 646f, 531f, text)
                signature?.let { image -> fit(image, RectF(646f, 535f, 806f, 574f)) }
                    ?: canvas.drawLine(646f, 568f, 806f, 568f, grid)
                text.textSize = 7f
                canvas.drawText(profile.fullName, 646f, 585f, text)
                canvas.drawText("Les franchises de trajet (45 minutes par défaut) sont déjà déduites des heures non effectives", 24f, 543f, text)
                canvas.drawText("lorsqu'elles s'appliquent.", 24f, 554f, text)
                canvas.drawText("Établi le ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}", 24f, 582f, text)
                canvas.drawText("Page $pageNumber", 550f, 582f, text)
                document.finishPage(it); page = null
                text.textSize = 7.6f; text.typeface = Typeface.DEFAULT; text.color = navy
            }
        }
    }
}
