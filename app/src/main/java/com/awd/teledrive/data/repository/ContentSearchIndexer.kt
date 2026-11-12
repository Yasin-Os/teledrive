package com.awd.teledrive.data.repository

import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.FileContentFts
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Batch 7b smart search: indexes the text of a file into the standalone
 * file_content_fts table so content search can find it.
 *
 * Rules (all best-effort — indexing must never throw or break preview):
 *  - only when a local copy exists (localPath non-null and the file is
 *    really there);
 *  - only text files: mimeType text/*, or a text-like extension
 *    (.txt/.md/.json/.csv and friends);
 *  - only the first ~200 KB, read on Dispatchers.IO;
 *  - encrypted files are NEVER indexed: their local bytes are ciphertext
 *    (useless) and indexing a decrypted copy would put secure plaintext
 *    into an unencrypted FTS table;
 *  - PDFs are NOT indexed: the app has no PDF text-extraction library
 *    (PdfViewerScreen uses android.graphics.pdf.PdfRenderer, which only
 *    renders bitmaps). PDFs stay searchable by name only. No new
 *    dependency was added for this.
 * An empty/whitespace-only file removes any stale index row.
 */
class ContentSearchIndexer(private val driveDao: DriveDao) {

    companion object {
        const val MAX_INDEX_BYTES = 200L * 1024L

        private val INDEXABLE_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "log", "xml",
            "html", "htm", "srt", "vtt", "yaml", "yml", "ini", "cfg"
        )
    }

    suspend fun indexFile(file: DriveItem.File) {
        if (file.isEncrypted) return
        val uniqueId = file.remoteUniqueId
        if (uniqueId.isBlank()) return
        val path = file.localPath ?: return
        if (!isIndexable(file)) return
        withContext(Dispatchers.IO) {
            try {
                val local = File(path)
                if (!local.isFile || !local.exists()) return@withContext
                val bytes = local.inputStream().use { input ->
                    val buf = ByteArray(MAX_INDEX_BYTES.toInt())
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buf.copyOf(read)
                }
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.isEmpty()) {
                    driveDao.deleteFileContent(uniqueId)
                } else {
                    driveDao.upsertFileContent(FileContentFts(uniqueId, text))
                }
            } catch (_: Exception) {
                // Best-effort only: indexing must never break preview.
            }
        }
    }

    private fun isIndexable(file: DriveItem.File): Boolean {
        if (file.mimeType.startsWith("text/")) return true
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return ext in INDEXABLE_EXTENSIONS
    }
}
