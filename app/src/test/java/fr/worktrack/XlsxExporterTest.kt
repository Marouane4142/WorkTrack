package fr.worktrack

import fr.worktrack.domain.*
import fr.worktrack.export.*
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxExporterTest {
    private val monday = LocalDate.of(2026, 10, 5)
    private val week = PointageWeek(monday)
    private val profile = Profile("Jean", "EXEMPLE")
    private fun template() = requireNotNull(javaClass.getResourceAsStream("/${XlsxExporter.TEMPLATE}"))
    private fun entry(start: String, end: String, type: ActivityType = ActivityType.CUSTOMER_INTERVENTION,
                      location: String = "Site Alpha", number: String = "00042", rules: WorkRules = WorkRules()) =
        WorkCalculator.calculate(WorkEntry(start = start, end = end, type = type, location = location, caseNumber = number), rules)
    private fun export(source: List<WorkEntry>, person: Profile = profile, selectedWeek: PointageWeek = week): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        template().use { XlsxExporter.write(output, it, source, selectedWeek, person) }
        return unpack(output.toByteArray())
    }
    private fun unpack(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val item = zip.nextEntry ?: break; if (!item.isDirectory) parts[item.name] = zip.readBytes() }
        }
    }
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun sheet(parts: Map<String, ByteArray>, index: Int = 1) = xml(parts.getValue("xl/worksheets/sheet$index.xml"))
    private fun cell(doc: Document, address: String): Element = doc.getElementsByTagNameNS("*", "c").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("r") == address }
    }
    private fun value(doc: Document, address: String): Double = cell(doc, address).getElementsByTagNameNS("*", "v").item(0).textContent.toDouble()

    @Test fun originalFiveSheetsLayoutAndLogoArePreserved() {
        val original = template().use { unpack(it.readBytes()) }
        val result = export(listOf(entry("2026-10-05T09:00", "2026-10-05T12:00")))
        val names = xml(result.getValue("xl/workbook.xml")).getElementsByTagNameNS("*", "sheet")
        assertEquals(PointageWeek.sheetNames, (0 until names.length).map { (names.item(it) as Element).getAttribute("name") })
        original.filterKeys { it.startsWith("xl/drawings/") || it.startsWith("xl/media/") || it == "xl/sharedStrings.xml" || it == "xl/theme/theme1.xml" }.forEach { (path, bytes) ->
            assertArrayEquals(path, bytes, result.getValue(path))
        }
        (1..5).forEach { index ->
            val before = sheet(original, index); val after = sheet(result, index)
            listOf("mergeCell", "col").forEach { tag ->
                fun attributes(doc: Document) = doc.getElementsByTagNameNS("*", tag).let { nodes -> (0 until nodes.length).map { i ->
                    val attrs = nodes.item(i).attributes; (0 until attrs.length).associate { attrs.item(it).nodeName to attrs.item(it).nodeValue }
                } }
                assertEquals(attributes(before), attributes(after))
            }
            assertEquals(62, after.getElementsByTagNameNS("*", "row").length)
        }
    }
    @Test fun referenceWeekFillsTypedDatesTimesTotalsAndAllFiveDays() {
        val entries = javaClass.getResourceAsStream("/reference-week.csv")!!.bufferedReader().useLines { rows -> rows.drop(1).map { line ->
            val c = line.split(';'); val date = "2026-10-${c[0].padStart(2, '0')}"
            entry("${date}T${c[1]}", "${date}T${c[2]}", ActivityType.valueOf(c[3]), c[4])
        }.toList() }
        val result = export(entries)
        val expected = week.days(entries)
        (1..5).forEach { index ->
            val doc = sheet(result, index); val total = expected[index - 1].summary
            assertEquals(total.effectiveMinutes / 60.0, value(doc, "G53") + value(doc, "G54"), 0.000001)
            assertEquals(total.nonEffectiveMinutes / 60.0, value(doc, "H53") + value(doc, "H54"), 0.000001)
            assertEquals(1.0, value(doc, "H58"), 0.0)
            assertEquals(41.0, value(doc, "H3"), 0.0)
            assertEquals("inlineStr", cell(doc, "D3").getAttribute("t"))
            assertTrue(cell(doc, "D3").textContent.contains("EXEMPLE"))
            assertEquals(monday.plusDays(index - 1L).toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay(), value(doc, "B3").toLong())
            assertEquals("SUM(G17:G51)-G54", cell(doc, "G53").getElementsByTagNameNS("*", "f").item(0).textContent)
        }
        assertEquals(30.75, (1..5).sumOf { value(sheet(result, it), "G53") + value(sheet(result, it), "G54") }, 0.0)
        assertEquals(8.5, (1..5).sumOf { value(sheet(result, it), "H53") + value(sheet(result, it), "H54") }, 0.0)
        val output = ByteArrayOutputStream()
        template().use { XlsxExporter.write(output, it, entries, week, profile) }
        File("build/pointage-test/reference-week.xlsx").apply { parentFile.mkdirs(); writeBytes(output.toByteArray()) }
    }
    @Test fun commutesKeepRawTimesAndDeductFranchiseOnceWithRecordedRules() {
        val rules = WorkRules(returnHomeDeductionMinutes = 60)
        val source = listOf(entry("2026-10-05T06:00", "2026-10-05T08:00", ActivityType.HOME_TO_CUSTOMER, rules = rules),
            entry("2026-10-05T08:00", "2026-10-05T12:00", location = "=1+1", number = "00007"),
            entry("2026-10-05T12:00", "2026-10-05T14:00", ActivityType.CUSTOMER_TO_HOME, rules = rules))
        val doc = sheet(export(source))
        assertEquals(6.0 / 24, value(doc, "B17"), 0.000001); assertEquals(8.0 / 24, value(doc, "C17"), 0.000001)
        assertEquals(1.0, value(doc, "H17"), 0.0); assertEquals(1.0, value(doc, "H51"), 0.0)
        assertEquals(2.0, value(doc, "H53"), 0.0)
        assertTrue(cell(doc, "D17").textContent.contains("60 MIN"))
        assertEquals("00007", cell(doc, "E17").textContent)
        assertEquals("=1+1", cell(doc, "D18").textContent)
        assertEquals("inlineStr", cell(doc, "D18").getAttribute("t"))
        assertEquals(0, cell(doc, "D18").getElementsByTagNameNS("*", "f").length)
    }
    @Test fun midnightAndWeekBoundariesKeepNightAndFranchiseOnTheCorrectCivilDay() {
        val source = listOf(entry("2026-10-04T23:30", "2026-10-05T01:30", ActivityType.HOME_TO_CUSTOMER),
            entry("2026-10-09T23:00", "2026-10-10T01:00", ActivityType.OFFICE_WORK),
            entry("2026-10-10T08:00", "2026-10-10T12:00"))
        val result = export(source)
        assertEquals(0.0, value(sheet(result), "B17"), 0.0)
        assertEquals(1.5 / 24, value(sheet(result), "C17"), 0.000001)
        assertEquals(1.25, value(sheet(result), "H54"), 0.0)
        assertEquals(0.0, value(sheet(result), "H53"), 0.0)
        assertEquals(1.0, value(sheet(result, 5), "G54"), 0.0)
        assertEquals(0.0, value(sheet(result, 5), "G53"), 0.0)
        assertEquals(1.0, value(sheet(result, 5), "C18"), 0.0)
    }
    @Test fun extraPausesAreKeptAsSeparateIntervalsInsteadOfInventingOneLongBreak() {
        val source = listOf(entry("2026-10-05T10:00", "2026-10-05T10:15", ActivityType.LUNCH_BREAK),
            entry("2026-10-05T12:00", "2026-10-05T13:00", ActivityType.LUNCH_BREAK))
        val lines = week.days(source).first().lines()
        assertEquals(listOf(14, 18), lines.map { it.row })
        val doc = sheet(export(source))
        assertEquals(10.0 / 24, value(doc, "B14"), 0.000001)
        assertEquals(12.0 / 24, value(doc, "B18"), 0.000001)
        assertEquals(0.0, value(doc, "G53"), 0.0)
    }
    @Test fun emptyWeekdaysStayDatedWithZeroTotalsAndNoFinancialQuantities() {
        val doc = sheet(export(emptyList()), 3)
        val original = template().use { sheet(unpack(it.readBytes()), 3) }
        assertEquals(0.0, value(doc, "G53"), 0.0); assertEquals(0.0, value(doc, "H54"), 0.0)
        assertEquals(0.0, value(doc, "H58"), 0.0)
        listOf("B18", "G18", "H59", "H60", "H61").forEach { assertEquals(cell(original, it).textContent, cell(doc, it).textContent) }
        assertEquals(LocalDate.of(2026, 10, 5), PointageWeek.containing(LocalDate.of(2026, 10, 11)).monday)
        assertEquals(53, PointageWeek.containing(LocalDate.of(2027, 1, 1)).number)
    }
    @Test fun overflowIsRejectedBeforeWritingAndNeverSilentlyDropsActivities() {
        val source = (0..17).map { index -> entry("2026-10-05T08:00", "2026-10-05T08:15", location = "Site $index") }
        val output = ByteArrayOutputStream()
        template().use { input ->
            assertThrows(IllegalArgumentException::class.java) { XlsxExporter.write(output, input, source, week, profile) }
        }
        assertEquals(0, output.size())
    }
    @Test fun signatureIsEmbeddedOnEverySheetWithoutRemovingTheOriginalCompanyLogo() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
        val result = export(emptyList(), profile.copy(signature = png))
        assertArrayEquals(Base64.getDecoder().decode(png), result.getValue("xl/media/user-signature.png"))
        assertTrue(result.containsKey("xl/media/image1.png"))
        for (index in 1..5) {
            val drawing = xml(result.getValue("xl/drawings/drawing$index.xml"))
            val original = template().use { xml(unpack(it.readBytes()).getValue("xl/drawings/drawing$index.xml")) }
            assertEquals(original.getElementsByTagNameNS("*", "pic").length + 1, drawing.getElementsByTagNameNS("*", "pic").length)
            val relationships = result.getValue("xl/drawings/_rels/drawing$index.xml.rels").toString(Charsets.UTF_8)
            assertTrue(relationships.contains("../media/user-signature.png"))
        }
    }
    @Test fun configuredLogoFitsExistingFramesWithoutDistortingItsAspectRatio() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
        val result = export(emptyList(), profile.copy(companyLogo = png))
        assertArrayEquals(Base64.getDecoder().decode(png), result.getValue("xl/media/image1.png"))
        for (index in 1..5) {
            val drawing = xml(result.getValue("xl/drawings/drawing$index.xml"))
            val anchors = drawing.getElementsByTagNameNS("*", "oneCellAnchor")
            assertTrue(anchors.length > 0)
            for (j in 0 until anchors.length) {
                val nodes = (anchors.item(j) as Element).getElementsByTagNameNS("*", "ext")
                val extent = (0 until nodes.length).map { nodes.item(it) as Element }.first { it.hasAttribute("cx") }
                assertEquals(extent.getAttribute("cx"), extent.getAttribute("cy"))
            }
        }
    }
}
