package com.clipcut.domain

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies an exported clip into the device's shared Movies collection (visible in Gallery/Photos
 * apps) so it can be eyeballed without pulling it off the device. Purely a convenience copy for
 * manual verification during testing -- the app's own flow (upload/caption/post) always reads
 * from the original cache-dir file, never this copy.
 */
object GalleryStore {
    suspend fun copyToGallery(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        // Scoped-storage MediaStore insert needs API 29+; older devices are skipped rather than
        // wiring up the legacy WRITE_EXTERNAL_STORAGE runtime-permission flow for a dev-only path.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: return@withContext false
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }

    /**
     * Saves a shared results image to Pictures/X Auto, so every image that went to X is also kept on the phone. Small
     * and quick, so it runs inline. False on Android 9 and older, or if the gallery refused it.
     */
    fun saveImage(context: Context, file: File, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/X Auto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: java.io.IOException) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
