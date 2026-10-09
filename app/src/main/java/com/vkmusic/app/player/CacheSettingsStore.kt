package com.vkmusic.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cacheStore by preferencesDataStore("cache_settings")

data class CacheSettings(
    val maxCacheMb: Int = 256,
    /** Сколько следующих треков докачивать целиком после текущего. */
    val prefetchAhead: Int = 3
) {
    val maxCacheBytes: Long get() = maxCacheMb.toLong() * 1024L * 1024L
}

data class CacheUiStats(
    val totalBytes: Long = 0L,
    val maxBytes: Long = 0L,
    val totalKeys: Int = 0,
    val trackBytes: Long = 0L,
    val isDownloading: Boolean = false
) {
    val totalMbLabel: String get() = "%.0f".format(totalBytes / (1024.0 * 1024.0))
    val maxMbLabel: String get() = "%.0f".format(maxBytes / (1024.0 * 1024.0))
    val trackMbLabel: String get() = "%.1f".format(trackBytes / (1024.0 * 1024.0))
    val diskFill: Float
        get() = if (maxBytes > 0) (totalBytes.toFloat() / maxBytes).coerceIn(0f, 1f) else 0f
    /** Грубая шкала для полоски мини-плеера (~8 МБ ≈ полный трек). */
    val trackFill: Float
        get() = (trackBytes.toFloat() / (8f * 1024f * 1024f)).coerceIn(0f, 1f)
}

object CacheSettingsStore {
    private val KEY_MAX_MB = intPreferencesKey("max_cache_mb")
    private val KEY_PREFETCH_AHEAD = intPreferencesKey("prefetch_ahead")

    val MAX_MB_OPTIONS = listOf(64, 128, 256, 512)
    val PREFETCH_AHEAD_OPTIONS = listOf(1, 2, 3, 5)

    @Volatile
    private var cached = CacheSettings()

    fun current(): CacheSettings = cached

    fun settingsFlow(context: Context): Flow<CacheSettings> =
        context.applicationContext.cacheStore.data.map { prefs ->
            CacheSettings(
                maxCacheMb = (prefs[KEY_MAX_MB] ?: 256).coerceIn(64, 512),
                prefetchAhead = (prefs[KEY_PREFETCH_AHEAD] ?: 3).coerceIn(1, 5)
            ).also { cached = it }
        }

    suspend fun hydrate(context: Context) {
        cached = settingsFlow(context).first()
    }

    suspend fun update(context: Context, transform: (CacheSettings) -> CacheSettings) {
        val next = transform(settingsFlow(context).first())
        cached = next
        context.applicationContext.cacheStore.edit { prefs ->
            prefs[KEY_MAX_MB] = next.maxCacheMb
            prefs[KEY_PREFETCH_AHEAD] = next.prefetchAhead
        }
    }
}
