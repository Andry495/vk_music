package com.vkmusic.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object AudioCacheStore {
    private const val TAG = "VkMusicCache"
    private const val CACHE_DIR = "vk_music_audio"

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var appliedMaxBytes: Long = 0L

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            return open(context.applicationContext)
        }
    }

    fun reloadIfNeeded(context: Context) {
        val wanted = CacheSettingsStore.current().maxCacheBytes
        synchronized(this) {
            if (cache != null && appliedMaxBytes == wanted) return
            releaseLocked()
            open(context.applicationContext)
        }
    }

    fun cachedBytes(): Long = cache?.cacheSpace ?: 0L

    fun keyCount(): Int = cache?.keys?.size ?: 0

    fun cachedBytesForTrack(trackId: String): Long {
        val c = cache ?: return 0L
        if (trackId.isBlank()) return 0L
        return runCatching {
            c.keys.sumOf { key ->
                if (key == trackId || key.startsWith("$trackId|")) {
                    c.getCachedSpans(key).sumOf { it.length }
                } else {
                    0L
                }
            }
        }.getOrDefault(0L)
    }

    fun isTrackCached(trackId: String, minBytes: Long = 64L * 1024L): Boolean =
        cachedBytesForTrack(trackId) >= minBytes

    fun clear() {
        val c = cache ?: return
        runCatching {
            c.keys.toList().forEach { c.removeResource(it) }
        }.onFailure { Log.w(TAG, "clear failed", it) }
    }

    fun snapshot(trackId: String? = null): CacheUiStats {
        val max = CacheSettingsStore.current().maxCacheBytes
        return CacheUiStats(
            totalBytes = cachedBytes(),
            maxBytes = max,
            totalKeys = keyCount(),
            trackBytes = trackId?.let { cachedBytesForTrack(it) } ?: 0L
        )
    }

    private fun open(context: Context): SimpleCache {
        val maxBytes = CacheSettingsStore.current().maxCacheBytes
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val db = StandaloneDatabaseProvider(context)
        val simple = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), db)
        cache = simple
        appliedMaxBytes = maxBytes
        Log.i(TAG, "opened cache maxMb=${maxBytes / (1024 * 1024)} dir=${dir.absolutePath}")
        return simple
    }

    private fun releaseLocked() {
        runCatching { cache?.release() }
        cache = null
        appliedMaxBytes = 0L
    }
}
