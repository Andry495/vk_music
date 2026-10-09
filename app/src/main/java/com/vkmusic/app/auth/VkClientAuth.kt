package com.vkmusic.app.auth

import com.vkmusic.app.vk.VkApiClient
import com.vkmusic.app.vk.VkClientProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Path C: password OAuth against known client_ids (Kate / Official),
 * with 2FA support and best-effort auth.refreshToken.
 *
 * Fragile: VK may block these clients or require receipt confirmation (error 25).
 */
class VkClientAuth(
    private val api: VkApiClient = VkApiClient()
) {
    suspend fun login(
        login: String,
        password: String,
        twoFactorCode: String? = null,
        prefer: VkClientProfiles.Profile = VkClientProfiles.Kate
    ): ClientAuthResult = withContext(Dispatchers.IO) {
        val profiles = listOf(prefer) + VkClientProfiles.all.filter { it.clientId != prefer.clientId }
        var lastError: ClientAuthResult.Error? = null

        for (profile in profiles) {
            when (val result = passwordGrant(profile, login, password, twoFactorCode)) {
                is ClientAuthResult.Success -> {
                    val refreshed = tryRefresh(result.session)
                    return@withContext ClientAuthResult.Success(refreshed)
                }
                is ClientAuthResult.NeedsTwoFactor -> return@withContext result
                is ClientAuthResult.Error -> lastError = result
            }
        }
        lastError ?: ClientAuthResult.Error("Не удалось получить токен")
    }

    private suspend fun passwordGrant(
        profile: VkClientProfiles.Profile,
        login: String,
        password: String,
        twoFactorCode: String?
    ): ClientAuthResult {
        val params = linkedMapOf(
            "grant_type" to "password",
            "client_id" to profile.clientId,
            "client_secret" to profile.clientSecret,
            "username" to login.trim(),
            "password" to password,
            "v" to "5.95",
            "scope" to "audio,offline",
            "2fa_supported" to "1",
            "force_sms" to "1"
        )
        if (!twoFactorCode.isNullOrBlank()) {
            params["code"] = twoFactorCode.trim()
        }

        val json = try {
            api.rawGet(
                url = "https://oauth.vk.com/token",
                userAgent = profile.userAgent,
                params = params
            )
        } catch (e: Exception) {
            return ClientAuthResult.Error("Сеть: ${e.message}")
        }

        if (json.has("error")) {
            val error = json.optString("error")
            return when (error) {
                "need_validation" -> ClientAuthResult.NeedsTwoFactor(
                    validationType = json.optString("validation_type").ifBlank { null },
                    validationSid = json.optString("validation_sid").ifBlank { null },
                    phoneMask = json.optString("phone_mask").ifBlank { null },
                    redirectUri = json.optString("redirect_uri").ifBlank { null }
                )
                "invalid_client" -> ClientAuthResult.Error(
                    "Клиент ${profile.name} недоступен",
                    error
                )
                else -> ClientAuthResult.Error(
                    json.optString("error_description").ifBlank { error },
                    error
                )
            }
        }

        val token = json.optString("access_token")
        val userId = json.optLong("user_id", -1L)
        if (token.isBlank() || userId <= 0L) {
            return ClientAuthResult.Error("Токен не получен: ${json.toString().take(160)}")
        }

        val expiresIn = json.optLong("expires_in", 0L)
        val expiresAt = if (expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000L
        } else {
            0L
        }

        return ClientAuthResult.Success(
            VkSession(
                accessToken = token,
                userId = userId,
                source = AuthSource.Client,
                userAgent = profile.userAgent,
                expiresAtEpochMs = expiresAt
            )
        )
    }

    /**
     * Best-effort refresh. Without a GCM receipt VK may return the same token
     * or error 25 on subsequent audio calls — probe decides readiness.
     */
    private suspend fun tryRefresh(session: VkSession): VkSession {
        return try {
            val json = api.call(
                method = "auth.refreshToken",
                session = session,
                params = mapOf("receipt" to "")
            )
            val response = json.optJSONObject("response") ?: return session
            val newToken = response.optString("token")
            if (newToken.isNotBlank() && newToken != session.accessToken) {
                session.copy(accessToken = newToken)
            } else {
                session
            }
        } catch (_: Exception) {
            session
        }
    }

    fun parseErrorPayload(json: JSONObject): String =
        json.optString("error_description").ifBlank { json.optString("error") }
}
