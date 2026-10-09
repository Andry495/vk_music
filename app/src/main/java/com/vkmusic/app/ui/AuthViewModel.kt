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
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "VkMusicAuth"

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
    val tracks: List<Track> = emptyList(),
    val tracksLoading: Boolean = false,
    val tracksError: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VkMusicApp
    private val sessionStore = app.sessionStore
    private val probe = app.audioAccessProbe
    private val webAudio = app.webAudioClient
    private val clientAuth = app.vkClientAuth

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    /** Host activity sets this to open WebView login. */
    var launchWebLogin: ((profile: String) -> Unit)? = null

    init {
        viewModelScope.launch { restore() }
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
            runCatching { VkIdAuth.logout() }
            sessionStore.clear()
            _ui.value = AuthUiState(
                authState = AuthState.LoggedOut,
                vkidConfigured = VkIdAuth.isConfigured
            )
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun reloadLibrary() {
        val state = _ui.value.authState
        if (state !is AuthState.Ready) return
        viewModelScope.launch { loadLibrary(state.session) }
    }

    private suspend fun loadLibrary(session: VkSession) {
        _ui.update { it.copy(tracksLoading = true, tracksError = null) }
        try {
            val tracks = webAudio.fetchMyAudios(session, limit = 80)
            Log.i(TAG, "LIBRARY tracks=${tracks.size}")
            _ui.update {
                it.copy(
                    tracks = tracks,
                    tracksLoading = false,
                    tracksError = when {
                        tracks.isEmpty() ->
                            "Сессия OK, но треков нет. В «Моей музыке» VK пусто " +
                                "(placeholder_music_no_audios). Добавьте треки в приложении VK " +
                                "или нажмите Обновить после этого."
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
                        tracksLoading = true,
                        tracksError = null
                    )
                }
                loadLibrary(working)
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
}
