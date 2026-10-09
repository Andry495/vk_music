package com.vkmusic.app.auth

import android.util.Log
import com.vkmusic.app.vk.VkClientProfiles
import com.vkmusic.app.vk.VkWebAudioClient

private const val TAG = "VkMusicAuth"

/**
 * Проверка доступа к музыке. Основной путь — веб (al_audio + remixsid) после входа Kate.
 */
class AudioAccessProbe(
    private val webAudio: VkWebAudioClient = VkWebAudioClient(),
    private val sessionKeeper: SessionKeeper = SessionKeeper()
) {
    suspend fun probeAndAlign(session: VkSession): Pair<VkSession, AudioProbeResult> {
        var working = session.copy(
            userAgent = session.userAgent.ifBlank { VkClientProfiles.Kate.userAgent }
        )
        working = sessionKeeper.enrichFromCookieJar(working)

        val refreshed = sessionKeeper.refreshIfAlive(working)
        if (refreshed != null) {
            working = refreshed
        }

        val webResult = webAudio.probe(working)
        Log.i(TAG, "web probe -> $webResult")
        return when (webResult) {
            AudioProbeResult.Ok ->
                working.copy(audioVia = "web") to AudioProbeResult.Ok
            is AudioProbeResult.Denied -> working to webResult
            is AudioProbeResult.NetworkError -> working to webResult
        }
    }
}
