package com.clipcut.data.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Fetches HLS media playlists and downloads the segments they list. */
class SegmentFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    /** Follows one level of master -> variant redirection, if present, to the media playlist. */
    suspend fun fetchMediaPlaylist(playlistUrl: String): ParsedMediaPlaylist =
        withContext(Dispatchers.IO) {
            val text = fetchText(playlistUrl)
            if (HlsPlaylistParser.isMasterPlaylist(text)) {
                val variantUri = HlsPlaylistParser.firstVariantUri(text)
                    ?: throw IOException("Master playlist at $playlistUrl has no variant streams")
                HlsPlaylistParser.parseMediaPlaylist(fetchText(resolve(playlistUrl, variantUri)))
            } else {
                HlsPlaylistParser.parseMediaPlaylist(text)
            }
        }

    /**
     * Downloads [initSegmentUri] (if present) followed by [segments], in order, concatenated
     * byte-for-byte into [outputFile]. Consecutive fMP4 segments sharing one init segment are
     * valid to concatenate this way -- each is just another moof/mdat pair for the same track.
     */
    suspend fun downloadConcatenated(
        playlistUrl: String,
        initSegmentUri: String?,
        segments: List<PlaylistSegment>,
        outputFile: File,
    ): Unit = withContext(Dispatchers.IO) {
        outputFile.outputStream().use { out ->
            if (initSegmentUri != null) {
                out.write(fetchBytes(resolve(playlistUrl, initSegmentUri)))
            }
            for (segment in segments) {
                out.write(fetchBytes(resolve(playlistUrl, segment.uri)))
            }
        }
    }

    /** Resolves a (relative or root-relative) playlist URI against [baseUrl], keeping its query. */
    private fun resolve(baseUrl: String, uri: String): String {
        val base = baseUrl.toHttpUrl()
        val path = if (uri.startsWith("/")) uri else base.encodedPath.substringBeforeLast('/') + "/" + uri
        return base.newBuilder()
            .encodedPath(path)
            .encodedQuery(base.encodedQuery)
            .build()
            .toString()
    }

    private fun fetchText(url: String): String = request(url).use { response ->
        response.body?.string() ?: throw IOException("Empty body for $url")
    }

    private fun fetchBytes(url: String): ByteArray = request(url).use { response ->
        response.body?.bytes() ?: throw IOException("Empty body for $url")
    }

    private fun request(url: String): okhttp3.Response {
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("GET $url failed: HTTP ${response.code}")
        }
        return response
    }
}
