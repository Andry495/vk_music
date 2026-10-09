package com.vkmusic.app.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "vk_session")

class SessionStore(private val context: Context) {

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val USER_ID = longPreferencesKey("user_id")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val COOKIES = stringPreferencesKey("cookies")
        val AUDIO_VIA = stringPreferencesKey("audio_via")
    }

    val sessionFlow: Flow<VkSession?> = context.sessionDataStore.data.map { prefs ->
        val token = prefs[Keys.ACCESS_TOKEN] ?: return@map null
        val userId = prefs[Keys.USER_ID] ?: return@map null
        VkSession(
            accessToken = token,
            userId = userId,
            userAgent = prefs[Keys.USER_AGENT].orEmpty(),
            expiresAtEpochMs = prefs[Keys.EXPIRES_AT] ?: 0L,
            displayName = prefs[Keys.DISPLAY_NAME],
            cookies = prefs[Keys.COOKIES].orEmpty(),
            audioVia = prefs[Keys.AUDIO_VIA].orEmpty()
        )
    }

    suspend fun save(session: VkSession) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = session.accessToken
            prefs[Keys.USER_ID] = session.userId
            prefs[Keys.USER_AGENT] = session.userAgent
            prefs[Keys.EXPIRES_AT] = session.expiresAtEpochMs
            prefs[Keys.COOKIES] = session.cookies
            prefs[Keys.AUDIO_VIA] = session.audioVia
            if (session.displayName != null) {
                prefs[Keys.DISPLAY_NAME] = session.displayName
            } else {
                prefs.remove(Keys.DISPLAY_NAME)
            }
            // legacy key cleanup
            prefs.remove(stringPreferencesKey("source"))
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
