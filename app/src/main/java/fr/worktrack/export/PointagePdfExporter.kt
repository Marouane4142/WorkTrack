package fr.worktrack.export

import android.graphics.*
import android.graphics.pdf.PdfDocument
import fr.worktrack.domain.French
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor
import kotlin.math.roundToInt

/** Prints the filled workbook itself: one A4 portrait page per original worksheet. */
object PointagePdfExporter {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val DRAW = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(destination: OutputStream, workbook: ByteArray) {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(workbook)).use { zip ->
            while (true) { val item = zip.nextEntry ?: break; if (!item.isDirectory) parts[item.name] = zip.readBytes() }
        }
        val book = xml(parts.getValue("xl/workbook.xml"))
        val sheets = book.documentElement.children("sheets").single().children("sheet")
        require(sheets.map { it.getAttribute("name") } == PointageWeek.sheetNames)
        val links = relationships(parts.getValue("xl/_rels/workbook.xml.rels"))
        val strings = parts["xl/sharedStrings.xml"]?.let { xml(it).documentElement.children("si").map(::plainText) }.orEmpty()
        val styles = Styles(xml(parts.getValue("xl/styles.xml")))
        val images = mutableMapOf<String, Bitmap>()
        val pdf = PdfDocument()
        try {
            sheets.forEachIndexed { index, sheet ->
                val path = resolve("xl/", links.getValue(sheet.getAttributeNS(OFFICE_REL, "id")))
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                try { Worksheet(xml(parts.getValue(path)), styles, strings).draw(page.canvas, parts, path, images) }
                finally { pdf.finishPage(page) }
            }
            pdf.writeTo(destination)
        } finally { pdf.close(); images.values.forEach { it.recycle() } }
    }

    private data class Range(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Cell(val element: Element, val column: Int, val row: Int, val style: Element, val text: String, val numeric: Boolean)

    private class Worksheet(val doc: Document, val styles: Styles, val strings: List<String>) {
        private val root = doc.documentElement
        private val widths = FloatArray(9) { 48f }
        private val heights = FloatArray(62) { 15f }
        private val columns = FloatArray(10)
        private val rows = FloatArray(63)
        private val merges = root.children("mergeCells").flatMap { it.children("mergeCell") }.map { range(it.getAttribute("ref")) }
        private val cells: List<Cell>
        private val byPosition: Map<Pair<Int, Int>, Cell>
        init {
            root.children("cols").flatMap { it.children("col") }.forEach { column ->
                val width = column.getAttribute("width").toFloatOrNull() ?: 8.43f
                val pixels = if (width < 1) floor(width * 12f) else floor(width * 7f + 5f)
                for (index in column.getAttribute("min").toInt()..column.getAttribute("max").toInt()) {
                    if (index in 1..9) widths[index - 1] = if (column.getAttribute("hidden") == "1") 0f else pixels * .75f
                }
            }
            val data = root.children("sheetData").single().children("row")
            data.forEach { row ->
                val index = row.getAttribute("r").toInt()
                if (index in 1..62) heights[index - 1] = if (row.getAttribute("hidden") == "1") 0f else row.getAttribute("ht").toFloatOrNull() ?: 15f
            }
            widths.forEachIndexed { index, width -> columns[index + 1] = columns[index] + width }
            heights.forEachIndexed { index, height -> rows[index + 1] = rows[index] + height }
            cells = data.flatMap { it.children("c") }.map { element ->
                val address = range(element.getAttribute("r"))
                val style = styles.cellStyles[element.getAttribute("s").toIntOrNull() ?: 0]
                val value = element.children("v").firstOrNull()?.textContent.orEmpty()
                val type = element.getAttribute("t")
                val text = when (type) {
                    "s" -> strings[value.toInt()]
                    "inlineStr" -> plainText(element)
                    else -> value.toDoubleOrNull()?.let { number(it, styles.format(style)) } ?: value
                }
                Cell(element, address.left, address.top, style, text, type != "s" && type != "inlineStr" && value.toDoubleOrNull() != null)
            }
            byPosition = cells.associateBy { it.column to it.row }
        }

        fun draw(canvas: Canvas, parts: Map<String, ByteArray>, path: String, images: MutableMap<String, Bitmap>) {
            canvas.drawColor(Color.WHITE)
            val margins = root.children("pageMargins").firstOrNull()
            fun margin(name: String, fallback: Float) = (margins?.getAttribute(name)?.toFloatOrNull() ?: fallback) * 72f
            val left = margin("left", .7f); val top = margin("top", .75f)
            val scale = minOf((595f - left - margin("right", .7f)) / columns.last(), (842f - top - margin("bottom", .75f)) / rows.last())
            canvas.save(); canvas.translate(left, top); canvas.scale(scale, scale)
            val fill = Paint()
            cells.forEach { cell ->
                val pattern = styles.fills[cell.style.getAttribute("fillId").toIntOrNull() ?: 0].children("patternFill").firstOrNull()
                if (pattern?.getAttribute("patternType") == "solid") {
                    fill.color = color(pattern.children("fgColor").firstOrNull(), Color.WHITE)
                    canvas.drawRect(bounds(Range(cell.column, cell.row, cell.column, cell.row)), fill)
                }
            }
            cells.forEach { cell -> drawBorders(canvas, cell) }
            cells.filter { it.text.isNotEmpty() }.forEach { cell -> drawText(canvas, cell) }
            drawImages(canvas, parts, path, images)
            canvas.restore()
        }

        private fun bounds(range: Range) = RectF(columns[range.left - 1], rows[range.top - 1], columns[range.right], rows[range.bottom])
        private fun merge(cell: Cell) = merges.firstOrNull { cell.column in it.left..it.right && cell.row in it.top..it.bottom }

        private fun drawBorders(canvas: Canvas, cell: Cell) {
            val box = bounds(Range(cell.column, cell.row, cell.column, cell.row))
            val merged = merge(cell)
            val border = styles.borders[cell.style.getAttribute("borderId").toIntOrNull() ?: 0]
            fun edge(name: String, permitted: Boolean, x1: Float, y1: Float, x2: Float, y2: Float) {
                val definition = border.children(name).firstOrNull() ?: return
                val style = definition.getAttribute("style")
                if (!permitted || style.isBlank() || style == "none") return
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = color(definition.children("color").firstOrNull(), Color.BLACK)
                    strokeWidth = when (style) { "medium" -> 1f; "thick" -> 1.5f; "hair" -> .25f; else -> .5f }
                    if (style in listOf("dashed", "dotted")) pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
                }
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
            edge("left", merged == null || cell.column == merged.left, box.left, box.top, box.left, box.bottom)
            edge("right", merged == null || cell.column == merged.right, box.right, box.top, box.right, box.bottom)
            edge("top", merged == null || cell.row == merged.top, box.left, box.top, box.right, box.top)
            edge("bottom", merged == null || cell.row == merged.bottom, box.left, box.bottom, box.right, box.bottom)
        }

        private fun drawText(canvas: Canvas, cell: Cell) {
            val merged = merge(cell)
            if (merged != null && (cell.column != merged.left || cell.row != merged.top)) return
            val box = bounds(merged ?: Range(cell.column, cell.row, cell.column, cell.row))
            val alignment = cell.style.children("alignment").firstOrNull()
            val wrap = alignment?.getAttribute("wrapText") == "1"
            val horizontal = alignment?.getAttribute("horizontal").orEmpty().ifBlank { if (cell.numeric) "right" else "left" }
            // Excel permits a label to flow across adjacent empty cells (notably the form's footnote).
            if (!wrap && merged == null && horizontal == "left" && !cell.numeric) {
                var column = cell.column + 1
                while (column <= 9 && byPosition[column to cell.row]?.text.isNullOrEmpty() && merges.none { column in it.left..it.right && cell.row in it.top..it.bottom }) {
                    box.right = columns[column]; column++
                }
            }
            val font = styles.fonts[cell.style.getAttribute("fontId").toIntOrNull() ?: 0]
            val bold = font.children("b").any { it.getAttribute("val") != "0" }
            val italic = font.children("i").any { it.getAttribute("val") != "0" }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = font.children("sz").firstOrNull()?.getAttribute("val")?.toFloatOrNull() ?: 11f
                color = color(font.children("color").firstOrNull(), Color.BLACK)
                typeface = Typeface.create("sans-serif", when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
                isUnderlineText = font.children("u").isNotEmpty()
                textAlign = when (horizontal) { "center" -> Paint.Align.CENTER; "right" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
            }
            val padding = 1.5f
            val lines = if (wrap) wrapped(cell.text, paint, (box.width() - padding * 2).coerceAtLeast(1f)) else listOf(cell.text.replace('\n', ' '))
            val metrics = paint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent
            val height = lineHeight * lines.size
            val y = when (alignment?.getAttribute("vertical")) {
                "top" -> box.top + padding - metrics.ascent
                "center" -> box.top + (box.height() - height) / 2 - metrics.ascent
                else -> box.bottom - padding - metrics.descent - (lines.size - 1) * lineHeight
            }
            val x = when (horizontal) { "center" -> box.centerX(); "right" -> box.right - padding; else -> box.left + padding }
            canvas.save(); canvas.clipRect(box)
            lines.forEachIndexed { index, line -> canvas.drawText(line, x, y + index * lineHeight, paint) }
            canvas.restore()
        }

        private fun drawImages(canvas: Canvas, parts: Map<String, ByteArray>, path: String, images: MutableMap<String, Bitmap>) {
            val drawingId = root.children("drawing").firstOrNull()?.getAttributeNS(OFFICE_REL, "id") ?: return
            val directory = path.substringBeforeLast('/') + "/"
            val sheetLinks = relationships(parts.getValue(directory + "_rels/" + path.substringAfterLast('/') + ".rels"))
            val drawingPath = resolve(directory, sheetLinks.getValue(drawingId))
            val drawingDirectory = drawingPath.substringBeforeLast('/') + "/"
            val imageLinks = relationships(parts.getValue(drawingDirectory + "_rels/" + drawingPath.substringAfterLast('/') + ".rels"))
            val drawing = xml(parts.getValue(drawingPath)).documentElement
            for (anchor in drawing.children()) {
                val from = anchor.children("from").firstOrNull() ?: continue
                fun position(marker: Element): Pair<Float, Float> {
                    fun value(name: String) = marker.children(name).firstOrNull()?.textContent?.toFloatOrNull() ?: 0f
                    return (columns[value("col").toInt().coerceIn(0, 9)] + value("colOff") / 12700f) to
                        (rows[value("row").toInt().coerceIn(0, 62)] + value("rowOff") / 12700f)
                }
                val (left, top) = position(from)
                val to = anchor.children("to").firstOrNull()
                val extent = anchor.children("ext").firstOrNull()
                val (right, bottom) = if (to != null) position(to) else
                    (left + (extent?.getAttribute("cx")?.toFloatOrNull() ?: 0f) / 12700f) to (top + (extent?.getAttribute("cy")?.toFloatOrNull() ?: 0f) / 12700f)
                val picture = anchor.children("pic").firstOrNull() ?: continue
                val blip = picture.getElementsByTagNameNS(DRAW, "blip").item(0) as? Element ?: continue
                val imagePath = resolve(drawingDirectory, imageLinks.getValue(blip.getAttributeNS(OFFICE_REL, "embed")))
                val image = images.getOrPut(imagePath) {
                    val content = parts.getValue(imagePath)
                    BitmapFactory.decodeByteArray(content, 0, content.size) ?: error("Impossible de lire une image du modèle de pointage.")
                }
                canvas.drawBitmap(image, null, RectF(left, top, right, bottom), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    private class Styles(doc: Document) {
        val cellStyles = doc.documentElement.children("cellXfs").single().children("xf")
        val fonts = doc.documentElement.children("fonts").single().children("font")
        val fills = doc.documentElement.children("fills").single().children("fill")
        val borders = doc.documentElement.children("borders").single().children("border")
        val formats = doc.documentElement.children("numFmts").flatMap { it.children("numFmt") }.associate { it.getAttribute("numFmtId").toInt() to it.getAttribute("formatCode") }
        fun format(style: Element) = when (val id = style.getAttribute("numFmtId").toIntOrNull() ?: 0) { 1 -> "0"; 2 -> "0.00"; 49 -> "@"; else -> formats[id].orEmpty() }
    }
    private fun number(value: Double, format: String): String {
        val prefix = Regex("\"([^\"]*)\"").findAll(format).joinToString("") { it.groupValues[1] }
        return when {
            format.contains("dd/mm/yyyy") -> prefix + LocalDate.of(1899, 12, 30).plusDays(value.toLong()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            format.contains("hh:mm") || format.contains("[h]:mm") -> {
                val minutes = (value * 1440).roundToInt()
                prefix + String.format(French, "%02d:%02d", minutes / 60, minutes % 60)
            }
            format == "0.00" -> String.format(French, "%.2f", value)
            else -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        }
    }
    private fun wrapped(text: String, paint: Paint, width: Float): List<String> = text.split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>(); var rest = paragraph
        while (rest.isNotEmpty()) {
            val count = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            val space = if (count < rest.length) rest.lastIndexOf(' ', count - 1) else -1
            val end = if (space > 0) space else count
            lines += rest.substring(0, end); rest = rest.substring(end).trimStart()
        }
        lines.ifEmpty { listOf("") }
    }
    private fun range(address: String): Range {
        fun cell(value: String): Pair<Int, Int> {
            val column = value.takeWhile { it.isLetter() }.fold(0) { result, char -> result * 26 + (char.uppercaseChar() - 'A' + 1) }
            return column to value.dropWhile { it.isLetter() }.toInt()
        }
        val (left, top) = cell(address.substringBefore(':')); val (right, bottom) = cell(address.substringAfter(':', address))
        return Range(left, top, right, bottom)
    }
    private fun color(element: Element?, fallback: Int): Int {
        val rgb = element?.getAttribute("rgb").orEmpty()
        if (rgb.isNotBlank()) return Color.parseColor("#$rgb")
        return when (element?.getAttribute("theme")) { "0" -> Color.WHITE; "1" -> Color.BLACK; else -> fallback }
    }
    private fun plainText(element: Element): String = element.getElementsByTagNameNS(MAIN, "t").let { nodes -> (0 until nodes.length).joinToString("") { nodes.item(it).textContent } }
    private fun relationships(content: ByteArray) = xml(content).documentElement.children("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
    private fun resolve(directory: String, target: String) = if (target.startsWith('/')) target.drop(1) else URI(directory).resolve(target).normalize().path
    private fun xml(content: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(content))
    private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length).mapNotNull { (childNodes.item(it) as? Element)?.takeIf { element -> name == null || element.localName == name } }
}
