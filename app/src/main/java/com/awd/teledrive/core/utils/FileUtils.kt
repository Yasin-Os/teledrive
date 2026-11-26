package com.awd.teledrive.core.utils

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

object FileUtils {
    const val MAX_FILE_SIZE = 2000 * 1024 * 1024L // 2000 MB for safety

    /**
     * Splits a large file into smaller chunks in the cache directory.
     * Returns a list of temporary files.
     * Uses suspend and yield to prevent CPU hogging/UI freeze for very large files.
     */
    suspend fun splitFile(
        context: Context, 
        sourceFile: File, 
        chunkSize: Long = MAX_FILE_SIZE,
        onProgress: (Float) -> Unit = {}
    ): List<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val parts = mutableListOf<File>()
        val buffer = ByteArray(1024 * 1024) // 1MB buffer
        val totalSize = sourceFile.length()
        var totalBytesReadAcrossParts = 0L

        // M35: the output stream is closed in a finally block and partial
        // parts are deleted on failure. Previously a cancel/error leaked
        // the file descriptor and left stale .tdpart files behind.
        var fos: FileOutputStream? = null
        try {
            FileInputStream(sourceFile).use { fis ->
                var partCounter = 1
                var bytesReadInPart = 0L
                var bytesRead: Int

                var currentPartFile = createTempPartFile(context, sourceFile.name, partCounter)
                fos = FileOutputStream(currentPartFile)
                parts.add(currentPartFile)

                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (bytesReadInPart + bytesRead > chunkSize) {
                        val remainingInChunk = (chunkSize - bytesReadInPart).toInt()
                        fos?.write(buffer, 0, remainingInChunk)
                        fos?.close()
                        fos = null

                        partCounter++
                        bytesReadInPart = (bytesRead - remainingInChunk).toLong()
                        currentPartFile = createTempPartFile(context, sourceFile.name, partCounter)
                        fos = FileOutputStream(currentPartFile)
                        fos?.write(buffer, remainingInChunk, bytesRead - remainingInChunk)
                        parts.add(currentPartFile)
                    } else {
                        fos?.write(buffer, 0, bytesRead)
                        bytesReadInPart += bytesRead
                    }

                    totalBytesReadAcrossParts += bytesRead
                    if (totalSize > 0) {
                        onProgress(totalBytesReadAcrossParts.toFloat() / totalSize.toFloat())
                    }

                    // Allow other tasks to breathe, prevents "black screen" and UI freeze
                    kotlinx.coroutines.yield()
                }
                fos?.close()
                fos = null
            }
        } catch (e: Exception) {
            try { fos?.close() } catch (_: Exception) {}
            parts.forEach { try { it.delete() } catch (_: Exception) {} }
            throw e
        } finally {
            try { fos?.close() } catch (_: Exception) {}
        }
        parts
    }

    /**
     * Merges multiple files into one.
     */
    suspend fun mergeFiles(parts: List<File>, targetFile: File, onProgress: (Float) -> Unit = {}) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val totalSize = parts.sumOf { it.length() }
        var bytesMerged = 0L
        
        // M35: truncate any previous (possibly larger) target first:
        // without this, leftover trailing bytes of an old file corrupt
        // the merged output.
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(0)
            parts.forEach { part ->
                FileInputStream(part).use { fis ->
                    val buffer = ByteArray(1024 * 1024)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        bytesMerged += bytesRead
                        if (totalSize > 0) {
                            onProgress(bytesMerged.toFloat() / totalSize.toFloat())
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            }
        }
    }

    private fun createTempPartFile(context: Context, originalName: String, index: Int): File {
        val cacheDir = File(context.cacheDir, "upload_parts")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "$originalName.${index.toString().padStart(3, '0')}.tdpart")
    }

    fun generateSplitGroupId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun cleanupParts(parts: List<File>) {
        parts.forEach { it.delete() }
    }

    fun getAvailableSpace(context: Context): Long {
        return context.cacheDir.usableSpace
    }

    fun hasEnoughSpace(context: Context, requiredSize: Long): Boolean {
        // Add 100MB margin for safety
        return getAvailableSpace(context) > requiredSize + (100 * 1024 * 1024L)
    }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        // L28: sizes >= 1 PB used to index past the end of `units` (crash).
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
        return String.format(java.util.Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }
}
