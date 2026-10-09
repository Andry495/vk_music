package com.vkmusic.app.auth

/**
 * Сессия браузерного входа (Kate WebView): OAuth-токен + cookies (remixsid) для музыки.
 */
data class VkSession(
    val accessToken: String,
    val userId: Long,
    /** Kate Mobile UA — как при авторизации. */
    val userAgent: String = "",
    val expiresAtEpochMs: Long = 0L,
    val displayName: String? = null,
    /**
     * Cookie-строка из WebView (remixsid и др.) для al_audio.php.
     */
    val cookies: String = "",
    /** Как подтвердили доступ к музыке: web / api. */
    val audioVia: String = ""
)

sealed class AuthState {
    data object Loading : AuthState()
    data object LoggedOut : AuthState()
    /** Сессия есть, но cookies/музыка недоступны — нужен повторный вход. */
    data class NeedsReauth(val session: VkSession) : AuthState()
    data class Ready(val session: VkSession) : AuthState()
}

sealed class AudioProbeResult {
    data object Ok : AudioProbeResult()
    data class Denied(val errorCode: Int?, val message: String) : AudioProbeResult()
    data class NetworkError(val message: String) : AudioProbeResult()
}
