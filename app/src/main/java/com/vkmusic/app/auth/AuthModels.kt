package com.vkmusic.app.auth

enum class AuthSource {
    /** Official VK ID SDK (optional; needs id.vk.com app). */
    VkId,
    /** WebView Implicit Flow — основной вход без регистрации приложения. */
    WebView,
    /** Password grant (запасной). */
    Client
}

data class VkSession(
    val accessToken: String,
    val userId: Long,
    val source: AuthSource,
    /** Required for WebView/Client API calls; empty for VK ID. */
    val userAgent: String = "",
    val expiresAtEpochMs: Long = 0L,
    val displayName: String? = null,
    /**
     * Cookie-строка из WebView (remixsid и др.). Нужна для музыки через al_audio.php,
     * когда OAuth-методы audio.* отвечают error 3.
     */
    val cookies: String = "",
    /** Как подтвердили доступ к музыке: api / web. */
    val audioVia: String = ""
)

sealed class AuthState {
    data object Loading : AuthState()
    data object LoggedOut : AuthState()
    /** Сессия есть, но audio недоступен — показать запасной вход. */
    data class NeedsAudioFallback(val session: VkSession) : AuthState()
    data class Ready(val session: VkSession) : AuthState()
}

sealed class ClientAuthResult {
    data class Success(val session: VkSession) : ClientAuthResult()
    data class NeedsTwoFactor(
        val validationType: String?,
        val validationSid: String?,
        val phoneMask: String?,
        val redirectUri: String?
    ) : ClientAuthResult()

    data class Error(val message: String, val code: String? = null) : ClientAuthResult()
}

sealed class AudioProbeResult {
    data object Ok : AudioProbeResult()
    data class Denied(val errorCode: Int?, val message: String) : AudioProbeResult()
    data class NetworkError(val message: String) : AudioProbeResult()
}
