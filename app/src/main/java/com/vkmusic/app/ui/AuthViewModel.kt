package com.vkmusic.app.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.vkmusic.app.VkMusicApp
import com.vkmusic.app.auth.AuthState
import com.vkmusic.app.auth.AudioProbeResult
import com.vkmusic.app.auth.VkLoginActivity
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.LibraryActionResult
import com.vkmusic.app.data.LibrarySection
import com.vkmusic.app.data.MainTab
import com.vkmusic.app.data.Playlist
import com.vkmusic.app.data.Track
import com.vkmusic.app.player.CacheSettings
import com.vkmusic.app.player.CacheSettingsStore
import com.vkmusic.app.player.CacheUiStats
import com.vkmusic.app.player.PlaybackState
import com.vkmusic.app.vk.VkClientProfiles
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "VkMusicAuth"

data class AuthUiState(
    val authState: AuthState = AuthState.Loading,
    val busy: Boolean = false,
    val message: String? = null,
    val mainTab: MainTab = MainTab.Library,
    val librarySection: LibrarySection = LibrarySection.Recoms,
    val tracks: List<Track> = emptyList(),
    val tracksLoading: Boolean = false,
    val tracksError: String? = null,
    val searchQuery: String = "",
    val showSettings: Boolean = false,
    val cacheSettings: CacheSettings = CacheSettings(),
    val cacheStats: CacheUiStats = CacheUiStats(),
    val playbackError: String? = null,
    val playlists: List<Playlist> = emptyList(),
    val playlistsLoading: Boolean = false,
    val openPlaylist: Playlist? = null,
    val trackForPlaylistPicker: Track? = null,
    val showCreatePlaylist: Boolean = false,
    val newPlaylistTitle: String = ""
)

