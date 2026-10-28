package fr.worktrack.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.worktrack.data.EntryCodec
import fr.worktrack.domain.Backup
import fr.worktrack.domain.BackupValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PendingDocument(val path: String, val name: String, val mime: String)

@Singleton
class DocumentService @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun prepare(name: String, mime: String, writer: (java.io.OutputStream) -> Unit): PendingDocument = withContext(Dispatchers.IO) {
        // A separate folder keeps concurrent shares independent and preserves the exact public filename.
        val folder = File(context.cacheDir, "reports/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, name)
        file.outputStream().use(writer)
        PendingDocument(file.absolutePath, name, mime)
    }
    suspend fun save(document: PendingDocument, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> File(document.path).inputStream().use { it.copyTo(output) } }
            ?: error("Impossible d'ouvrir le fichier choisi.")
    }
    suspend fun pointage(source: List<fr.worktrack.domain.WorkEntry>, week: PointageWeek, profile: fr.worktrack.domain.Profile): PendingDocument =
        prepare("${ReportNames.base(profile, week.monday, monday = true)}.xlsx", XlsxExporter.MIME) { output ->
            context.assets.open(XlsxExporter.TEMPLATE).use { template -> XlsxExporter.write(output, template, source, week, profile) }
        }
    fun share(document: PendingDocument) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(document.path))
        val intent = Intent(Intent.ACTION_SEND).setType(document.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(document.name, uri) }
        context.startActivity(Intent.createChooser(intent, "Partager le relevé").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    suspend fun readBackup(uri: Uri): Backup = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val size = input.read(block); if (size < 0) break
                require(buffer.size() + size <= 40 * 1024 * 1024) { "La sauvegarde dépasse la limite de 40 Mo." }
                buffer.write(block, 0, size)
            }
            buffer.toByteArray()
        } ?: error("Impossible de lire la sauvegarde.")
        val backup = EntryCodec.json.decodeFromString<Backup>(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        BackupValidator.validate(backup)
        backup
    }
}
