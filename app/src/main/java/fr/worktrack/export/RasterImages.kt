package fr.worktrack.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Images are copied into portable PNG data; exports/backups never depend on a temporary URI. */
@Singleton
class ImageImporter @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val block = ByteArray(8192)
            while (true) {
                val count = input.read(block); if (count < 0) break
                require(output.size() + count <= 15 * 1024 * 1024) { "Choisissez une image de moins de 15 Mo." }
                output.write(block, 0, count)
            }
            output.toByteArray()
        } ?: error("Impossible de lire cette image.")
        RasterImages.normalize(data)
    }
}

object RasterImages {
    fun decode(value: String): Bitmap? = if (value.isBlank()) null else runCatching {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    fun normalize(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Ce fichier n'est pas une image prise en charge." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Impossible de décoder cette image.")
        try {
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
            }
            var edge = 960
            while (true) {
                val longest = maxOf(bitmap.width, bitmap.height)
                if (longest > edge) {
                    val scale = edge / longest.toFloat()
                    val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
                    if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
                }
                val encoded = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
                if (encoded.size <= 750_000) return Base64.encodeToString(encoded, Base64.NO_WRAP)
                edge /= 2
                require(edge >= 120) { "L'image est trop volumineuse. Choisissez une version plus simple." }
            }
        } finally { bitmap.recycle() }
    }
}
