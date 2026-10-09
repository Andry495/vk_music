package com.vkmusic.app.auth

import com.vk.id.AccessToken
import com.vk.id.VKID
import com.vk.id.logout.VKIDLogoutCallback
import com.vk.id.logout.VKIDLogoutFail
import com.vkmusic.app.BuildConfig

object VkIdAuth {
    val isConfigured: Boolean
        get() = BuildConfig.VKID_CONFIGURED

    fun sessionFromAccessToken(token: AccessToken): VkSession {
        val expiresAt = if (token.expireTime > 0L) token.expireTime else 0L
        val name = listOfNotNull(token.userData.firstName, token.userData.lastName)
            .joinToString(" ")
            .ifBlank { null }
        return VkSession(
            accessToken = token.token,
            userId = token.userID,
            source = AuthSource.VkId,
            userAgent = "",
            expiresAtEpochMs = expiresAt,
            displayName = name
        )
    }

    suspend fun logout() {
        if (!isConfigured) return
        try {
            VKID.instance.logout(
                callback = object : VKIDLogoutCallback {
                    override fun onSuccess() = Unit
                    override fun onFail(fail: VKIDLogoutFail) = Unit
                }
            )
        } catch (_: Exception) {
            // Ignore logout failures — local session is cleared anyway.
        }
    }
}
