package fr.worktrack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.worktrack.domain.*
import fr.worktrack.export.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class PointageExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualAndroidExporterFillsTheSuppliedTemplateAndEmbedsSignatureOnAllFiveSheets() = runBlocking {
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("reference-week.csv").bufferedReader().useLines { rows ->
            rows.drop(1).map { row ->
                val c = row.split(';'); val date = "2026-10-${c[0].padStart(2, '0')}"
                WorkCalculator.calculate(WorkEntry(start = "${date}T${c[1]}", end = "${date}T${c[2]}",
                    type = ActivityType.valueOf(c[3]), location = c[4], caseNumber = "00042"), WorkRules())
            }.toList()
        }
        val bitmap = Bitmap.createBitmap(300, 54, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText("Signature EXEMPLE", 8f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE; textSize = 28f })
        val signature = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val profile = Profile("Jean", "EXEMPLE", signature = Base64.encodeToString(signature, Base64.NO_WRAP))
        val document = DocumentService(context).pointage(source, PointageWeek(LocalDate.of(2026, 10, 5)), profile)
        assertEquals(XlsxExporter.MIME, document.mime)
        assertEquals("Feuilles_heures_hebdomadaire_EXEMPLE JEAN_2026-10-05.xlsx", document.name)
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(File(document.path).inputStream()).use { zip ->
            while (true) { val item = zip.nextEntry ?: break; parts[item.name] = zip.readBytes() }
        }
        assertArrayEquals(signature, parts["xl/media/user-signature.png"])
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        var effective = 0.0; var nonEffective = 0.0
        for (i in 1..5) {
            val sheet = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("xl/worksheets/sheet$i.xml")))
            val cells = sheet.getElementsByTagNameNS("*", "c")
            fun value(address: String): Double {
                for (j in 0 until cells.length) {
                    val cell = cells.item(j) as org.w3c.dom.Element
                    if (cell.getAttribute("r") == address) return cell.getElementsByTagNameNS("*", "v").item(0).textContent.toDouble()
                }
                error("Cellule absente : $address")
            }
            effective += value("G53") + value("G54"); nonEffective += value("H53") + value("H54")
            assertEquals(1.0, value("H58"), 0.0)
            val drawing = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("xl/drawings/drawing$i.xml")))
            val pictures = drawing.getElementsByTagNameNS("*", "cNvPr")
            assertEquals(1, (0 until pictures.length).count { (pictures.item(it) as org.w3c.dom.Element).getAttribute("name") == "Signature" })
        }
        assertEquals(30.75, effective, 0.0); assertEquals(8.5, nonEffective, 0.0)
        // Test output only; never preloaded as user history or bundled in the release APK.
        File(context.getExternalFilesDir(null), "pointage-exemple.xlsx").writeBytes(File(document.path).readBytes())
        val pdf = DocumentService(context).pointage(source, PointageWeek(LocalDate.of(2026, 10, 5)), profile, PointageFormat.PDF)
        assertTrue(pdf.name.endsWith(".pdf")); assertEquals("application/pdf", pdf.mime)
        android.os.ParcelFileDescriptor.open(File(pdf.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                assertEquals(5, renderer.pageCount)
                repeat(5) { index -> renderer.openPage(index).use { page -> assertEquals(595, page.width); assertEquals(842, page.height) } }
            }
        }
        File(context.getExternalFilesDir(null), "pointage-exemple.pdf").writeBytes(File(pdf.path).readBytes())
    }
    @Test fun openingTheSavedFileUsesItsActualUriMimeAndTemporaryReadPermission() = runBlocking {
        val service = DocumentService(context)
        val prepared = service.prepare("Pointage.xlsx", XlsxExporter.MIME) { out -> context.assets.open(XlsxExporter.TEMPLATE).use { it.copyTo(out) } }
        val folder = File(context.cacheDir, "reports/open-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val target = File(folder, "Fichier enregistre.xlsx")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        try {
            val saved = service.save(prepared, uri)
            assertEquals(uri.toString(), saved.savedUri)
            val intent = service.openIntent(saved)
            assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
            assertEquals(uri, intent.data); assertEquals(XlsxExporter.MIME, intent.type)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertArrayEquals(File(prepared.path).readBytes(), context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        } finally { File(prepared.path).delete(); target.delete(); folder.delete() }
    }
    @Test fun missingViewerProducesAnUnderstandableMessageInsteadOfAnActivityCrash() {
        val service = DocumentService(object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: android.content.Intent) { throw android.content.ActivityNotFoundException() }
        })
        val exception = assertThrows(IllegalStateException::class.java) {
            service.open(PendingDocument("", "Pointage.pdf", "application/pdf", "content://example/document/pointage"))
        }
        assertTrue(exception.message!!.contains("lecteur PDF"))
    }
}
