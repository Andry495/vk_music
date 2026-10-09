package com.vkmusic.app.vk

import android.util.Log
import com.vkmusic.app.auth.AudioProbeResult
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.LibraryActionResult
import com.vkmusic.app.data.Playlist
import com.vkmusic.app.data.Track
import com.vkmusic.app.data.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Музыка — веб (al_audio / m.vk) + cookies Kate.
 * Плейлисты: список/треки/add/remove — веб + edit_hash;
 * create/resolve hash — веб, при отсутствии hash один точечный вызов Kate API
 * (без циклов getPlaylists/addToPlaylist, которые ловят Flood).
 */
class VkWebAudioClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    /** edit_hash плейлиста — кэш на сессию (HTML-список часто его не отдаёт). */
    private val editHashCache = ConcurrentHashMap<String, String>()

    /** Общий hash со страницы аудио — нужен для create/save_playlist. */
    @Volatile
    private var audioPageHash: String? = null

    private fun rememberHashes(pl: Playlist): Playlist {
        pl.editHash?.takeIf { it.isNotBlank() }?.let {
            editHashCache[pl.fullId] = it
            editHashCache["?_${pl.id}"] = it
        }
        val cached = editHashCache[pl.fullId] ?: editHashCache["?_${pl.id}"]
        return if (pl.editHash.isNullOrBlank() && !cached.isNullOrBlank()) {
            pl.copy(editHash = cached)
        } else {
            pl
        }
    }

    private fun forgetPlaylist(fullId: String) {
        editHashCache.remove(fullId)
    }
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

    suspend fun addToMyMusic(session: VkSession, track: Track): LibraryActionResult =
        withContext(Dispatchers.IO) {
            val hash = track.addHash
            if (hash.isNullOrBlank()) {
                return@withContext LibraryActionResult.Error("Нет add_hash — VK не даёт добавить этот трек")
            }
            val attempts = listOf(
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "add")
                    .add("audio_id", track.audioId.toString())
                    .add("audio_owner_id", track.ownerId.toString())
                    .add("hash", hash)
                    .add("group_id", "0")
                    .apply { track.trackCode?.let { add("track_code", it) } }
                    .build(),
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "add")
                    .add("aid", track.audioId.toString())
                    .add("oid", track.ownerId.toString())
                    .add("hash", hash)
                    .build()
            )
            for (form in attempts) {
                val body = postAlAudio(session, form) ?: continue
                Log.i(TAG, "addToMyMusic bodyLen=${body.length} head=${body.take(120)}")
                if (isActionSuccess(body)) return@withContext LibraryActionResult.Ok
            }
            LibraryActionResult.Error("Не удалось добавить в Мою музыку")
        }

    suspend fun removeFromMyMusic(session: VkSession, track: Track): LibraryActionResult =
        withContext(Dispatchers.IO) {
            val hash = track.deleteHash
            if (hash.isNullOrBlank()) {
                return@withContext LibraryActionResult.Error("Нет delete_hash")
            }
            val form = FormBody.Builder()
                .add("al", "1")
                .add("act", "delete_audio")
                .add("aid", track.audioId.toString())
                .add("oid", track.ownerId.toString())
                .add("hash", hash)
                .add("restore", "1")
                .apply { track.trackCode?.let { add("track_code", it) } }
                .build()
            val body = postAlAudio(session, form)
                ?: return@withContext LibraryActionResult.Error("Пустой ответ VK")
            Log.i(TAG, "removeFromMyMusic bodyLen=${body.length}")
            if (isActionSuccess(body)) LibraryActionResult.Ok
            else LibraryActionResult.Error("Не удалось удалить из Моей музыки")
        }

    suspend fun fetchPlaylists(session: VkSession): List<Playlist> =
        withContext(Dispatchers.IO) {
            // hash для create не блокируем список — иначе пикер пустой 4–8с
            val merged = linkedMapOf<String, Playlist>()
            val html = getRaw(
                "https://m.vk.ru/audio?act=audio_playlists${session.userId}",
                session
            ) ?: getRaw("https://m.vk.com/audio?act=audio_playlists${session.userId}", session)
            if (!html.isNullOrBlank()) {
                scrapePageHashes(html)
                parsePlaylistsHtml(html, session.userId).forEach { merged[it.fullId] = it }
                logHashHints(html, "playlistsHtml")
            }
            val body = sectionRaw(session, "playlists")
            if (!body.isNullOrBlank()) {
                scrapePageHashes(body)
                extractPageHashFromAlPayload(body)
                parsePlaylistsJson(body, session.userId).forEach { merged.putIfAbsent(it.fullId, it) }
                logHashHints(body, "sectionPlaylists")
            }
            val result = merged.values.map { rememberHashes(it) }
            Log.i(TAG, "fetchPlaylists count=${result.size} pageHash=${!audioPageHash.isNullOrBlank()}")
            result
        }

    suspend fun fetchPlaylistTracks(
        session: VkSession,
        playlist: Playlist,
        limit: Int = 200
    ): List<Track> = withContext(Dispatchers.IO) {
        val enriched = resolvePlaylist(session, playlist)
        val form = FormBody.Builder()
            .add("act", "load_section")
            .add("owner_id", enriched.ownerId.toString())
            .add("playlist_id", enriched.id.toString())
            .add("offset", "0")
            .add("type", "playlist")
            .add("access_hash", enriched.accessHash.orEmpty())
            .add("is_loading_all", "1")
            .build()
        val body = postRaw("https://m.vk.ru/audio", session, form)
            ?: postRaw("https://m.vk.com/audio", session, form)
            ?: postAlAudio(
                session,
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "load_section")
                    .add("owner_id", enriched.ownerId.toString())
                    .add("playlist_id", enriched.id.toString())
                    .add("offset", "0")
                    .add("type", "playlist")
                    .add("access_hash", enriched.accessHash.orEmpty())
                    .build()
            )
        if (body.isNullOrBlank()) return@withContext emptyList()
        scrapePageHashes(body)
        parsePlaylistsJson(body, session.userId).forEach { rememberHashes(it) }
        val tracks = parseTracks(body, TrackSource.Playlist)
        Log.i(
            TAG,
            "playlist ${enriched.fullId} tracks=${tracks.size} editHash=${!enriched.editHash.isNullOrBlank()}"
        )
        tracks.take(limit)
    }

    /**
     * Достаёт edit_hash/access_hash со страницы плейлиста / load_section.
     * Без edit_hash VK отвечает «Ошибка доступа» на add/delete.
     */
    suspend fun resolvePlaylist(session: VkSession, playlist: Playlist): Playlist =
        withContext(Dispatchers.IO) {
            var current = rememberHashes(playlist)
            if (!current.editHash.isNullOrBlank()) return@withContext current

            val page = getRaw(
                "https://m.vk.ru/audio?act=audio_playlist${current.ownerId}_${current.id}" +
                    (current.accessHash?.let { "&access_hash=$it" } ?: ""),
                session
            ) ?: getRaw(
                "https://m.vk.com/audio?act=audio_playlist${current.ownerId}_${current.id}",
                session
            )
            if (!page.isNullOrBlank()) {
                scrapePageHashes(page)
                parsePlaylistsJson(page, session.userId)
                    .firstOrNull { it.fullId == current.fullId }
                    ?.let { current = rememberHashes(mergePlaylist(current, it)) }
                extractEditHashNearId(page, current.id)?.let { h ->
                    current = rememberHashes(current.copy(editHash = h))
                }
            }

            if (current.editHash.isNullOrBlank()) {
                val body = postAlAudio(
                    session,
                    FormBody.Builder()
                        .add("al", "1")
                        .add("act", "load_section")
                        .add("owner_id", current.ownerId.toString())
                        .add("playlist_id", current.id.toString())
                        .add("offset", "0")
                        .add("type", "playlist")
                        .add("access_hash", current.accessHash.orEmpty())
                        .build()
                )
                if (!body.isNullOrBlank()) {
                    scrapePageHashes(body)
                    parsePlaylistsJson(body, session.userId)
                        .firstOrNull { it.fullId == current.fullId || it.id == current.id }
                        ?.let { current = rememberHashes(mergePlaylist(current, it)) }
                    extractEditHashNearId(body, current.id)?.let { h ->
                        current = rememberHashes(current.copy(editHash = h))
                    }
                }
            }
            if (current.editHash.isNullOrBlank()) {
                fetchPlaylistByIdApi(session, current)?.let {
                    current = rememberHashes(mergePlaylist(current, it))
                }
            }
            Log.i(TAG, "resolvePlaylist ${current.fullId} editHash=${current.editHash != null}")
            current
        }

    suspend fun createPlaylist(
        session: VkSession,
        title: String,
        description: String = ""
    ): Playlist? = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isEmpty()) return@withContext null
        val beforeIds = fetchPlaylists(session).map { it.fullId }.toSet()
        val pageHash = ensureAudioPageHash(session)
        if (!pageHash.isNullOrBlank()) {
            val forms = listOf(
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "save_playlist")
                    .add("owner_id", session.userId.toString())
                    .add("playlist_id", "0")
                    .add("title", t)
                    .add("description", description)
                    .add("hash", pageHash)
                    .build(),
                FormBody.Builder()
                    .add("al", "1")
                    .add("act", "create_playlist")
                    .add("owner_id", session.userId.toString())
                    .add("title", t)
                    .add("description", description)
                    .add("hash", pageHash)
                    .build()
            )
            for (form in forms) {
                val body = postAlAudio(session, form) ?: continue
                Log.i(TAG, "createPlaylist web bodyLen=${body.length} head=${body.take(160)}")
                if (!isMutationSuccess(body)) continue
                scrapePageHashes(body)
                parseCreatedPlaylist(body, session.userId, t)?.let {
                    return@withContext rememberHashes(it)
                }
            }
        } else {
            Log.w(TAG, "createPlaylist: no audioPageHash — web skip, try Kate API")
        }
        // Один вызов Kate API: даёт id+edit_hash без flood (в отличие от циклов getPlaylists/add)
        createPlaylistViaApi(session, t)?.let { return@withContext rememberHashes(it) }
        delay(700)
        fetchPlaylists(session).firstOrNull {
            it.title.equals(t, ignoreCase = true) && it.fullId !in beforeIds
        }?.let { rememberHashes(it) }
            ?: fetchPlaylists(session).firstOrNull { it.title.equals(t, ignoreCase = true) }
                ?.let { rememberHashes(it) }
    }

    suspend fun addToPlaylist(
        session: VkSession,
        playlist: Playlist,
        track: Track
    ): LibraryActionResult = withContext(Dispatchers.IO) {
        val enriched = resolvePlaylist(session, playlist)
        val hash = enriched.editHash
        if (hash.isNullOrBlank()) {
            Log.w(TAG, "addToPlaylist: no edit_hash for ${enriched.fullId}")
            return@withContext LibraryActionResult.Error(
                "Нет edit_hash для «${playlist.title}» — откройте плейлист и повторите"
            )
        }
        val simple = "${track.ownerId}_${track.audioId}"
        val full = audioFullId(track)
        val attempts = listOf(
            FormBody.Builder()
                .add("al", "1")
                .add("act", "add_audio_to_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("audio_ids", simple)
                .add("hash", hash)
                .apply { enriched.accessHash?.let { add("access_hash", it) } }
                .build(),
            FormBody.Builder()
                .add("al", "1")
                .add("act", "save_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("title", enriched.title)
                .add("description", "")
                .add("audio_ids_to_add", full)
                .add("hash", hash)
                .apply { enriched.accessHash?.let { add("access_hash", it) } }
                .build(),
            FormBody.Builder()
                .add("al", "1")
                .add("act", "save_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("title", enriched.title)
                .add("description", "")
                .add("audio_ids_to_add", simple)
                .add("hash", hash)
                .build()
        )
        for (form in attempts) {
            val body = postAlAudio(session, form) ?: continue
            Log.i(TAG, "addToPlaylist bodyLen=${body.length} head=${body.take(160)}")
            if (isMutationSuccess(body)) return@withContext LibraryActionResult.Ok
        }
        LibraryActionResult.Error("Не удалось добавить в плейлист «${playlist.title}»")
    }

    suspend fun removeFromPlaylist(
        session: VkSession,
        playlist: Playlist,
        track: Track
    ): LibraryActionResult = withContext(Dispatchers.IO) {
        val enriched = resolvePlaylist(session, playlist)
        val hash = enriched.editHash
            ?: return@withContext LibraryActionResult.Error("Нет edit_hash для удаления из плейлиста")
        val simple = "${track.ownerId}_${track.audioId}"
        val attempts = listOf(
            FormBody.Builder()
                .add("al", "1")
                .add("act", "remove_audio_from_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("audio_ids", simple)
                .add("hash", hash)
                .apply { enriched.accessHash?.let { add("access_hash", it) } }
                .build(),
            FormBody.Builder()
                .add("al", "1")
                .add("act", "save_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("title", enriched.title)
                .add("description", "")
                .add("audio_ids_to_remove", simple)
                .add("hash", hash)
                .build()
        )
        for (form in attempts) {
            val body = postAlAudio(session, form) ?: continue
            Log.i(TAG, "removeFromPlaylist bodyLen=${body.length} head=${body.take(120)}")
            if (isMutationSuccess(body)) return@withContext LibraryActionResult.Ok
        }
        LibraryActionResult.Error("Не удалось удалить из плейлиста «${playlist.title}»")
    }

    suspend fun deletePlaylist(
        session: VkSession,
        playlist: Playlist
    ): LibraryActionResult = withContext(Dispatchers.IO) {
        if (playlist.ownerId != session.userId) {
            return@withContext LibraryActionResult.Error("Можно удалить только свой плейлист")
        }
        val enriched = resolvePlaylist(session, playlist)
        val hash = enriched.editHash
            ?: return@withContext LibraryActionResult.Error("Нет edit_hash для удаления плейлиста")
        val forms = listOf(
            FormBody.Builder()
                .add("al", "1")
                .add("act", "delete_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("hash", hash)
                .build(),
            FormBody.Builder()
                .add("al", "1")
                .add("act", "delete_playlist")
                .add("owner_id", enriched.ownerId.toString())
                .add("playlist_id", enriched.id.toString())
                .add("hash", hash)
                .apply { enriched.accessHash?.let { add("access_hash", it) } }
                .build()
        )
        for (form in forms) {
            val body = postAlAudio(session, form) ?: continue
            Log.i(TAG, "deletePlaylist bodyLen=${body.length} head=${body.take(120)}")
            if (isMutationSuccess(body)) {
                forgetPlaylist(enriched.fullId)
                editHashCache.remove("?_${enriched.id}")
                return@withContext LibraryActionResult.Ok
            }
        }
        // fallback: Kate API один раз, если есть edit_hash
        if (deletePlaylistViaApi(session, enriched)) {
            forgetPlaylist(enriched.fullId)
            editHashCache.remove("?_${enriched.id}")
            return@withContext LibraryActionResult.Ok
        }
        LibraryActionResult.Error("Не удалось удалить плейлист «${playlist.title}»")
    }

    private fun audioFullId(track: Track): String = buildString {
        append(track.ownerId).append('_').append(track.audioId)
        if (!track.accessKey.isNullOrBlank()) append('_').append(track.accessKey)
    }

    private fun mergePlaylist(base: Playlist, extra: Playlist): Playlist = base.copy(
        title = extra.title.ifBlank { base.title },
        trackCount = maxOf(base.trackCount, extra.trackCount),
        accessHash = extra.accessHash ?: base.accessHash,
        editHash = extra.editHash ?: base.editHash,
        coverUrl = extra.coverUrl ?: base.coverUrl
    )

    private suspend fun ensureAudioPageHash(session: VkSession): String? {
        audioPageHash?.let { return it }
        // Desktop UA: AudioPage JSON с hash есть на полной странице, не в Kate Mobile
        val pages = listOf(
            "https://vk.ru/audios${session.userId}",
            "https://vk.com/audios${session.userId}",
            "https://vk.ru/audio",
            "https://m.vk.ru/audio?act=audio_playlists${session.userId}"
        )
        for (url in pages) {
            val html = getRaw(url, session, uaOverride = DESKTOP_UA)
                ?: getRaw(url, session)
                ?: continue
            Log.i(
                TAG,
                "hashProbe $url len=${html.length} AudioPage=${html.contains("AudioPage")} " +
                    "edit_hash=${html.contains("edit_hash")} login=${html.contains("login_form") || html.contains("name=\"email\"")}"
            )
            scrapePageHashes(html)
            if (!audioPageHash.isNullOrBlank()) break
        }
        if (audioPageHash.isNullOrBlank()) {
            sectionRaw(session, "playlists")?.let { body ->
                Log.i(TAG, "hashProbe section=playlists len=${body.length} edit_hash=${body.contains("edit_hash")}")
                scrapePageHashes(body)
                extractPageHashFromAlPayload(body)
            }
            sectionRaw(session, "all")?.let { body ->
                scrapePageHashes(body)
                extractPageHashFromAlPayload(body)
            }
        }
        Log.i(TAG, "audioPageHash len=${audioPageHash?.length ?: 0}")
        return audioPageHash
    }

    private fun logHashHints(body: String, tag: String) {
        val keys = listOf("edit_hash", "editHash", "AudioPage", "\"hash\"", "playlist_id", "save_playlist")
        val found = keys.filter { body.contains(it, ignoreCase = true) }
        val sampleIdx = body.indexOf("playlist", ignoreCase = true).takeIf { it >= 0 }
            ?: body.indexOf("\"id\"")
        val sample = if (sampleIdx >= 0) {
            body.substring(sampleIdx, (sampleIdx + 280).coerceAtMost(body.length))
                .replace('\n', ' ')
        } else {
            body.take(200)
        }
        Log.i(TAG, "hashHints[$tag] keys=$found sample=$sample")
    }

    private fun extractPageHashFromAlPayload(body: String) {
        if (!audioPageHash.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(body.removePrefix("<!--").trim().let {
                if (it.startsWith("{")) it else it.substring(it.indexOf('{').coerceAtLeast(0))
            })
            val payload = json.optJSONArray("payload") ?: return
            fun walk(n: Any?, depth: Int) {
                if (depth > 8 || !audioPageHash.isNullOrBlank()) return
                when (n) {
                    is JSONObject -> {
                        val h = n.optString("hash")
                        if (h.length in 8..64 &&
                            (n.has("sectionId") || n.has("ownerId") || n.has("id") && n.has("nextFrom"))
                        ) {
                            audioPageHash = h
                            return
                        }
                        val keys = n.keys()
                        while (keys.hasNext() && audioPageHash.isNullOrBlank()) {
                            walk(n.opt(keys.next()), depth + 1)
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until n.length()) walk(n.opt(i), depth + 1)
                    }
                }
            }
            walk(payload, 0)
        }
    }

    private fun scrapePageHashes(body: String) {
        if (audioPageHash.isNullOrBlank()) {
            val fromAudioPage = AUDIO_PAGE_JSON.matcher(body).takeIf { it.find() }?.group(1)
            if (!fromAudioPage.isNullOrBlank()) {
                Regex("\"hash\"\\s*:\\s*\"([a-zA-Z0-9_-]{6,})\"").find(fromAudioPage)
                    ?.groupValues?.getOrNull(1)
                    ?.let { audioPageHash = it }
            }
        }
        if (audioPageHash.isNullOrBlank()) {
            val patterns = listOf(
                Regex("AudioPage\\([^)]*,\\s*\\{[\\s\\S]{0,800}?\"hash\"\\s*:\\s*\"([a-zA-Z0-9_-]{6,})\""),
                Regex("\"audioPageHash\"\\s*:\\s*\"([a-zA-Z0-9_-]{6,})\""),
                Regex("cur\\.audioHash\\s*=\\s*'([a-zA-Z0-9_-]{6,})'"),
                Regex("\"hash\"\\s*:\\s*\"([a-fA-F0-9]{8,40})\"")
            )
            for (p in patterns) {
                val h = p.find(body)?.groupValues?.getOrNull(1)
                if (!h.isNullOrBlank() && h.length in 6..64) {
                    audioPageHash = h
                    break
                }
            }
        }
        val editRe = Regex(
            "\"(?:id|playlist_id)\"\\s*:\\s*(\\d+)[\\s\\S]{0,240}?\"edit_hash\"\\s*:\\s*\"([^\"]+)\"|" +
                "\"edit_hash\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,240}?\"(?:id|playlist_id)\"\\s*:\\s*(\\d+)"
        )
        editRe.findAll(body).forEach { m ->
            val id = m.groupValues[1].ifBlank { m.groupValues[4] }.toLongOrNull() ?: return@forEach
            val hash = m.groupValues[2].ifBlank { m.groupValues[3] }
            if (hash.isNotBlank() && id > 0) {
                editHashCache.putIfAbsent("?_$id", hash)
            }
        }
    }

    private fun extractEditHashNearId(body: String, playlistId: Long): String? {
        if (playlistId <= 0) {
            return Regex("\"edit_hash\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        }
        val window = Regex(
            "\"(?:id|playlist_id)\"\\s*:\\s*$playlistId[\\s\\S]{0,400}?\"edit_hash\"\\s*:\\s*\"([^\"]+)\"|" +
                "\"edit_hash\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,400}?\"(?:id|playlist_id)\"\\s*:\\s*$playlistId"
        ).find(body)
        return window?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: editHashCache["?_$playlistId"]
    }

    private fun parseCreatedPlaylist(body: String, userId: Long, title: String): Playlist? {
        parsePlaylistsJson(body, userId)
            .firstOrNull { it.title.equals(title, ignoreCase = true) }
            ?.let { return it }
        // ответ save_playlist часто: payload[1][0] = {id, owner_id, title, edit_hash}
        val compact = Regex(
            "\"id\"\\s*:\\s*(\\d+)[\\s\\S]{0,300}?\"owner_id\"\\s*:\\s*(-?\\d+)[\\s\\S]{0,300}?\"edit_hash\"\\s*:\\s*\"([^\"]+)\""
        ).find(body)
        if (compact != null) {
            val id = compact.groupValues[1].toLongOrNull() ?: return null
            val owner = compact.groupValues[2].toLongOrNull() ?: userId
            return Playlist(
                id = id,
                ownerId = owner,
                title = title,
                editHash = compact.groupValues[3]
            )
        }
        return null
    }

    private fun fetchPlaylistByIdApi(session: VkSession, playlist: Playlist): Playlist? {
        if (session.accessToken.isBlank()) return null
        return try {
            val url = ("https://api.vk.ru/method/audio.getPlaylistById").toHttpUrl().newBuilder()
                .addQueryParameter("access_token", session.accessToken)
                .addQueryParameter("v", "5.199")
                .addQueryParameter("owner_id", playlist.ownerId.toString())
                .addQueryParameter("playlist_id", playlist.id.toString())
                .apply {
                    playlist.accessHash?.let { addQueryParameter("access_key", it) }
                }
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", session.userAgent.ifBlank { VkClientProfiles.Kate.userAgent })
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "getPlaylistById head=${body.take(200)}")
                val json = JSONObject(body)
                if (json.has("error")) return null
                val o = json.optJSONObject("response") ?: return null
                Playlist(
                    id = o.optLong("id", playlist.id),
                    ownerId = o.optLong("owner_id", playlist.ownerId),
                    title = o.optString("title").ifBlank { playlist.title },
                    trackCount = o.optInt("count", playlist.trackCount),
                    accessHash = o.optString("access_hash").ifBlank { playlist.accessHash },
                    editHash = o.optString("edit_hash").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylistById ${e.message}")
            null
        }
    }

    private fun createPlaylistViaApi(session: VkSession, title: String): Playlist? {
        if (session.accessToken.isBlank()) return null
        return try {
            val url = ("https://api.vk.ru/method/audio.createPlaylist").toHttpUrl().newBuilder()
                .addQueryParameter("access_token", session.accessToken)
                .addQueryParameter("v", "5.199")
                .addQueryParameter("owner_id", session.userId.toString())
                .addQueryParameter("title", title)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", session.userAgent.ifBlank { VkClientProfiles.Kate.userAgent })
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "createPlaylistViaApi HTTP ${resp.code} head=${body.take(220)}")
                val json = JSONObject(body)
                if (json.has("error")) {
                    Log.w(TAG, "createPlaylistViaApi err=${json.optJSONObject("error")}")
                    return null
                }
                val o = json.optJSONObject("response") ?: return null
                val id = o.optLong("id", 0L)
                if (id <= 0) return null
                Playlist(
                    id = id,
                    ownerId = o.optLong("owner_id", session.userId),
                    title = o.optString("title").ifBlank { title },
                    trackCount = o.optInt("count", 0),
                    accessHash = o.optString("access_hash").ifBlank { null },
                    editHash = o.optString("edit_hash").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPlaylistViaApi ${e.message}")
            null
        }
    }

    private fun deletePlaylistViaApi(session: VkSession, playlist: Playlist): Boolean {
        val edit = playlist.editHash ?: return false
        if (session.accessToken.isBlank()) return false
        return try {
            val url = ("https://api.vk.ru/method/audio.deletePlaylist").toHttpUrl().newBuilder()
                .addQueryParameter("access_token", session.accessToken)
                .addQueryParameter("v", "5.199")
                .addQueryParameter("owner_id", playlist.ownerId.toString())
                .addQueryParameter("playlist_id", playlist.id.toString())
                .addQueryParameter("edit_hash", edit)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", session.userAgent.ifBlank { VkClientProfiles.Kate.userAgent })
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "deletePlaylistViaApi head=${body.take(160)}")
                val json = JSONObject(body)
                !json.has("error") && (json.optInt("response", 0) == 1 || json.opt("response") == true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylistViaApi ${e.message}")
            false
        }
    }

    /**
     * Успех мутации al_audio: payload[0]==0/1, без «Ошибка доступа» и без SPA-HTML заглушки.
     */
    private fun isMutationSuccess(body: String): Boolean {
        if (body.isBlank()) return false
        if (body.contains("Ошибка доступа", ignoreCase = true) ||
            body.contains("Access denied", ignoreCase = true) ||
            body.contains("Flood control", ignoreCase = true)
        ) {
            return false
        }
        if (body.contains("scroll_fix_wrap") || body.contains("LayoutWrapper") ||
            (body.contains("<div") && body.length > 50_000)
        ) {
            return false
        }
        if (Regex("\"error\"\\s*:\\s*\\{").containsMatchIn(body) &&
            body.contains("error_msg", ignoreCase = true)
        ) {
            return false
        }
        val payloadCode = Regex("\"payload\"\\s*:\\s*\\[\\s*\"?(-?\\d+)\"?")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (payloadCode != null) {
            return payloadCode == 0 || payloadCode == 1
        }
        // m.vk JSON: {"data":[...]} без payload
        return body.contains("\"edit_hash\"") || body.contains("\"playlist\"")
    }

    /** Для add/remove трека в «Мою музыку». */
    private fun isActionSuccess(body: String): Boolean = isMutationSuccess(body) ||
        (body.length in 6..50_000 &&
            !body.contains("Ошибка", ignoreCase = true) &&
            Regex("\"payload\"\\s*:\\s*\\[\\s*\"?0\"?").containsMatchIn(body))

    private fun getRaw(url: String, session: VkSession, uaOverride: String? = null): String? {
        return try {
            val ua = uaOverride
                ?: session.userAgent.ifBlank { DEFAULT_MOBILE_UA }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Cookie", enrichCookies(session.cookies))
                .header("Referer", "https://vk.ru/audio")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url ${e.message}")
            null
        }
    }

    private fun parsePlaylistsHtml(html: String, userId: Long): List<Playlist> {
        val out = linkedMapOf<String, Playlist>()
        val matcher = PLAYLIST_LINK.matcher(html)
        while (matcher.find()) {
            val owner = matcher.group(1)?.toLongOrNull() ?: continue
            val id = matcher.group(2)?.toLongOrNull() ?: continue
            if (id <= 0) continue
            val access = matcher.group(3)
            val aroundStart = (matcher.start() - 200).coerceAtLeast(0)
            val aroundEnd = (matcher.end() + 400).coerceAtMost(html.length)
            val around = html.substring(aroundStart, aroundEnd)
            val titleMatch = TITLE_IN_BLOCK.find(around)
            val title = listOfNotNull(
                titleMatch?.groupValues?.getOrNull(1),
                titleMatch?.groupValues?.getOrNull(2)
            ).firstOrNull { it.isNotBlank() }?.let { stripHtml(it) } ?: "Плейлист $id"
            val count = COUNT_IN_BLOCK.find(around)?.groupValues?.getOrNull(1)
                ?.replace(" ", "")?.toIntOrNull() ?: 0
            val edit = Regex("edit_hash[=\":]+([\\w-]+)").find(around)?.groupValues?.getOrNull(1)
            val pl = rememberHashes(
                Playlist(
                    id = id,
                    ownerId = owner,
                    title = title,
                    trackCount = count,
                    accessHash = access,
                    editHash = edit
                )
            )
            out.putIfAbsent(pl.fullId, pl)
        }
        if (out.isEmpty() && userId > 0) {
            // запасной разбор owner_id_playlist_id в JSON
            val jsonMatcher = PLAYLIST_ID_JSON.matcher(html)
            while (jsonMatcher.find()) {
                val owner = jsonMatcher.group(1)?.toLongOrNull() ?: userId
                val id = jsonMatcher.group(2)?.toLongOrNull() ?: continue
                val pl = Playlist(id = id, ownerId = owner, title = "Плейлист $id")
                out.putIfAbsent(pl.fullId, pl)
            }
        }
        return out.values.toList()
    }

    private fun parsePlaylistsJson(body: String, userId: Long): List<Playlist> {
        val out = linkedMapOf<String, Playlist>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val id = node.optLong("id", 0L).takeIf { it > 0 }
                        ?: node.optLong("playlist_id", 0L).takeIf { it > 0 }
                    val owner = node.optLong("owner_id", 0L).takeIf { it != 0L } ?: userId
                    val title = node.optString("title").ifBlank { node.optString("name") }
                    if (id != null && title.isNotBlank()) {
                        val pl = Playlist(
                            id = id,
                            ownerId = owner,
                            title = stripHtml(title),
                            trackCount = node.optInt("size", node.optInt("count", 0)),
                            accessHash = node.optString("access_hash").ifBlank { null },
                        editHash = node.optString("edit_hash").ifBlank { null },
                            coverUrl = node.optString("thumb").ifBlank {
                                node.optJSONObject("photo")?.optString("photo_135")
                            }?.ifBlank { null }
                        )
                        out.putIfAbsent(pl.fullId, pl)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
        }
        runCatching { walk(JSONObject(body.removePrefix("<!--").trim())) }
            .onFailure {
                val idx = body.indexOf('{')
                if (idx >= 0) runCatching { walk(JSONObject(body.substring(idx))) }
            }
        return out.values.toList()
    }

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
            if (!body.isNullOrBlank() && body.length > 5) return body
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
        val addHash = hashes.getOrNull(0)?.takeIf { it.isNotBlank() }
        val deleteHash = hashes.getOrNull(3)?.takeIf { it.isNotBlank() }
        val actionHash = hashes.getOrNull(2).orEmpty()
        val urlHash = hashes.getOrNull(5).orEmpty()
        val reloadKey = if (actionHash.isNotBlank() && urlHash.isNotBlank()) {
            "${ownerId}_${audioId}_${actionHash}_$urlHash"
        } else {
            null
        }
        val cover = arr.optString(14).split(',').firstOrNull { it.startsWith("http") }
        val accessKey = arr.optString(24).takeIf { it.isNotBlank() && it.length < 64 }
            ?: hashes.getOrNull(6)?.takeIf { it.length in 8..64 }
        val trackCode = arr.opt(20)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
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
            addHash = addHash,
            deleteHash = deleteHash,
            accessKey = accessKey,
            trackCode = trackCode,
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
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val AUDIO_PAGE_JSON: Pattern =
            Pattern.compile("new AudioPage\\(.*?(\\{.*\\})", Pattern.DOTALL)
        private val M3U8_TO_MP3: Pattern =
            Pattern.compile("/[0-9a-f]+(/audios)?/([0-9a-f]+)/index\\.m3u8")
        private val PLAYLIST_LINK: Pattern = Pattern.compile(
            "act=audio_playlist(-?\\d+)_(\\d+)(?:[^\"'\\s]*access_hash=([\\w-]+))?",
            Pattern.CASE_INSENSITIVE
        )
        private val PLAYLIST_ID_JSON: Pattern =
            Pattern.compile("\"owner_id\"\\s*:\\s*(-?\\d+)[^\\{]{0,80}\"id\"\\s*:\\s*(\\d+)")
        private val TITLE_IN_BLOCK = Regex(
            "audioPlaylistsPage__title[^>]*>([^<]+)|\"title\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        private val COUNT_IN_BLOCK = Regex("(\\d[\\d\\s]*)\\s*(?:аудио|трек|compos)", RegexOption.IGNORE_CASE)

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
