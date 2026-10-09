package com.vkmusic.app.auth

import android.util.Log
import com.vkmusic.app.vk.VkApiClient
import com.vkmusic.app.vk.VkClientProfiles
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.delay

private const val TAG = "VkMusicAuth"

class AudioAccessProbe(
    private val api: VkApiClient,
    private val webAudio: VkWebAudioClient = VkWebAudioClient()
) {
    /**
     * 1) Пробуем OAuth API (api.vk.ru)
     * 2) Если error 3 / нет метода — пробуем веб al_audio.php с remixsid
     */
    suspend fun probe(session: VkSession): AudioProbeResult {
        val apiResult = probeApi(session)
        if (apiResult is AudioProbeResult.Ok) return apiResult

        Log.i(TAG, "API audio failed ($apiResult), trying web al_audio…")
        val webResult = webAudio.probe(session)
        if (webResult is AudioProbeResult.Ok) return webResult

        return when {
            apiResult is AudioProbeResult.Denied && apiResult.errorCode == 3 ->
                AudioProbeResult.Denied(
                    3,
                    "OAuth audio.* отключён; web: ${(webResult as? AudioProbeResult.Denied)?.message ?: webResult}"
                )
            else -> webResult
        }
    }

    suspend fun probeAndAlignUserAgent(session: VkSession): Pair<VkSession, AudioProbeResult> {
        val apiResult = probeApi(session)
        if (apiResult is AudioProbeResult.Ok) {
            return session.copy(
                userAgent = VkClientProfiles.Official.userAgent,
                audioVia = "api"
            ) to AudioProbeResult.Ok
        }

        Log.i(TAG, "API audio failed ($apiResult), trying web al_audio…")
        val webResult = webAudio.probe(session)
        if (webResult is AudioProbeResult.Ok) {
            return session.copy(audioVia = "web") to AudioProbeResult.Ok
        }

        val denied = when {
            apiResult is AudioProbeResult.Denied && apiResult.errorCode == 3 ->
                AudioProbeResult.Denied(
                    3,
                    "OAuth audio.* отключён; web: ${(webResult as? AudioProbeResult.Denied)?.message ?: webResult}"
                )
            webResult is AudioProbeResult.Denied -> webResult
            apiResult is AudioProbeResult.Denied -> apiResult
            else -> AudioProbeResult.Denied(null, "audio недоступен")
        }
        return session to denied
    }

    private suspend fun probeApi(session: VkSession): AudioProbeResult {
        val attempts = listOf(
            Triple("audio.get", mapOf("count" to "1"), "5.131"),
            Triple("audio.get", mapOf("count" to "1", "owner_id" to session.userId.toString()), "5.95")
        )
        var last: AudioProbeResult = AudioProbeResult.Denied(null, "no api attempt")
        for ((index, attempt) in attempts.withIndex()) {
            if (index > 0) delay(600)
            var result = callApi(session, attempt.first, attempt.second, attempt.third)
            if (result is AudioProbeResult.Denied && result.errorCode in setOf(6, 9)) {
                delay(1200)
                result = callApi(session, attempt.first, attempt.second, attempt.third)
            }
            Log.i(TAG, "probe API ${attempt.first} -> $result")
            if (result is AudioProbeResult.Ok) return result
            last = result
        }
        return last
    }

    private suspend fun callApi(
        session: VkSession,
        method: String,
        params: Map<String, String>,
        version: String
    ): AudioProbeResult {
        var lastNet: AudioProbeResult? = null
        for (base in VkApiClient.API_BASES) {
            try {
                val json = api.call(
                    method = method,
                    session = session,
                    params = params,
                    apiVersion = version,
                    userAgentOverride = VkClientProfiles.Official.userAgent,
                    post = true,
                    apiBase = base
                )
                return when {
                    json.has("response") -> AudioProbeResult.Ok
                    json.has("error") -> {
                        val err = json.getJSONObject("error")
                        AudioProbeResult.Denied(
                            err.optInt("error_code", -1),
                            err.optString("error_msg", "access denied") + " [$method]"
                        )
                    }
                    else -> AudioProbeResult.Denied(null, "Unexpected API")
                }
            } catch (e: Exception) {
                lastNet = AudioProbeResult.NetworkError(e.message ?: e.toString())
            }
        }
        return lastNet ?: AudioProbeResult.NetworkError("API unreachable")
    }

    suspend fun refreshIfPossible(session: VkSession): VkSession = session

    suspend fun readAppPermissions(session: VkSession): Int? {
        return try {
            delay(400)
            val json = api.call(
                method = "account.getAppPermissions",
                session = session,
                apiVersion = "5.131",
                post = true,
                apiBase = VkApiClient.API_BASE_RU
            )
            if (json.has("response")) json.optInt("response") else null
        } catch (_: Exception) {
            null
        }
    }

    fun hasAudioBit(permissions: Int?): Boolean {
        if (permissions == null) return false
        return permissions and 8 != 0
    }
}
