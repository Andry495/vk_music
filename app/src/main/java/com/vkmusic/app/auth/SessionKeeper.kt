package com.vkmusic.app.auth

import android.util.Log
import android.webkit.CookieManager
import com.vkmusic.app.vk.VkClientProfiles
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Поддержка cookie-сессии: подмешивание CookieManager + HTTP-пинг VK,
 * чтобы продлить remixsid без повторного логина, пока сессия жива.
 */
class SessionKeeper(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun enrichFromCookieJar(session: VkSession): VkSession = withContext(Dispatchers.Main) {
        val fromJar = VkWebAudioClient.collectVkCookies { host ->
            CookieManager.getInstance().getCookie(host)
        }
        mergeCookies(session, fromJar)
    }

    /**
     * Пинг m.vk / vk.ru с текущими cookies. Если VK не увёл на login — сессия жива,
     * обновляем cookie-строку из Set-Cookie и CookieManager.
     */
    suspend fun refreshIfAlive(session: VkSession): VkSession? = withContext(Dispatchers.IO) {
        var current = session
        if (!current.cookies.contains("remixsid", ignoreCase = true)) {
            current = withContext(Dispatchers.Main) { enrichFromCookieJar(current) }
        }
        if (!current.cookies.contains("remixsid", ignoreCase = true)) {
            Log.w(TAG, "refresh: no remixsid")
            return@withContext null
        }

        injectIntoCookieManager(current.cookies)
        val ua = current.userAgent.ifBlank { VkClientProfiles.Kate.userAgent }
        val urls = listOf(
            "https://m.vk.ru/",
            "https://vk.ru/audio"
        )
        var alive = false
        for (url in urls) {
            val ok = ping(url, current.cookies, ua)
            if (ok) {
                alive = true
                break
            }
        }
        if (!alive) {
            Log.w(TAG, "refresh: session expired (redirected to login)")
            return@withContext null
        }

        val merged = withContext(Dispatchers.Main) {
            val jar = VkWebAudioClient.collectVkCookies { host ->
                CookieManager.getInstance().getCookie(host)
            }
            mergeCookies(current, jar)
        }
        Log.i(TAG, "refresh ok cookiesLen=${merged.cookies.length}")
        merged.copy(userAgent = ua)
    }

    private fun ping(url: String, cookies: String, userAgent: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val finalHost = response.request.url.host.lowercase()
                val loc = response.header("Location").orEmpty().lowercase()
                val toLogin = finalHost.contains("login.vk") ||
                    loc.contains("login.vk") ||
                    loc.contains("oauth.vk")
                if (toLogin) return false
                // Принимаем 2xx/3xx на vk/m.vk
                response.isSuccessful || response.code in 300..399
            }
        } catch (e: Exception) {
            Log.w(TAG, "ping $url ${e.message}")
            false
        }
    }

    private fun mergeCookies(session: VkSession, extra: String): VkSession {
        if (extra.isBlank()) return session
        val map = linkedMapOf<String, String>()
        fun putAll(raw: String) {
            raw.split(';').forEach { part ->
                val p = part.trim()
                val eq = p.indexOf('=')
                if (eq > 0) {
                    map[p.substring(0, eq)] = p.substring(eq + 1)
                }
            }
        }
        putAll(session.cookies)
        putAll(extra)
        val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return if (merged == session.cookies) session else session.copy(cookies = merged)
    }

    private fun injectIntoCookieManager(cookies: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val hosts = listOf(
            "https://vk.ru",
            "https://m.vk.ru",
            "https://vk.com",
            "https://m.vk.com"
        )
        cookies.split(';').forEach { part ->
            val p = part.trim()
            if (p.contains('=')) {
                hosts.forEach { host -> cm.setCookie(host, p) }
            }
        }
        cm.flush()
    }

    companion object {
        private const val TAG = "VkMusicAuth"
    }
}
