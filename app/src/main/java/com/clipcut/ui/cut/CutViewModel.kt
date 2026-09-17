package com.clipcut.ui.cut

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.stream.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CutViewModel @Inject constructor(
    @ApplicationContext context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    init {
        viewModelScope.launch {
            val url = playlistRepository.currentPlaylistUrl()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun onCleared() {
        player.release()
    }
}
