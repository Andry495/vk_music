package com.vkmusic.app.vk

import android.util.Log
import com.vkmusic.app.auth.AudioProbeResult
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.Track
import com.vkmusic.app.data.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Музыка через веб-бэкенд VK (al_audio.php / m.vk.ru/audio) + cookies из WebView.
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
                val recoms = fetchRecoms(session, limit = 3)
                if (recoms.isNotEmpty()) {
                    Log.i(TAG, "web probe ok via recoms=${recoms.size}")
                    return@withContext AudioProbeResult.Ok
                }
                val search = search(session, "music", limit = 3)
                if (search.isNotEmpty()) {
                    Log.i(TAG, "web probe ok via search=${search.size}")
                    return@withContext AudioProbeResult.Ok
                }
                val mine = fetchMyAudios(session, limit = 3)
                if (mine.isNotEmpty()) {
                    Log.i(TAG, "web probe ok via library=${mine.size}")
                    return@withContext AudioProbeResult.Ok
                }
                sectionRaw(session, "all")?.let { body ->
                    if (looksLikeAudioPayload(body) || body.contains("placeholder_music_no_audios")) {
                        AudioProbeResult.Ok
                    } else {
                        AudioProbeResult.Denied(null, "al_audio без треков")
                    }
                } ?: AudioProbeResult.Denied(null, "al_audio пусто")
            }
        }
    }

    suspend fun fetchMyAudios(session: VkSession, limit: Int = 100): List<Track> =
        withContext(Dispatchers.IO) {
            val sections = listOf("all", "my", "recent")
            val merged = linkedMapOf<String, Track>()
            for (section in sections) {
                val body = sectionRaw(session, section) ?: continue
                val tracks = parseTracks(body, TrackSource.Library)
                Log.i(TAG, "section=$section bodyLen=${body.length} parsed=${tracks.size}")
                tracks.forEach { merged.putIfAbsent(it.id, it) }
                if (merged.size >= limit) break
            }
            merged.values.take(limit)
        }

    suspend fun fetchRecoms(session: VkSession, limit: Int = 80): List<Track> =
        withContext(Dispatchers.IO) {
            val merged = linkedMapOf<String, Track>()

            // 1) Chart / new songs через страницу /audio (как vk_api.get_popular_iter)
            for (block in listOf("chart", "new_songs")) {
                val body = postAudioPage(session, block = block, section = "recoms")
                if (body.isNullOrBlank()) continue
                val fromPage = parseAudioPageTracks(body, TrackSource.Recoms)
                Log.i(TAG, "recoms page block=$block bodyLen=${body.length} parsed=${fromPage.size}")
                fromPage.forEach { merged.putIfAbsent(it.id, it) }
                if (merged.size >= limit) break
            }

            // 2) al_audio section=recoms
            if (merged.size < limit) {
                val body = sectionRaw(session, "recoms")
                if (!body.isNullOrBlank()) {
                    val tracks = parseTracks(body, TrackSource.Recoms)
                    Log.i(TAG, "section=recoms bodyLen=${body.length} parsed=${tracks.size}")
                    tracks.forEach { merged.putIfAbsent(it.id, it) }
                }
            }

            merged.values.take(limit)
        }

    suspend fun search(session: VkSession, query: String, limit: Int = 80): List<Track> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val body = postAlAudio(
                session,
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "section")
                    .add("claim", "0")
                    .add("is_layer", "0")
                    .add("owner_id", session.userId.toString())
                    .add("section", "search")
                    .add("q", q)
                    .build()
            ) ?: return@withContext emptyList()
            val tracks = parseTracks(body, TrackSource.Search)
            Log.i(TAG, "search q='$q' bodyLen=${body.length} parsed=${tracks.size}")
            tracks.take(limit)
        }

    /**
     * Получает playable URL через act=reload_audio (пачки по 10).
     */
    suspend fun resolveStreamUrls(session: VkSession, tracks: List<Track>): List<Track> =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext emptyList()
            val byId = tracks.associateBy { it.id }.toMutableMap()
            val needReload = tracks.filter {
                it.streamUrl.isNullOrBlank() || it.streamUrl.contains("audio_api_unavailable")
            }
            val keys = needReload.mapNotNull { it.reloadKey }.distinct()
            for (chunk in keys.chunked(10)) {
                delay(400)
                val reloaded = reloadAudio(session, chunk)
                reloaded.forEach { t -> byId[t.id] = t }
            }
            tracks.map { byId[it.id] ?: it }
        }

    suspend fun resolveStreamUrl(session: VkSession, track: Track): Track =
        resolveStreamUrls(session, listOf(track)).firstOrNull() ?: track

    private fun reloadAudio(session: VkSession, ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val idParam = ids.joinToString(",")
        val endpoints = listOf(
            "https://m.vk.ru/audio" to FormBody.Builder()
                .add("act", "reload_audio")
                .add("ids", idParam)
                .build(),
            "https://vk.ru/al_audio.php" to FormBody.Builder()
                .add("al", "1")
                .add("act", "reload_audio")
                .add("ids", idParam)
                .build()
        )
        for ((url, form) in endpoints) {
            val body = postRaw(url, session, form) ?: continue
            val tracks = parseTracks(body, TrackSource.Library).map { t ->
                val urlDecoded = t.streamUrl?.let { u ->
                    when {
                        u.contains("audio_api_unavailable") || u.contains("?extra=") ->
                            AudioUrlDecoder.decode(u, session.userId) ?: u
                        else -> u
                    }
                }?.let { convertM3u8IfNeeded(it) }
                t.copy(streamUrl = urlDecoded)
            }.filter { !it.streamUrl.isNullOrBlank() && it.streamUrl!!.startsWith("http") }
            Log.i(TAG, "reload_audio via=$url ids=${ids.size} resolved=${tracks.size}")
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private fun sectionRaw(session: VkSession, section: String = "all"): String? {
        return postAlAudio(
            session,
            FormBody.Builder()
                .add("al", "1")
                .add("act", "section")
                .add("owner_id", session.userId.toString())
                .add("section", section)
                .add("claim", "0")
                .build()
        )
    }

    private fun postAudioPage(session: VkSession, block: String, section: String): String? {
        val form = FormBody.Builder()
            .add("block", block)
            .add("section", section)
            .build()
        return postRaw("https://vk.ru/audio", session, form)
            ?: postRaw("https://vk.com/audio", session, form)
    }

    private fun postAlAudio(session: VkSession, form: FormBody): String? {
        val hosts = listOf("https://vk.ru/al_audio.php", "https://vk.com/al_audio.php")
        for (url in hosts) {
            val body = postRaw(url, session, form)
            if (!body.isNullOrBlank() && body.length > 40) return body
        }
        return null
    }

    private fun postRaw(url: String, session: VkSession, form: FormBody): String? {
        return try {
            val ua = session.userAgent.ifBlank { DEFAULT_MOBILE_UA }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Cookie", enrichCookies(session.cookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://vk.ru/audios${session.userId}")
                .post(form)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST $url -> HTTP ${response.code}")
                    return null
                }
                response.body?.string()?.removePrefix("<!--")
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $url ${e.message}")
            null
        }
    }

    private fun enrichCookies(cookies: String): String {
        var c = cookies
        if (!c.contains("remixaudio_show_alert_today")) {
            c += "; remixaudio_show_alert_today=0"
        }
        if (!c.contains("remixmdevice")) {
            c += "; remixmdevice=1920/1080/2/!!-!!!!"
        }
        return c
    }

    private fun parseAudioPageTracks(html: String, source: TrackSource): List<Track> {
        val json = scrapAudioPageJson(html) ?: return parseTracks(html, source)
        return parseTracks(json, source)
    }

    private fun scrapAudioPageJson(html: String): String? {
        val matcher = AUDIO_PAGE_JSON.matcher(html)
        if (!matcher.find()) return null
        return matcher.group(1)
    }

    fun parseTracks(body: String, source: TrackSource = TrackSource.Library): List<Track> {
        val found = linkedMapOf<String, Track>()
        val cleaned = body.removePrefix("<!--").trim()
        try {
            walkJson(JSONObject(cleaned), found, source)
        } catch (_: Exception) {
            val idx = cleaned.indexOf('[')
            if (idx >= 0) {
                runCatching { walkJson(JSONArray(cleaned.substring(idx)), found, source) }
            }
            // иногда ответ обёрнут как {"data":[...]}
            runCatching {
                val dataIdx = cleaned.indexOf("\"data\"")
                if (dataIdx >= 0) walkJson(JSONObject(cleaned), found, source)
            }
        }
        // HTML data-audio="[...]"
        extractDataAudio(cleaned, found, source)
        return found.values.toList()
    }

    private fun extractDataAudio(
        html: String,
        out: MutableMap<String, Track>,
        source: TrackSource
    ) {
        var from = 0
        while (true) {
            val key = html.indexOf("data-audio=\"", from)
            if (key < 0) break
            val start = key + "data-audio=\"".length
            val end = html.indexOf('"', start)
            if (end < 0) break
            from = end + 1
            val raw = html.substring(start, end)
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
            runCatching {
                walkJson(JSONArray(raw), out, source)
            }
        }
    }

    private fun walkJson(node: Any?, out: MutableMap<String, Track>, source: TrackSource) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlist")?.let { walkJson(it, out, source) }
                node.optJSONArray("playlists")?.let { walkJson(it, out, source) }
                node.optJSONArray("list")?.let { walkJson(it, out, source) }
                node.optJSONArray("data")?.let { walkJson(it, out, source) }
                node.optJSONObject("sectionData")?.let { walkJson(it, out, source) }
                node.optJSONObject("recoms")?.let { walkJson(it, out, source) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkJson(node.opt(keys.next()), out, source)
                }
            }
            is JSONArray -> {
                if (looksLikeAudioArray(node)) {
                    parseAudioArray(node, source)?.let { out.putIfAbsent(it.id, it) }
                } else {
                    for (i in 0 until node.length()) {
                        walkJson(node.opt(i), out, source)
                    }
                }
            }
        }
    }

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

    private fun parseAudioArray(arr: JSONArray, source: TrackSource): Track? {
        val audioId = arr.optLong(0)
        val ownerId = arr.optLong(1)
        if (audioId <= 0L || ownerId == 0L) return null
        val rawUrl = arr.optString(2)
        val url = when {
            rawUrl.startsWith("http") -> convertM3u8IfNeeded(rawUrl)
            else -> null
        }
        val title = stripHtml(arr.optString(3)).ifBlank { "Без названия" }
        val artist = stripHtml(arr.optString(4)).ifBlank { "Unknown" }
        val duration = when (val d = arr.opt(5)) {
            is Number -> d.toLong()
            is String -> d.toLongOrNull() ?: 0L
            else -> 0L
        }
        val hashes = arr.optString(13).split('/')
        val actionHash = hashes.getOrNull(2).orEmpty()
        val urlHash = hashes.getOrNull(5).orEmpty()
        val reloadKey = if (actionHash.isNotBlank() && urlHash.isNotBlank()) {
            "${ownerId}_${audioId}_${actionHash}_$urlHash"
        } else {
            null
        }
        val cover = arr.optString(14).split(',').firstOrNull { it.startsWith("http") }
        return Track(
            id = "${ownerId}_$audioId",
            ownerId = ownerId,
            audioId = audioId,
            title = title,
            artist = artist,
            durationSeconds = duration,
            streamUrl = url,
            thumbnailUrl = cover,
            reloadKey = reloadKey,
            source = source
        )
    }

    private fun convertM3u8IfNeeded(url: String): String {
        if (!url.contains("m3u8")) return url
        return M3U8_TO_MP3.matcher(url).replaceAll("$1/$2.mp3")
    }

    private fun stripHtml(value: String): String =
        value.replace(Regex("<[^>]+>"), "").trim()

    private fun looksLikeAudioPayload(body: String): Boolean {
        return body.contains("\"list\"") ||
            body.contains("\"playlist\"") ||
            body.contains("\"audios\"") ||
            body.contains("data-audio") ||
            (body.contains("\"title\"") && body.contains("\"id\"") && body.length > 200)
    }

    companion object {
        private const val TAG = "VkMusicAuth"
        private const val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private val AUDIO_PAGE_JSON: Pattern =
            Pattern.compile("new AudioPage\\(.*?(\\{.*\\})", Pattern.DOTALL)
        private val M3U8_TO_MP3: Pattern =
            Pattern.compile("/[0-9a-f]+(/audios)?/([0-9a-f]+)/index\\.m3u8")

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
