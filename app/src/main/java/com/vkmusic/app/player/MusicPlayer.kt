package com.vkmusic.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vkmusic.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlaybackState(
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val error: String? = null,
    val cacheStats: CacheUiStats = CacheUiStats(),
    val status: String? = null
)

/**
 * Плеер с дисковым кэшем и фоновой докачкой следующих треков (как в Youtube_Voice).
 */
@UnstableApi
class MusicPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefetchMutex = Mutex()

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)

    private val cacheFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(AudioCacheStore.get(appContext))
        .setUpstreamDataSourceFactory(httpFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setCacheKeyFactory { spec ->
            spec.key ?: spec.uri.toString()
        }

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(cacheFactory))
        .build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var queue: List<Track> = emptyList()
    private var urls: Map<String, String> = emptyMap()
    private var prefetchJob: Job? = null
    private var progressJob: Job? = null
    private var lastTrackBytes: Long = 0L

    /** Resolve stream URL for a track (caller provides). */
    var resolveUrl: (suspend (Track) -> String?)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishProgress()
            if (playbackState == Player.STATE_ENDED) {
                playNext()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = player.currentMediaItemIndex
            val track = queue.getOrNull(idx)
            _state.update {
                it.copy(
                    track = track,
                    queueIndex = idx,
                    status = if (track != null && AudioCacheStore.isTrackCached(track.id)) "Оффлайн кэш" else null
                )
            }
            restartPrefetch(idx)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update { it.copy(playing = false, error = error.message ?: "Ошибка воспроизведения") }
        }
    }

    init {
        player.addListener(listener)
        progressJob = scope.launch {
            while (isActive) {
                publishProgress()
                delay(500)
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, resolvedUrls: Map<String, String> = emptyMap()) {
        if (tracks.isEmpty()) return
        queue = tracks
        urls = resolvedUrls
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        val items = tracks.map { track ->
            val url = resolvedUrls[track.id] ?: track.streamUrl.orEmpty()
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(url.ifBlank { "https://invalid.local/${track.id}" })
                .setTag(track)
                .build()
        }
        player.setMediaItems(items, idx, 0L)
        player.prepare()
        player.playWhenReady = true
        _state.update {
            it.copy(
                queue = tracks,
                queueIndex = idx,
                track = tracks[idx],
                playing = true,
                error = null,
                status = null
            )
        }
        restartPrefetch(idx)
    }

    fun playTrackInQueue(track: Track, allTracks: List<Track>, url: String) {
        val merged = if (allTracks.any { it.id == track.id }) allTracks else listOf(track) + allTracks
        val idx = merged.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playQueue(merged, idx, mapOf(track.id to url) + urls)
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun playNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun playPrevious() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        publishProgress()
    }

    fun stop() {
        prefetchJob?.cancel()
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        urls = emptyMap()
        _state.value = PlaybackState(cacheStats = AudioCacheStore.snapshot())
    }

    fun reloadCacheSettings() {
        AudioCacheStore.reloadIfNeeded(appContext)
        val idx = _state.value.queueIndex
        if (idx >= 0) restartPrefetch(idx)
        publishProgress()
    }

    fun clearCache() {
        AudioCacheStore.clear()
        publishProgress()
    }

    fun release() {
        prefetchJob?.cancel()
        progressJob?.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun publishProgress() {
        val track = _state.value.track
        val trackBytes = track?.let { AudioCacheStore.cachedBytesForTrack(it.id) } ?: 0L
        val downloading = trackBytes > lastTrackBytes + 8_192
        lastTrackBytes = trackBytes
        _state.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L),
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                playing = player.isPlaying,
                cacheStats = AudioCacheStore.snapshot(track?.id).copy(isDownloading = downloading)
            )
        }
    }

    private fun restartPrefetch(centerIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val progressed = prefetchMutex.withLock { runPrefetchPass(centerIndex) }
                if (!progressed) delay(2_000)
                else delay(200)
                if (_state.value.queueIndex != centerIndex) break
            }
        }
    }

    private suspend fun runPrefetchPass(centerIndex: Int): Boolean {
        if (queue.isEmpty() || centerIndex !in queue.indices) return false
        val settings = CacheSettingsStore.current()
        var room = (settings.maxCacheBytes - AudioCacheStore.cachedBytes()).coerceAtLeast(0L)
        if (room < 64L * 1024L) return false

        var wrote = false
        val indexes = buildList {
            add(centerIndex)
            for (i in 1..settings.prefetchAhead) {
                val n = centerIndex + i
                if (n in queue.indices) add(n)
            }
        }
        for (idx in indexes) {
            room = (settings.maxCacheBytes - AudioCacheStore.cachedBytes()).coerceAtLeast(0L)
            if (room < 64L * 1024L) break
            val track = queue[idx]
            val url = ensureUrl(track) ?: continue
            // Качаем трек целиком (пачками до 64 МБ), пока источник отдаёт данные и есть место в кэше.
            var already = AudioCacheStore.cachedBytesForTrack(track.id)
            while (true) {
                room = (settings.maxCacheBytes - AudioCacheStore.cachedBytes()).coerceAtLeast(0L)
                if (room < 64L * 1024L) break
                val chunk = minOf(room, 64L * 1024L * 1024L)
                val written = warmUrl(track.id, url, already, chunk)
                if (written <= 0L) break
                already += written
                wrote = true
                Log.i(TAG, "prefetch full ${track.id} +${written}B total=$already")
                withContext(Dispatchers.Main) { publishProgress() }
            }
        }
        return wrote
    }

    private suspend fun ensureUrl(track: Track): String? {
        urls[track.id]?.let { return it }
        track.streamUrl?.takeIf { it.startsWith("http") }?.let {
            urls = urls + (track.id to it)
            return it
        }
        val resolver = resolveUrl ?: return null
        val resolved = runCatching { resolver(track) }.getOrNull()
        if (!resolved.isNullOrBlank() && resolved.startsWith("http")) {
            urls = urls + (track.id to resolved)
            return resolved
        }
        return null
    }

    private fun warmUrl(trackId: String, url: String, already: Long, budget: Long): Long {
        return runCatching {
            var written = 0L
            val dataSource = cacheFactory.createDataSource()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setKey(trackId)
                .setPosition(already)
                .setLength(budget)
                .build()
            CacheWriter(dataSource, spec, /* temporaryBuffer */ null) { _, _, newBytes ->
                written += newBytes
            }.cache()
            written
        }.onFailure {
            Log.w(TAG, "warm failed $trackId: ${it.message}")
        }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "VkMusicPlayer"
    }
}
