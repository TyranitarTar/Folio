package app.folio.reader

import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object EpubValidator {
    suspend fun validate(file: File) {
        require(file.length() in 1..200L * 1024 * 1024) { "Please choose an EPUB smaller than 200 MB." }
        try { ZipFile(file).use { zip ->
            require(zip.size() <= 15000 && zip.getEntry("META-INF/container.xml") != null) { "This file is not a valid EPUB." }
            val names = mutableSetOf<String>(); val buffer = ByteArray(65536); var expanded = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = entries.nextElement()
                require(names.add(entry.name) && !entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "The EPUB has ambiguous resource paths." }
                if (entry.isDirectory) continue
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                val limit = if (extension in setOf("xhtml", "html", "htm", "xml", "opf", "ncx", "svg")) 8L * 1024 * 1024 else 32L * 1024 * 1024
                require(entry.size in 0..limit) { "An EPUB resource is too large to read safely." }
                val crc = CRC32(); var bytes = 0L
                zip.getInputStream(entry).use { input -> while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; expanded += count; crc.update(buffer, 0, count)
                    require(bytes <= limit && expanded <= 256L * 1024 * 1024) { "The EPUB contains too much expanded data." }
                } }
                require(bytes == entry.size && crc.value == entry.crc) { "The EPUB contains a damaged resource." }
                if (extension in setOf("jpg", "jpeg", "png", "webp", "gif")) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth <= 12000 && bounds.outHeight <= 12000 &&
                        bounds.outWidth.toLong().coerceAtLeast(0) * bounds.outHeight.coerceAtLeast(0) <= 12_000_000) {
                        "An illustration is too large to decode safely on this device."
                    }
                }
            }
        } } catch (error: java.util.zip.ZipException) { throw IllegalArgumentException("This EPUB archive is damaged.", error) }
    }
}
