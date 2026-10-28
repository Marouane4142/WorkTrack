package fr.worktrack.export

import fr.worktrack.domain.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Patches the original OOXML package; unrelated parts, logo and layout stay intact. */
object XlsxExporter {
    const val TEMPLATE = "templates/pointage-hebdomadaire.xlsx"
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
    private const val ART = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(output: OutputStream, template: InputStream, source: List<WorkEntry>, week: PointageWeek, profile: Profile) {
        // Validate capacity before producing any output, including on later weekdays.
        val days = week.days(source)
        val lines = days.map { it.lines() }
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(template).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory) parts[item.name] = zip.readBytes()
            }
        }
        val workbook = xml(requireNotNull(parts["xl/workbook.xml"]) { "Modèle Excel incomplet." })
        val sheets = workbook.documentElement.children("sheets").single().children("sheet")
        require(sheets.map { it.getAttribute("name") } == PointageWeek.sheetNames) { "Le modèle doit contenir les onglets Lundi à Vendredi, dans cet ordre." }
        val relations = xml(requireNotNull(parts["xl/_rels/workbook.xml.rels"]))
            .documentElement.children("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val styles = Styles(xml(requireNotNull(parts["xl/styles.xml"])))
        days.forEachIndexed { index, day ->
            val relation = sheets[index].getAttributeNS(OFFICE_REL, "id")
            val target = requireNotNull(relations[relation])
            val path = if (target.startsWith('/')) target.drop(1) else "xl/$target"
            val doc = xml(requireNotNull(parts[path]))
            val sheet = Sheet(doc, styles)
            sheet.number("B3", excelDate(day.date), "\"Date : \"dd/mm/yyyy")
            sheet.text("D3", "NOM : ${profile.lastName}")
            sheet.text("D4", "Prénom : ${profile.firstName}")
            sheet.number("H3", week.number.toDouble(), "0")
            sheet.number("G4", excelDate(week.monday), "\"Du : \"dd/mm/yyyy")
            sheet.number("H4", excelDate(week.friday), "\"au : \"dd/mm/yyyy")
            lines[index].forEach { line ->
                val row = line.row
                val entry = line.entry
                val start = Duration.between(day.date.atStartOfDay(), line.start).toMinutes()
                val end = Duration.between(day.date.atStartOfDay(), line.end).toMinutes()
                sheet.number("B$row", start / 1440.0, "\"de : \"hh:mm")
                sheet.number("C$row", end / 1440.0, "\"à : \"[h]:mm")
                if (row >= 17) {
                    if (entry.type.isTravel) {
                        val franchise = entry.appliedRules.returnHomeDeductionMinutes
                        val homeSite = setOf(entry.departure?.type, entry.arrival?.type) == setOf(PlaceType.HOME, PlaceType.CUSTOMER_SITE)
                        val label = when {
                            homeSite && row == 17 -> "PREMIER TRAJET AU DELA DE $franchise MIN"
                            homeSite && row == 51 -> "DERNIER TRAJET AU DELA DE $franchise MIN"
                            else -> "TRAJET"
                        }
                        sheet.text("D$row", label)
                    } else {
                        sheet.text("D$row", if (entry.type.isBreak) entry.type.displayLabel else entry.title, wrap = true)
                        sheet.text("E${row - 1}", entry.caseNumber, wrap = true)
                    }
                    val total = line.summary
                    sheet.number("G$row", total.effectiveMinutes / 60.0)
                    sheet.number("H$row", total.nonEffectiveMinutes / 60.0)
                }
            }
            val total = day.summary
            sheet.number("G54", total.nightEffectiveMinutes / 60.0)
            sheet.number("H54", total.nightNonEffectiveMinutes / 60.0)
            sheet.formula("G53", "SUM(G17:G51)-G54", (total.effectiveMinutes - total.nightEffectiveMinutes) / 60.0)
            sheet.formula("H53", "SUM(H17:H51)-H54", (total.nonEffectiveMinutes - total.nightNonEffectiveMinutes) / 60.0)
            sheet.number("H58", total.mealCount.toDouble(), "0")
            // No quantities, calculations or invented values for financial allowances.
            sheet.printOnOnePage()
            parts[path] = bytes(doc)
        }
        parts["xl/styles.xml"] = bytes(styles.doc)
        var calc = workbook.documentElement.children("calcPr").firstOrNull()
        if (calc == null) {
            calc = workbook.createElementNS(MAIN, "calcPr")
            val extension = workbook.documentElement.children("extLst").firstOrNull()
            workbook.documentElement.insertBefore(calc, extension)
        }
        calc.setAttribute("calcMode", "auto")
        calc.setAttribute("fullCalcOnLoad", "1")
        calc.setAttribute("forceFullCalc", "1")
        // Each day is a printable copy of the supplied daily form.
        val names = workbook.documentElement.children("definedNames").firstOrNull() ?: workbook.createElementNS(MAIN, "definedNames").also {
            workbook.documentElement.insertBefore(it, calc)
        }
        names.children("definedName").filter { it.getAttribute("name") == "_xlnm.Print_Area" }.forEach(names::removeChild)
        PointageWeek.sheetNames.forEachIndexed { index, name ->
            names.appendChild(workbook.createElementNS(MAIN, "definedName").apply {
                setAttribute("name", "_xlnm.Print_Area"); setAttribute("localSheetId", index.toString())
                textContent = "'$name'!\$A\$1:\$I\$62"
            })
        }
        parts["xl/workbook.xml"] = bytes(workbook)
        parts["docProps/core.xml"]?.let { original ->
            val metadata = xml(original)
            listOf("creator", "lastModifiedBy").forEach { name ->
                val nodes = metadata.getElementsByTagNameNS("*", name)
                for (i in 0 until nodes.length) nodes.item(i).textContent = profile.fullName
            }
            parts["docProps/core.xml"] = bytes(metadata)
        }
        addImages(parts, profile)
        // Finish the ZIP without taking ownership of the caller's output stream.
        ZipOutputStream(object : FilterOutputStream(output) { override fun close() { flush() } }).use { zip ->
            parts.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                zip.write(content); zip.closeEntry()
            }
        }
    }

    private fun addImages(parts: MutableMap<String, ByteArray>, profile: Profile) {
        if (profile.companyLogo.isNotBlank()) {
            val logo = Base64.getDecoder().decode(profile.companyLogo)
            require(logo.size >= 24) { "Logo PNG invalide." }
            parts["xl/media/image1.png"] = logo
            // Fit the configured logo inside each existing logo frame without stretching it.
            for (index in 1..5) {
                val path = "xl/drawings/drawing$index.xml"
                val doc = xml(requireNotNull(parts[path]))
                val root = doc.documentElement
                val anchors = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
                anchors.forEach { old ->
                    val pic = old.children("pic").firstOrNull() ?: return@forEach
                    val transform = pic.children("spPr").firstOrNull()?.children("xfrm")?.firstOrNull() ?: return@forEach
                    val extent = transform.children("ext").firstOrNull() ?: return@forEach
                    val from = old.children("from").firstOrNull() ?: return@forEach
                    val ratio = minOf(extent.getAttribute("cx").toDouble() / readInt(logo, 16).coerceAtLeast(1),
                        extent.getAttribute("cy").toDouble() / readInt(logo, 20).coerceAtLeast(1))
                    val width = (readInt(logo, 16) * ratio).toLong().toString()
                    val height = (readInt(logo, 20) * ratio).toLong().toString()
                    extent.setAttribute("cx", width); extent.setAttribute("cy", height)
                    val replacement = doc.createElementNS(DRAWING, "xdr:oneCellAnchor").apply {
                        appendChild(from.cloneNode(true))
                        appendChild(doc.createElementNS(DRAWING, "xdr:ext").apply { setAttribute("cx", width); setAttribute("cy", height) })
                        appendChild(pic.cloneNode(true))
                        appendChild(doc.createElementNS(DRAWING, "xdr:clientData"))
                    }
                    root.replaceChild(replacement, old)
                }
                parts[path] = bytes(doc)
            }
        }
        if (profile.signature.isBlank()) return
        val signature = Base64.getDecoder().decode(profile.signature)
        require(signature.size >= 24) { "Signature PNG invalide." }
        parts["xl/media/user-signature.png"] = signature
        val pngWidth = readInt(signature, 16).coerceAtLeast(1)
        val pngHeight = readInt(signature, 20).coerceAtLeast(1)
        val scale = minOf(330.0 / pngWidth, 54.0 / pngHeight)
        val width = (pngWidth * scale * 9525).toLong()
        val height = (pngHeight * scale * 9525).toLong()
        for (index in 1..5) {
            val path = "xl/drawings/drawing$index.xml"
            val doc = xml(requireNotNull(parts[path]))
            val relPath = "xl/drawings/_rels/drawing$index.xml.rels"
            val relations = xml(requireNotNull(parts[relPath]))
            val ids = relations.documentElement.children("Relationship").map { it.getAttribute("Id") }.toSet()
            var suffix = 1
            while ("rIdSignature$suffix" in ids) suffix++
            val id = "rIdSignature$suffix"
            relations.documentElement.appendChild(relations.createElementNS(REL, "Relationship").apply {
                setAttribute("Id", id); setAttribute("Type", "$OFFICE_REL/image"); setAttribute("Target", "../media/user-signature.png")
            })
            val anchor = xml("""<xdr:oneCellAnchor xmlns:xdr="$DRAWING" xmlns:a="$ART" xmlns:r="$OFFICE_REL"><xdr:from><xdr:col>1</xdr:col><xdr:colOff>76200</xdr:colOff><xdr:row>57</xdr:row><xdr:rowOff>19050</xdr:rowOff></xdr:from><xdr:ext cx="$width" cy="$height"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="100" name="Signature"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="$id"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""".toByteArray())
            doc.documentElement.appendChild(doc.importNode(anchor.documentElement, true))
            parts[path] = bytes(doc); parts[relPath] = bytes(relations)
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int) = (0..3).fold(0) { value, i -> (value shl 8) or (bytes[offset + i].toInt() and 255) }
    private fun excelDate(date: LocalDate) = (date.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()).toDouble()

    private class Sheet(private val doc: Document, private val styles: Styles) {
        private val cells = doc.getElementsByTagNameNS(MAIN, "c").let { nodes ->
            (0 until nodes.length).associate { (nodes.item(it) as Element).let { e -> e.getAttribute("r") to e } }
        }
        private fun clear(address: String, format: String? = null, wrap: Boolean = false): Element {
            val cell = requireNotNull(cells[address]) { "Cellule $address absente du modèle." }
            while (cell.hasChildNodes()) cell.removeChild(cell.firstChild)
            cell.removeAttribute("t")
            if (format != null || wrap) cell.setAttribute("s", styles.forCell(cell.getAttribute("s").ifBlank { "0" }.toInt(), format, wrap).toString())
            return cell
        }
        fun text(address: String, value: String, wrap: Boolean = false) {
            require(value.length <= 32767) { "Texte trop long pour la cellule $address." }
            val cell = clear(address, format = "@", wrap = wrap)
            cell.setAttribute("t", "inlineStr")
            val text = doc.createElementNS(MAIN, "t").apply {
                setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                textContent = value.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            }
            cell.appendChild(doc.createElementNS(MAIN, "is").apply { appendChild(text) })
            if (wrap && address.startsWith("D") && value.length > 38) {
                val row = cell.parentNode as Element
                row.setAttribute("ht", (15 * ((value.length + 37) / 38)).toString())
                row.setAttribute("customHeight", "1")
            }
        }
        fun number(address: String, value: Double, format: String = "0.00") {
            clear(address, format).appendChild(doc.createElementNS(MAIN, "v").apply { textContent = value.toString() })
        }
        fun formula(address: String, formula: String, cached: Double) {
            val cell = clear(address, "0.00")
            cell.appendChild(doc.createElementNS(MAIN, "f").apply { textContent = formula })
            cell.appendChild(doc.createElementNS(MAIN, "v").apply { textContent = cached.toString() })
        }
        fun printOnOnePage() {
            val root = doc.documentElement
            val properties = root.children("sheetPr").firstOrNull() ?: doc.createElementNS(MAIN, "sheetPr").also { root.insertBefore(it, root.firstChild) }
            val setup = properties.children("pageSetUpPr").firstOrNull() ?: doc.createElementNS(MAIN, "pageSetUpPr").also(properties::appendChild)
            setup.setAttribute("fitToPage", "1")
            val page = root.children("pageSetup").firstOrNull() ?: doc.createElementNS(MAIN, "pageSetup").also {
                root.insertBefore(it, root.children("drawing").firstOrNull())
            }
            page.removeAttribute("scale")
            page.setAttribute("paperSize", "9"); page.setAttribute("orientation", "portrait")
            page.setAttribute("fitToWidth", "1"); page.setAttribute("fitToHeight", "1")
        }
    }

    private class Styles(val doc: Document) {
        private val cache = mutableMapOf<Triple<Int, String?, Boolean>, Int>()
        private val formats = mutableMapOf<String, Int>()
        fun forCell(base: Int, format: String?, wrap: Boolean): Int = cache.getOrPut(Triple(base, format, wrap)) {
            val xfs = doc.documentElement.children("cellXfs").single()
            val clone = xfs.children("xf")[base].cloneNode(true) as Element
            if (format != null) {
                val id = formats.getOrPut(format) {
                    val group = doc.documentElement.children("numFmts").firstOrNull() ?: doc.createElementNS(MAIN, "numFmts").also { doc.documentElement.insertBefore(it, doc.documentElement.firstChild) }
                    val id = maxOf(163, group.children("numFmt").maxOfOrNull { it.getAttribute("numFmtId").toInt() } ?: 163) + 1
                    group.appendChild(doc.createElementNS(MAIN, "numFmt").apply { setAttribute("numFmtId", id.toString()); setAttribute("formatCode", format) })
                    group.setAttribute("count", group.children("numFmt").size.toString())
                    id
                }
                clone.setAttribute("numFmtId", id.toString()); clone.setAttribute("applyNumberFormat", "1")
            }
            if (wrap) {
                val alignment = clone.children("alignment").firstOrNull() ?: doc.createElementNS(MAIN, "alignment").also(clone::appendChild)
                alignment.setAttribute("wrapText", "1"); alignment.setAttribute("vertical", "center")
                clone.setAttribute("applyAlignment", "1")
            }
            val index = xfs.children("xf").size
            xfs.appendChild(clone); xfs.setAttribute("count", (index + 1).toString())
            index
        }
    }

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull { index ->
        (childNodes.item(index) as? Element)?.takeIf { it.localName == name }
    }
    private fun xml(content: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(content))
    private fun bytes(doc: Document): ByteArray = ByteArrayOutputStream().also { output ->
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            .transform(DOMSource(doc), StreamResult(output))
    }.toByteArray()
}