@OptIn(UnstableApi::class)
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VkMusicApp
    private val sessionStore = app.sessionStore
    private val probe = app.audioAccessProbe
    private val webAudio = app.webAudioClient
    private val player = app.musicPlayer

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    val playback: StateFlow<PlaybackState> = player.state

    private var loadJob: Job? = null
    private var renewJob: Job? = null

    /** Host activity opens Kate WebView login. */
    var launchWebLogin: (() -> Unit)? = null

    init {
        player.resolveUrl = { track ->
            val session = (_ui.value.authState as? AuthState.Ready)?.session
            if (session == null) {
                null
            } else {
                webAudio.resolveStreamUrl(session, track).streamUrl
            }
        }
        viewModelScope.launch {
            CacheSettingsStore.hydrate(app)
            _ui.update { it.copy(cacheSettings = CacheSettingsStore.current()) }
        }
        viewModelScope.launch { restore() }
        viewModelScope.launch {
            player.state.collect { pb ->
                _ui.update {
                    it.copy(
                        playbackError = pb.error,
                        cacheStats = pb.cacheStats
                    )
                }
            }
        }
        viewModelScope.launch {
            CacheSettingsStore.settingsFlow(app).collect { settings ->
                _ui.update { it.copy(cacheSettings = settings) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1500)
                _ui.update { it.copy(cacheStats = player.state.value.cacheStats) }
            }
        }
    }

    private suspend fun restore() {
        _ui.update { it.copy(authState = AuthState.Loading) }
        val session = sessionStore.sessionFlow.first()
        if (session == null) {
            _ui.update { it.copy(authState = AuthState.LoggedOut, busy = false) }
            return
        }
        applyAfterProbe(session)
    }

    fun startWebLogin() {
        _ui.update { it.copy(message = null) }
        launchWebLogin?.invoke()
            ?: _ui.update { it.copy(message = "WebView-вход недоступен") }
    }

    fun onWebLoginResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            val err = data?.getStringExtra(VkLoginActivity.EXTRA_ERROR)
            _ui.update {
                it.copy(
                    busy = false,
                    message = err?.takeIf { e -> e.isNotBlank() } ?: "Вход отменён"
                )
            }
            return
        }
        val token = data.getStringExtra(VkLoginActivity.EXTRA_ACCESS_TOKEN).orEmpty()
        val userId = data.getLongExtra(VkLoginActivity.EXTRA_USER_ID, -1L)
        val expiresIn = data.getLongExtra(VkLoginActivity.EXTRA_EXPIRES_IN, 0L)
        val userAgent = data.getStringExtra(VkLoginActivity.EXTRA_USER_AGENT)
            ?.ifBlank { null }
            ?: VkClientProfiles.Kate.userAgent
        val cookies = data.getStringExtra(VkLoginActivity.EXTRA_COOKIES).orEmpty()
        if (token.isBlank() || userId <= 0L) {
            _ui.update { it.copy(busy = false, message = "Токен не получен из WebView") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            val expiresAt = if (expiresIn > 0) {
                System.currentTimeMillis() + expiresIn * 1000L
            } else {
                0L
            }
            val session = VkSession(
                accessToken = token,
                userId = userId,
                userAgent = userAgent,
                expiresAtEpochMs = expiresAt,
                cookies = cookies.ifBlank {
                    VkWebAudioClient.collectVkCookies { CookieManager.getInstance().getCookie(it) }
                }
            )
            sessionStore.save(session)
            applyAfterProbe(session)
        }
    }

    fun updateSearchQuery(value: String) = _ui.update { it.copy(searchQuery = value) }

    fun logout() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, showSettings = false) }
            renewJob?.cancel()
            player.stop()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            sessionStore.clear()
            _ui.value = AuthUiState(authState = AuthState.LoggedOut)
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null, playbackError = null) }

    fun selectMainTab(tab: MainTab) {
        _ui.update { it.copy(mainTab = tab) }
    }

    fun selectLibrarySection(section: LibrarySection) {
        if (_ui.value.librarySection == section &&
            section != LibrarySection.Playlists &&
            _ui.value.openPlaylist == null
        ) {
            return
        }
        _ui.update {
            it.copy(
                librarySection = section,
                openPlaylist = null,
                tracks = if (section == LibrarySection.Playlists) emptyList() else it.tracks
            )
        }
        val state = _ui.value.authState
        if (state is AuthState.Ready) loadLibrary(state.session, section)
    }

    fun openSettings() = _ui.update { it.copy(showSettings = true) }
    fun closeSettings() = _ui.update { it.copy(showSettings = false) }

    fun updateCacheSettings(transform: (CacheSettings) -> CacheSettings) {
        viewModelScope.launch {
            CacheSettingsStore.update(app, transform)
            player.reloadCacheSettings()
        }
    }

    fun clearAudioCache() {
        player.clearCache()
        _ui.update { it.copy(message = "Кэш аудио очищен") }
    }

    fun reloadLibrary() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        when (_ui.value.mainTab) {
            MainTab.Search -> submitSearch()
            MainTab.Library, MainTab.Player -> loadLibrary(state.session, _ui.value.librarySection)
        }
    }

    fun submitSearch() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        _ui.update { it.copy(mainTab = MainTab.Search) }
        loadSearch(state.session, _ui.value.searchQuery)
    }

    fun playTrack(track: Track) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            _ui.update { it.copy(playbackError = null, busy = true, mainTab = MainTab.Player) }
            try {
                var resolved = track
                val url = track.streamUrl
                if (url.isNullOrBlank() || url.contains("audio_api_unavailable") || url.contains("?extra=")) {
                    resolved = webAudio.resolveStreamUrl(state.session, track)
                }
                val playUrl = resolved.streamUrl
                if (playUrl.isNullOrBlank() || !playUrl.startsWith("http")) {
                    _ui.update {
                        it.copy(busy = false, playbackError = "Нет ссылки на поток для «${track.title}»")
                    }
                    return@launch
                }
                val queue = _ui.value.tracks.ifEmpty { listOf(resolved) }
                player.playTrackInQueue(resolved, queue, playUrl)
                _ui.update { it.copy(busy = false) }
            } catch (e: Exception) {
                Log.e(TAG, "play error ${e.message}")
                _ui.update {
                    it.copy(busy = false, playbackError = e.message ?: "Ошибка воспроизведения")
                }
            }
        }
    }

    fun togglePlayback() = player.toggle()
    fun playNext() = player.playNext()
    fun playPrevious() = player.playPrevious()
    fun seekTo(ms: Long) = player.seekTo(ms)

    fun addToMyMusic(track: Track) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            when (val r = webAudio.addToMyMusic(state.session, track)) {
                LibraryActionResult.Ok -> {
                    _ui.update { it.copy(message = "«${track.title}» добавлен в Мою музыку") }
                    if (_ui.value.librarySection == LibrarySection.MyMusic &&
                        _ui.value.openPlaylist == null
                    ) {
                        loadLibrary(state.session, LibrarySection.MyMusic)
                    }
                }
                is LibraryActionResult.Error ->
                    _ui.update { it.copy(message = r.message) }
            }
        }
    }

    fun removeFromMyMusic(track: Track) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            when (val r = webAudio.removeFromMyMusic(state.session, track)) {
                LibraryActionResult.Ok -> {
                    _ui.update {
                        val inMyMusicList = it.librarySection == LibrarySection.MyMusic &&
                            it.openPlaylist == null
                        it.copy(
                            message = "«${track.title}» удалён из Моей музыки",
                            tracks = if (inMyMusicList) {
                                it.tracks.filterNot { t -> t.id == track.id }
                            } else {
                                it.tracks
                            }
                        )
                    }
                }
                is LibraryActionResult.Error ->
                    _ui.update { it.copy(message = r.message) }
            }
        }
    }

    fun openAddToPlaylist(track: Track) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        _ui.update { it.copy(trackForPlaylistPicker = track, playlistsLoading = true) }
        viewModelScope.launch {
            val list = webAudio.fetchPlaylists(state.session)
            _ui.update { it.copy(playlists = list, playlistsLoading = false) }
        }
    }

    fun closePlaylistPicker() = _ui.update { it.copy(trackForPlaylistPicker = null) }

    fun addTrackToPlaylist(playlist: Playlist) {
        val state = _ui.value.authState
        val track = _ui.value.trackForPlaylistPicker ?: return
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            when (val r = webAudio.addToPlaylist(state.session, playlist, track)) {
                LibraryActionResult.Ok -> {
                    _ui.update { it.copy(trackForPlaylistPicker = null) }
                    // факт: перечитываем треки плейлиста
                    val tracks = webAudio.fetchPlaylistTracks(state.session, playlist)
                    val added = tracks.any { it.id == track.id || it.audioId == track.audioId }
                    _ui.update {
                        it.copy(
                            message = if (added) {
                                "«${track.title}» → «${playlist.title}»"
                            } else {
                                "VK принял запрос, но трек в «${playlist.title}» не найден — обновите"
                            },
                            openPlaylist = if (it.openPlaylist?.fullId == playlist.fullId) {
                                playlist.copy(trackCount = tracks.size)
                            } else {
                                it.openPlaylist
                            },
                            tracks = if (it.openPlaylist?.fullId == playlist.fullId) tracks else it.tracks,
                            tracksError = if (it.openPlaylist?.fullId == playlist.fullId && tracks.isEmpty()) {
                                "Плейлист пуст"
                            } else {
                                null
                            }
                        )
                    }
                }
                is LibraryActionResult.Error ->
                    _ui.update { it.copy(message = r.message, trackForPlaylistPicker = null) }
            }
        }
    }

    fun removeFromPlaylist(track: Track) {
        val state = _ui.value.authState
        val playlist = _ui.value.openPlaylist ?: return
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            when (val r = webAudio.removeFromPlaylist(state.session, playlist, track)) {
                LibraryActionResult.Ok -> {
                    val tracks = webAudio.fetchPlaylistTracks(state.session, playlist)
                    val stillThere = tracks.any { it.id == track.id }
                    _ui.update {
                        it.copy(
                            message = if (!stillThere) {
                                "«${track.title}» удалён из «${playlist.title}»"
                            } else {
                                "Не удалось подтвердить удаление из плейлиста"
                            },
                            tracks = tracks,
                            openPlaylist = it.openPlaylist?.copy(trackCount = tracks.size),
                            tracksError = if (tracks.isEmpty()) "Плейлист пуст" else null
                        )
                    }
                }
                is LibraryActionResult.Error ->
                    _ui.update { it.copy(message = r.message) }
            }
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            when (val r = webAudio.deletePlaylist(state.session, playlist)) {
                LibraryActionResult.Ok -> {
                    val list = webAudio.fetchPlaylists(state.session)
                    val stillThere = list.any { it.fullId == playlist.fullId }
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = if (!stillThere) {
                                "Плейлист «${playlist.title}» удалён"
                            } else {
                                "Удаление не подтвердилось — плейлист ещё в списке"
                            },
                            playlists = list,
                            openPlaylist = if (it.openPlaylist?.fullId == playlist.fullId) null else it.openPlaylist,
                            tracks = if (it.openPlaylist?.fullId == playlist.fullId) emptyList() else it.tracks
                        )
                    }
                }
                is LibraryActionResult.Error ->
                    _ui.update { it.copy(busy = false, message = r.message) }
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        _ui.update {
            it.copy(
                openPlaylist = playlist,
                librarySection = LibrarySection.Playlists,
                tracksLoading = true,
                tracks = emptyList(),
                tracksError = null
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // fetchPlaylistTracks сам resolve'ит edit_hash; повторный resolve — из кэша
                val tracks = webAudio.fetchPlaylistTracks(state.session, playlist)
                val resolved = webAudio.resolvePlaylist(state.session, playlist)
                _ui.update {
                    it.copy(
                        openPlaylist = resolved,
                        playlists = it.playlists.map { p ->
                            if (p.fullId == resolved.fullId) resolved else p
                        },
                        tracks = tracks,
                        tracksLoading = false,
                        tracksError = if (tracks.isEmpty()) "Плейлист пуст" else null
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(tracksLoading = false, tracksError = e.message ?: "Ошибка плейлиста")
                }
            }
        }
    }

    fun closePlaylist() {
        _ui.update { it.copy(openPlaylist = null, tracks = emptyList()) }
        val state = _ui.value.authState
        if (state is AuthState.Ready) loadPlaylists(state.session)
    }

    fun openCreatePlaylist() = _ui.update {
        // trackForPlaylistPicker не трогаем — после создания добавим туда трек
        it.copy(showCreatePlaylist = true, newPlaylistTitle = "")
    }

    fun closeCreatePlaylist() = _ui.update {
        it.copy(showCreatePlaylist = false, newPlaylistTitle = "")
    }

    fun updateNewPlaylistTitle(value: String) =
        _ui.update { it.copy(newPlaylistTitle = value) }

    fun createPlaylist() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        val title = _ui.value.newPlaylistTitle.trim()
        if (title.isEmpty()) {
            _ui.update { it.copy(message = "Введите название плейлиста") }
            return
        }
        val pendingTrack = _ui.value.trackForPlaylistPicker
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val created = webAudio.createPlaylist(state.session, title)
                // факт: один перезапрос списка после create
                var list = webAudio.fetchPlaylists(state.session)
                val confirmed = list.firstOrNull {
                    it.fullId == created?.fullId || it.title.equals(title, ignoreCase = true)
                } ?: created?.let { c -> list.firstOrNull { it.id == c.id } }
                if (confirmed == null) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = "Не удалось создать плейлист (нет в списке после запроса)"
                        )
                    }
                    return@launch
                }
                var message = "Плейлист «${confirmed.title}» создан"
                if (pendingTrack != null) {
                    when (val add = webAudio.addToPlaylist(state.session, confirmed, pendingTrack)) {
                        LibraryActionResult.Ok -> {
                            val tracks = webAudio.fetchPlaylistTracks(state.session, confirmed)
                            message = if (tracks.any { it.audioId == pendingTrack.audioId }) {
                                "«${pendingTrack.title}» → «${confirmed.title}»"
                            } else {
                                "Плейлист создан, трек пока не виден — обновите"
                            }
                            list = webAudio.fetchPlaylists(state.session)
                        }
                        is LibraryActionResult.Error ->
                            message = "Плейлист создан, но трек не добавлен: ${add.message}"
                    }
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        showCreatePlaylist = false,
                        newPlaylistTitle = "",
                        trackForPlaylistPicker = null,
                        message = message,
                        playlists = list
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, message = e.message ?: "Ошибка создания плейлиста")
                }
            }
        }
    }

    private fun loadLibrary(session: VkSession, section: LibrarySection) {
        when (section) {
            LibrarySection.Playlists -> {
                if (_ui.value.openPlaylist != null) {
                    openPlaylist(_ui.value.openPlaylist!!)
                } else {
                    loadPlaylists(session)
                }
            }
            LibrarySection.Recoms, LibrarySection.MyMusic -> {
                loadJob?.cancel()
                loadJob = viewModelScope.launch {
                    _ui.update { it.copy(tracksLoading = true, tracksError = null) }
                    try {
                        val tracks = when (section) {
                            LibrarySection.Recoms -> webAudio.fetchRecoms(session, limit = 80)
                            LibrarySection.MyMusic -> webAudio.fetchMyAudios(session, limit = 80)
                            LibrarySection.Playlists -> emptyList()
                        }
                        Log.i(TAG, "LIBRARY section=$section tracks=${tracks.size}")
                        _ui.update {
                            it.copy(
                                tracks = tracks,
                                tracksLoading = false,
                                tracksError = when {
                                    tracks.isEmpty() && section == LibrarySection.MyMusic ->
                                        "В «Моей музыке» пусто. Добавьте трек из рекомендаций или поиска (⋯)."
                                    tracks.isEmpty() ->
                                        "Не удалось загрузить рекомендации. Попробуйте поиск."
                                    else -> null
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "LIBRARY error ${e.message}")
                        _ui.update {
                            it.copy(tracksLoading = false, tracksError = e.message ?: "Ошибка загрузки")
                        }
                    }
                }
            }
        }
    }

    private fun loadPlaylists(session: VkSession) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update {
                it.copy(playlistsLoading = true, tracksLoading = false, tracks = emptyList(), tracksError = null)
            }
            try {
                val list = webAudio.fetchPlaylists(session)
                _ui.update {
                    it.copy(
                        playlists = list,
                        playlistsLoading = false,
                        tracksError = if (list.isEmpty()) {
                            "Плейлистов нет. Создайте новый."
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        playlistsLoading = false,
                        tracksError = e.message ?: "Ошибка загрузки плейлистов"
                    )
                }
            }
        }
    }

    private fun loadSearch(session: VkSession, query: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val q = query.trim()
            if (q.isEmpty()) {
                _ui.update {
                    it.copy(tracks = emptyList(), tracksLoading = false, tracksError = null)
                }
                return@launch
            }
            _ui.update { it.copy(tracksLoading = true, tracksError = null) }
            try {
                val tracks = webAudio.search(session, q, limit = 80)
                Log.i(TAG, "SEARCH q='$q' tracks=${tracks.size}")
                _ui.update {
                    it.copy(
                        tracks = tracks,
                        tracksLoading = false,
                        tracksError = if (tracks.isEmpty()) "Ничего не найдено." else null
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(tracksLoading = false, tracksError = e.message ?: "Ошибка поиска")
                }
            }
        }
    }

    private suspend fun applyAfterProbe(session: VkSession) {
        Log.i(TAG, "probe start userId=${session.userId}")
        val (working, result) = probe.probeAndAlign(session)
        when (result) {
            AudioProbeResult.Ok -> {
                sessionStore.save(working)
                Log.i(TAG, "AUTH READY userId=${working.userId} via=${working.audioVia}")
                _ui.update {
                    it.copy(
                        authState = AuthState.Ready(working),
                        busy = false,
                        message = null,
                        mainTab = MainTab.Library,
                        librarySection = LibrarySection.Recoms,
                        tracksLoading = true,
                        tracksError = null
                    )
                }
                startSessionRenew(working)
                loadLibrary(working, LibrarySection.Recoms)
            }
            is AudioProbeResult.Denied -> {
                Log.w(TAG, "AUTH DENIED code=${result.errorCode} msg=${result.message}")
                val hint = when (result.errorCode) {
                    3 -> "Нужны cookies браузера (remixsid). Войдите через Kate ещё раз."
                    6, 9 -> "VK временно ограничил запросы. Подождите и войдите снова."
                    else -> "Нет доступа к музыке (${result.errorCode}): ${result.message}"
                }
                renewJob?.cancel()
                _ui.update {
                    it.copy(
                        authState = AuthState.NeedsReauth(working),
                        busy = false,
                        message = hint
                    )
                }
            }
            is AudioProbeResult.NetworkError -> {
                renewJob?.cancel()
                _ui.update {
                    it.copy(
                        authState = AuthState.NeedsReauth(working),
                        busy = false,
                        message = "Сеть при проверке: ${result.message}"
                    )
                }
            }
        }
    }

    private fun startSessionRenew(initial: VkSession) {
        renewJob?.cancel()
        renewJob = viewModelScope.launch {
            var current = initial
            while (isActive) {
                delay(12 * 60 * 1000L)
                val refreshed = app.sessionKeeper.refreshIfAlive(current)
                if (refreshed != null) {
                    current = refreshed
                    sessionStore.save(refreshed)
                    _ui.update { state ->
                        if (state.authState is AuthState.Ready) {
                            state.copy(authState = AuthState.Ready(refreshed))
                        } else {
                            state
                        }
                    }
                } else {
                    Log.w(TAG, "session renew failed — need reauth")
                    _ui.update {
                        it.copy(
                            authState = AuthState.NeedsReauth(current),
                            message = "Сессия истекла — войдите снова"
                        )
                    }
                    break
                }
            }
        }
    }
}
