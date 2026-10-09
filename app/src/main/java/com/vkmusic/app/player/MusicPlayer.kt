package com.vkmusic.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vkmusic.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackState(
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)

class MusicPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context.applicationContext).build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    durationMs = player.duration.coerceAtLeast(0L),
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                )
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update { it.copy(playing = false, error = error.message ?: "Ошибка воспроизведения") }
        }
    }

    init {
        player.addListener(listener)
    }

    fun play(track: Track, url: String) {
        _state.value = PlaybackState(track = track, playing = true, error = null)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
