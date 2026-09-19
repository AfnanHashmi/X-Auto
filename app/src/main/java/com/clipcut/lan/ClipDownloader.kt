package com.clipcut.lan

import android.content.Context
import com.clipcut.data.stream.lan.MediaMtxConfig
import com.clipcut.domain.GalleryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Export = one HTTP GET. MediaMTX cuts [startEpochMs, endEpochMs) out of its own recording and
 * returns a finished, muxed MP4; the phone just saves it and copies it to the gallery.
 */
class ClipDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun export(startEpochMs: Long, endEpochMs: Long): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val host = MediaMtxConfig.host(context)
            val url = MediaMtxConfig.clipUrl(host, Instant.ofEpochMilli(startEpochMs).toString(), (endEpochMs - startEpochMs) / 1000.0)
            val out = File(context.cacheDir, "lan_clip_$startEpochMs.mp4")
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "PC returned ${resp.code}: ${resp.body?.string()?.take(120)}" }
                resp.body!!.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            check(out.length() > 0) { "PC returned an empty clip" }
            GalleryStore.copyToGallery(context, out)
            out
        }
    }
}
