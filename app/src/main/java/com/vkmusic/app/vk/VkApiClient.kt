package com.vkmusic.app.vk

import com.vkmusic.app.auth.AuthSource
import com.vkmusic.app.auth.VkSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VkApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val API_VERSION = "5.199"
        const val API_BASE_RU = "https://api.vk.ru/method/"
        const val API_BASE_COM = "https://api.vk.com/method/"
        val API_BASES = listOf(API_BASE_RU, API_BASE_COM)
    }

    suspend fun call(
        method: String,
        session: VkSession,
        params: Map<String, String> = emptyMap(),
        apiVersion: String = API_VERSION,
        userAgentOverride: String? = null,
        post: Boolean = false,
        apiBase: String = API_BASE_RU
    ): JSONObject = withContext(Dispatchers.IO) {
        val ua = userAgentOverride?.takeIf { it.isNotBlank() }
            ?: session.userAgent.ifBlank {
                when (session.source) {
                    AuthSource.Client, AuthSource.WebView -> VkClientProfiles.Official.userAgent
                    AuthSource.VkId -> DEFAULT_UA
                }
            }

        val request = if (post) {
            val form = FormBody.Builder()
                .add("access_token", session.accessToken)
                .add("v", apiVersion)
                .add("https", "1")
                .add("lang", "ru")
            params.forEach { (k, v) -> form.add(k, v) }
            Request.Builder()
                .url(apiBase + method)
                .header("User-Agent", ua)
                .post(form.build())
                .build()
        } else {
            val urlBuilder = (apiBase + method).toHttpUrl().newBuilder()
                .addQueryParameter("access_token", session.accessToken)
                .addQueryParameter("v", apiVersion)
                .addQueryParameter("https", "1")
            params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
            Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", ua)
                .get()
                .build()
        }

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.take(200)}")
            }
            JSONObject(body)
        }
    }

    suspend fun rawGet(
        url: String,
        userAgent: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val urlBuilder = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", userAgent)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.take(200)}")
            }
            JSONObject(body)
        }
    }
}

private const val DEFAULT_UA =
    "VKMusicAndroid/0.1 (Android; VK ID)"

object VkClientProfiles {
    data class Profile(
        val name: String,
        val clientId: String,
        val clientSecret: String,
        val userAgent: String
    )

    val Kate = Profile(
        name = "Kate",
        clientId = "2685278",
        clientSecret = "lxhD8OD7dMsqtXIm5IUY",
        userAgent = "KateMobileAndroid/72.1 lite-535 (Android 11; SDK 30; arm64-v8a; Google Pixel 5; ru)"
    )

    /** Official Android client — нужен для catalog.* / современного audio API. */
    val Official = Profile(
        name = "Official",
        clientId = "2274003",
        clientSecret = "hHbZxrka2uZ6jB1inYsH",
        userAgent = "VKAndroidApp/8.99-25871 (Android 13; SDK 33; arm64-v8a; Google Pixel 7; ru; 1080x2400)"
    )

    val all = listOf(Official, Kate)
}
