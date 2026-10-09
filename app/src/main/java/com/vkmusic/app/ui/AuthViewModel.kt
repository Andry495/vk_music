package com.vkmusic.app.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vk.id.AccessToken
import com.vkmusic.app.VkMusicApp
import com.vkmusic.app.auth.AuthSource
import com.vkmusic.app.auth.AuthState
import com.vkmusic.app.auth.AudioProbeResult
import com.vkmusic.app.auth.ClientAuthResult
import com.vkmusic.app.auth.VkIdAuth
import com.vkmusic.app.auth.VkLoginActivity
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.Track
import com.vkmusic.app.player.PlaybackState
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "VkMusicAuth"

enum class BrowseTab {
    Recoms,
    Search,
    Library
}

data class AuthUiState(
    val authState: AuthState = AuthState.Loading,
    val busy: Boolean = false,
    val message: String? = null,
    val showClientForm: Boolean = false,
    val needsTwoFactor: Boolean = false,
    val twoFactorHint: String? = null,
    val login: String = "",
    val password: String = "",
    val twoFactorCode: String = "",
    val vkidConfigured: Boolean = VkIdAuth.isConfigured,
    val tab: BrowseTab = BrowseTab.Recoms,
    val tracks: List<Track> = emptyList(),
    val tracksLoading: Boolean = false,
    val tracksError: String? = null,
    val searchQuery: String = "",
    val playingTrackId: String? = null,
    val playbackError: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VkMusicApp
    private val sessionStore = app.sessionStore
    private val probe = app.audioAccessProbe
    private val webAudio = app.webAudioClient
    private val clientAuth = app.vkClientAuth
    private val player = app.musicPlayer

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    val playback: StateFlow<PlaybackState> = player.state

    private var loadJob: Job? = null

    /** Host activity sets this to open WebView login. */
    var launchWebLogin: ((profile: String) -> Unit)? = null

    init {
        viewModelScope.launch { restore() }
        viewModelScope.launch {
            player.state.collect { pb ->
                _ui.update {
                    it.copy(
                        playingTrackId = pb.track?.id,
                        playbackError = pb.error
                    )
                }
            }
        }
    }

    private suspend fun restore() {
        _ui.update { it.copy(authState = AuthState.Loading, vkidConfigured = VkIdAuth.isConfigured) }
        val session = sessionStore.sessionFlow.first()
        if (session == null) {
            _ui.update { it.copy(authState = AuthState.LoggedOut, busy = false) }
            return
        }
        applyAfterProbe(enrichCookies(session))
    }

    private fun enrichCookies(session: VkSession): VkSession {
        if (session.cookies.contains("remixsid")) return session
        val fromWebView = VkWebAudioClient.collectVkCookies { host ->
            CookieManager.getInstance().getCookie(host)
        }
        return if (fromWebView.contains("remixsid")) {
            session.copy(cookies = fromWebView)
        } else {
            session
        }
    }

    fun startWebLogin(profile: String = VkLoginActivity.PROFILE_OFFICIAL) {
        _ui.update { it.copy(message = null) }
        launchWebLogin?.invoke(profile)
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
        val userAgent = data.getStringExtra(VkLoginActivity.EXTRA_USER_AGENT).orEmpty()
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
                source = AuthSource.WebView,
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

    fun onVkIdAuth(token: AccessToken) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            val session = VkIdAuth.sessionFromAccessToken(token)
            sessionStore.save(session)
            applyAfterProbe(session)
        }
    }

    fun onVkIdFail(message: String) {
        _ui.update {
            it.copy(
                busy = false,
                message = message,
                authState = when (val s = it.authState) {
                    is AuthState.Ready, is AuthState.NeedsAudioFallback -> s
                    else -> AuthState.LoggedOut
                }
            )
        }
    }

    fun updateLogin(value: String) = _ui.update { it.copy(login = value) }
    fun updatePassword(value: String) = _ui.update { it.copy(password = value) }
    fun updateTwoFactor(value: String) = _ui.update { it.copy(twoFactorCode = value) }
    fun updateSearchQuery(value: String) = _ui.update { it.copy(searchQuery = value) }

    fun openClientForm() {
        _ui.update {
            it.copy(showClientForm = true, message = null, needsTwoFactor = false)
        }
    }

    fun closeClientForm() {
        _ui.update {
            it.copy(
                showClientForm = false,
                needsTwoFactor = false,
                twoFactorCode = "",
                message = null
            )
        }
    }

    fun submitClientLogin() {
        viewModelScope.launch {
            val state = _ui.value
            if (state.login.isBlank() || state.password.isBlank()) {
                _ui.update { it.copy(message = "Введите логин и пароль") }
                return@launch
            }
            _ui.update { it.copy(busy = true, message = null) }
            val code = state.twoFactorCode.takeIf { state.needsTwoFactor && it.isNotBlank() }
            when (val result = clientAuth.login(state.login, state.password, code)) {
                is ClientAuthResult.Success -> {
                    sessionStore.save(result.session)
                    applyAfterProbe(result.session)
                }
                is ClientAuthResult.NeedsTwoFactor -> {
                    val hint = buildString {
                        append("Нужен код подтверждения")
                        result.phoneMask?.let { append(" ($it)") }
                        result.validationType?.let { append(" · $it") }
                    }
                    _ui.update {
                        it.copy(
                            busy = false,
                            needsTwoFactor = true,
                            showClientForm = true,
                            twoFactorHint = hint,
                            message = "Введите код из SMS / приложения"
                        )
                    }
                }
                is ClientAuthResult.Error -> {
                    _ui.update {
                        it.copy(busy = false, message = result.message)
                    }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            player.stop()
            runCatching { VkIdAuth.logout() }
            sessionStore.clear()
            _ui.value = AuthUiState(
                authState = AuthState.LoggedOut,
                vkidConfigured = VkIdAuth.isConfigured
            )
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun selectTab(tab: BrowseTab) {
        if (_ui.value.tab == tab) return
        _ui.update { it.copy(tab = tab) }
        val state = _ui.value.authState
        if (state is AuthState.Ready) {
            when (tab) {
                BrowseTab.Recoms, BrowseTab.Library -> loadTracks(state.session, tab)
                BrowseTab.Search -> {
                    if (_ui.value.searchQuery.isNotBlank()) {
                        loadTracks(state.session, tab)
                    } else {
                        _ui.update { it.copy(tracks = emptyList(), tracksError = null, tracksLoading = false) }
                    }
                }
            }
        }
    }

    fun reloadLibrary() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        loadTracks(state.session, _ui.value.tab)
    }

    fun submitSearch() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        _ui.update { it.copy(tab = BrowseTab.Search) }
        loadTracks(state.session, BrowseTab.Search)
    }

    fun playTrack(track: Track) {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch {
            _ui.update { it.copy(playbackError = null, busy = true) }
            try {
                var resolved = track
                val url = track.streamUrl
                if (url.isNullOrBlank() || url.contains("audio_api_unavailable") || url.contains("?extra=")) {
                    resolved = webAudio.resolveStreamUrl(state.session, track)
                }
                val playUrl = resolved.streamUrl
                if (playUrl.isNullOrBlank() || !playUrl.startsWith("http")) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            playbackError = "Нет ссылки на поток для «${track.title}»"
                        )
                    }
                    return@launch
                }
                player.play(resolved, playUrl)
                _ui.update { it.copy(busy = false, playingTrackId = resolved.id) }
            } catch (e: Exception) {
                Log.e(TAG, "play error ${e.message}")
                _ui.update {
                    it.copy(busy = false, playbackError = e.message ?: "Ошибка воспроизведения")
                }
            }
        }
    }

    fun togglePlayback() = player.toggle()

    private fun loadTracks(session: VkSession, tab: BrowseTab) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(tracksLoading = true, tracksError = null) }
            try {
                val tracks = when (tab) {
                    BrowseTab.Recoms -> webAudio.fetchRecoms(session, limit = 80)
                    BrowseTab.Library -> webAudio.fetchMyAudios(session, limit = 80)
                    BrowseTab.Search -> {
                        val q = _ui.value.searchQuery
                        if (q.isBlank()) emptyList() else webAudio.search(session, q, limit = 80)
                    }
                }
                Log.i(TAG, "LIBRARY tab=$tab tracks=${tracks.size}")
                _ui.update {
                    it.copy(
                        tracks = tracks,
                        tracksLoading = false,
                        tracksError = when {
                            tracks.isEmpty() && tab == BrowseTab.Library ->
                                "В «Моей музыке» пусто. Откройте Рекомендации или воспользуйтесь поиском."
                            tracks.isEmpty() && tab == BrowseTab.Recoms ->
                                "Не удалось загрузить рекомендации. Попробуйте поиск."
                            tracks.isEmpty() && tab == BrowseTab.Search ->
                                "Ничего не найдено по запросу."
                            else -> null
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "LIBRARY error ${e.message}")
                _ui.update {
                    it.copy(
                        tracksLoading = false,
                        tracksError = e.message ?: "Ошибка загрузки"
                    )
                }
            }
        }
    }

    private suspend fun applyAfterProbe(session: VkSession) {
        Log.i(TAG, "probe start source=${session.source} userId=${session.userId}")
        val refreshed = probe.refreshIfPossible(session).also {
            if (it.accessToken != session.accessToken) {
                Log.i(TAG, "token refreshed")
                sessionStore.save(it)
            }
        }
        val (working, result) = probe.probeAndAlignUserAgent(refreshed)
        when (result) {
            AudioProbeResult.Ok -> {
                sessionStore.save(working)
                Log.i(
                    TAG,
                    "AUTH READY userId=${working.userId} source=${working.source} via=${working.audioVia}"
                )
                _ui.update {
                    it.copy(
                        authState = AuthState.Ready(working),
                        busy = false,
                        showClientForm = false,
                        needsTwoFactor = false,
                        message = null,
                        tab = BrowseTab.Recoms,
                        tracksLoading = true,
                        tracksError = null
                    )
                }
                loadTracks(working, BrowseTab.Recoms)
            }
            is AudioProbeResult.Denied -> {
                val perms = probe.readAppPermissions(working)
                Log.w(
                    TAG,
                    "AUTH DENIED code=${result.errorCode} msg=${result.message} perms=$perms audioBit=${probe.hasAudioBit(perms)}"
                )
                val hint = when (result.errorCode) {
                    3 -> "OAuth audio.* отключён VK. Нужны cookies WebView (remixsid). " +
                        "Нажмите «Войти через VK» ещё раз — подхватим cookie-сессию для музыки."
                    6, 9 -> "VK временно ограничил запросы (flood). Подождите минуту и нажмите «Войти через VK» снова."
                    15 -> "Error 15: нет доступа к методу. Попробуйте «Войти через VK» ещё раз."
                    25 -> "Error 25: нужен refresh токена. Попробуйте вход логином/паролем."
                    else -> "Нет доступа к audio (${result.errorCode}): ${result.message}"
                }
                val permNote = if (perms != null) {
                    " Права=$perms, audioBit=${probe.hasAudioBit(perms)}."
                } else {
                    ""
                }
                _ui.update {
                    it.copy(
                        authState = AuthState.NeedsAudioFallback(working),
                        busy = false,
                        showClientForm = true,
                        message = hint + permNote
                    )
                }
            }
            is AudioProbeResult.NetworkError -> {
                Log.e(TAG, "AUTH NETWORK ${result.message}")
                _ui.update {
                    it.copy(
                        authState = AuthState.NeedsAudioFallback(working),
                        busy = false,
                        message = "Сеть при проверке audio: ${result.message}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        // player живёт в Application — не release здесь
        super.onCleared()
    }
}
