package com.vkmusic.app.vk

import android.util.Log
import com.vkmusic.app.auth.AudioProbeResult
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Музыка через веб-бэкенд VK (al_audio.php) + cookies из WebView.
 * OAuth audio.* API у VK сейчас отвечает error 3 (Unknown method).
 */
class VkWebAudioClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun probe(session: VkSession): AudioProbeResult = withContext(Dispatchers.IO) {
        when {
            session.cookies.isBlank() || !session.cookies.contains("remixsid", ignoreCase = true) ->
                AudioProbeResult.Denied(null, "Нет remixsid в cookies")
            else -> {
                val tracks = fetchMyAudios(session, limit = 5)
                if (tracks.isNotEmpty()) {
                    Log.i(TAG, "web probe ok tracks=${tracks.size} sample=${tracks.first().artist} — ${tracks.first().title}")
                    AudioProbeResult.Ok
                } else {
                    // fallback: старая эвристика по телу ответа
                    sectionRaw(session)?.let { body ->
                        if (looksLikeAudioPayload(body)) AudioProbeResult.Ok
                        else AudioProbeResult.Denied(null, "al_audio без треков")
                    } ?: AudioProbeResult.Denied(null, "al_audio пусто")
                }
            }
        }
    }

    suspend fun fetchMyAudios(session: VkSession, limit: Int = 100): List<Track> =
        withContext(Dispatchers.IO) {
            val sections = listOf("all", "my", "recent", "recoms", "general")
            val merged = linkedMapOf<String, Track>()
            for (section in sections) {
                val body = sectionRaw(session, section) ?: continue
                val emptyLib = body.contains("placeholder_music_no_audios")
                val tracks = parseTracks(body)
                if (section == "general" && tracks.isEmpty() && body.length > 1000) {
                    Log.i(TAG, "general markers audio=${body.contains("audio")} list=${body.contains("\"list\"")} dataAudio=${body.contains("data-audio")} ownerAudio=${body.contains("owner_id")}")
                    val sampleIdx = body.indexOf("audio")
                    if (sampleIdx >= 0) {
                        Log.i(TAG, "general sample@audio=${body.substring(sampleIdx, (sampleIdx + 220).coerceAtMost(body.length)).replace('\n', ' ')}")
                    }
                }
                Log.i(
                    TAG,
                    "section=$section bodyLen=${body.length} emptyLib=$emptyLib parsed=${tracks.size} head=${body.take(160).replace('\n', ' ')}"
                )
                tracks.forEach { merged.putIfAbsent(it.id, it) }
                if (merged.size >= limit) break
            }
            Log.i(TAG, "fetchMyAudios total=${merged.size}")
            merged.values.take(limit)
        }

    private fun sectionRaw(session: VkSession, section: String = "all"): String? {
        val cookies = session.cookies
        if (cookies.isBlank()) return null
        val hosts = listOf("https://vk.ru/al_audio.php", "https://vk.com/al_audio.php")
        for (url in hosts) {
            val body = postSection(url, cookies, session.userId, session.userAgent, section)
            if (!body.isNullOrBlank() && body.length > 80) {
                return body
            }
        }
        return null
    }

    private fun postSection(
        url: String,
        cookies: String,
        userId: Long,
        userAgent: String,
        section: String
    ): String? {
        return try {
            val form = FormBody.Builder()
                .add("al", "1")
                .add("act", "section")
                .add("owner_id", userId.toString())
                .add("section", section)
                .add("claim", "0")
                .build()
            val ua = userAgent.ifBlank { DEFAULT_MOBILE_UA }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://vk.ru/audios$userId")
                .post(form)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "web probe $url section=$section -> HTTP ${response.code}")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "postSection ${e.message}")
            null
        }
    }

    fun parseTracks(body: String): List<Track> {
        val found = linkedMapOf<String, Track>()
        try {
            walkJson(JSONObject(body), found)
        } catch (_: Exception) {
            // иногда payload обёрнут иначе — пробуем найти JSON-массивы в тексте
            val idx = body.indexOf('[')
            if (idx >= 0) {
                runCatching { walkJson(JSONArray(body.substring(idx)), found) }
            }
        }
        return found.values.toList()
    }

    private fun walkJson(node: Any?, out: MutableMap<String, Track>) {
        when (node) {
            is JSONObject -> {
                // playlist.list / audios / list
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walkJson(node.opt(key), out)
                }
            }
            is JSONArray -> {
                if (looksLikeAudioArray(node)) {
                    parseAudioArray(node)?.let { out.putIfAbsent(it.id, it) }
                } else {
                    for (i in 0 until node.length()) {
                        walkJson(node.opt(i), out)
                    }
                }
            }
        }
    }

    /**
     * Классический массив аудио VK:
     * [audio_id, owner_id, url, title, artist, duration, ...]
     */
    private fun looksLikeAudioArray(arr: JSONArray): Boolean {
        if (arr.length() < 6) return false
        val id = arr.optLong(0, Long.MIN_VALUE)
        val owner = arr.optLong(1, Long.MIN_VALUE)
        val title = arr.opt(3)
        val artist = arr.opt(4)
        val duration = arr.opt(5)
        return id != Long.MIN_VALUE &&
            owner != Long.MIN_VALUE &&
            title is String && title.isNotBlank() &&
            artist is String &&
            (duration is Number || (duration is String && duration.toLongOrNull() != null))
    }

    private fun parseAudioArray(arr: JSONArray): Track? {
        val audioId = arr.optLong(0)
        val ownerId = arr.optLong(1)
        if (audioId <= 0L || ownerId == 0L) return null
        val url = arr.optString(2).takeIf { it.startsWith("http") }
        val title = arr.optString(3).ifBlank { "Без названия" }
        val artist = arr.optString(4).ifBlank { "Unknown" }
        val duration = when (val d = arr.opt(5)) {
            is Number -> d.toLong()
            is String -> d.toLongOrNull() ?: 0L
            else -> 0L
        }
        return Track(
            id = "${ownerId}_$audioId",
            ownerId = ownerId,
            audioId = audioId,
            title = title,
            artist = artist,
            durationSeconds = duration,
            streamUrl = url
        )
    }

    private fun looksLikeAudioPayload(body: String): Boolean {
        return body.contains("\"list\"") ||
            body.contains("\"playlist\"") ||
            body.contains("\"audios\"") ||
            (body.contains("\"title\"") && body.contains("\"id\"") && body.length > 200)
    }

    companion object {
        private const val TAG = "VkMusicAuth"
        private const val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        fun collectVkCookies(cookieManagerGet: (String) -> String?): String {
            val hosts = listOf(
                "https://vk.ru",
                "https://m.vk.ru",
                "https://vk.com",
                "https://m.vk.com",
                "https://login.vk.ru",
                "https://login.vk.com"
            )
            val merged = linkedMapOf<String, String>()
            for (host in hosts) {
                val raw = cookieManagerGet(host).orEmpty()
                if (raw.isBlank()) continue
                raw.split(';').forEach { part ->
                    val p = part.trim()
                    val eq = p.indexOf('=')
                    if (eq > 0) {
                        merged[p.substring(0, eq)] = p.substring(eq + 1)
                    }
                }
            }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
