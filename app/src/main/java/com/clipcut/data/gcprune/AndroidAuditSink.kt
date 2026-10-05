package com.clipcut.data.gcprune

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.clipcut.domain.gcprune.AuditSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audit files are written twice. The copy in the app's own storage is the one that must succeed: if it
 * cannot be written, this throws and a live run stops. The second copy in Documents/XAuto/gc-prune/ is so
 * you can open the file from a file manager; it is best-effort, because losing it loses nothing.
 */
@Singleton
class AndroidAuditSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuditSink {
    private val mirrorUris = ConcurrentHashMap<String, Uri>()

    override suspend fun write(fileName: String, json: String): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "gc-prune").apply { mkdirs() }
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not finish writing the audit file" }
        }
        runCatching { mirror(fileName, json) }.getOrNull() ?: target.absolutePath
    }

    private fun mirror(fileName: String, json: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = context.getExternalFilesDir("gc-prune") ?: return null
            dir.mkdirs()
            return File(dir, fileName).also { it.writeText(json) }.absolutePath
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = mirrorUris[fileName]
            ?: findExisting(collection, fileName)
            ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                },
            )
            ?: return null
        // "wt" truncates, so a rewrite replaces the file instead of leaving the tail of a longer old one.
        resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: return null
        mirrorUris[fileName] = uri
        return RELATIVE + fileName
    }

    private fun findExisting(collection: Uri, fileName: String): Uri? =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, RELATIVE),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    private companion object {
        const val RELATIVE = "Documents/XAuto/gc-prune/"
    }
}
