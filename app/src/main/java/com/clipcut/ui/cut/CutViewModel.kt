package com.clipcut.ui.cut

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.stream.PlaylistRepository
import com.clipcut.data.stream.SegmentFetcher
import com.clipcut.domain.ClipExporter
import com.clipcut.domain.ClipRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CutViewModel @Inject constructor(
    @ApplicationContext context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val playlistRepository: PlaylistRepository,
    private val segmentFetcher: SegmentFetcher,
    private val clipExporter: ClipExporter,
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    init {
        viewModelScope.launch {
            val url = playlistRepository.currentPlaylistUrl()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * Phase 3 spike trigger: picks a known-safe 10s span (well clear of the still-forming edge
     * segment) from the live playlist and runs it through [ClipExporter]. Removed once Phase 4's
     * real in/out scrubber replaces it.
     */
    fun runExportSpike() {
        viewModelScope.launch {
            _exportStatus.value = "Fetching playlist..."
            runCatching {
                val playlist = segmentFetcher.fetchMediaPlaylist(playlistRepository.currentPlaylistUrl())
                val segments = playlist.segments
                require(segments.size >= 4) { "Only ${segments.size} full segments buffered so far, need >= 4" }
                val anchor = segments[segments.size - 4]
                ClipRange(startEpochMs = anchor.programDateTimeEpochMs, endEpochMs = anchor.programDateTimeEpochMs + 10_000)
            }.fold(
                onSuccess = { range ->
                    _exportStatus.value = "Exporting ${range.startEpochMs}..${range.endEpochMs}..."
                    clipExporter.export(range).fold(
                        onSuccess = { file -> _exportStatus.value = "OK: ${file.absolutePath} (${file.length()} bytes)" },
                        onFailure = { e -> _exportStatus.value = "Export failed: ${e.message}" },
                    )
                },
                onFailure = { e -> _exportStatus.value = "Range selection failed: ${e.message}" },
            )
        }
    }

    override fun onCleared() {
        player.release()
    }
}
