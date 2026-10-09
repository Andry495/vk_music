package com.vkmusic.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.vkmusic.app.vk.VkClientProfiles

/**
 * WebView Implicit Flow. После успеха VK открывает blank.html с текстом
 * «не копируйте ссылку» — токен лежит в #hash. WebView часто не отдаёт hash
 * в onPageFinished, поэтому читаем window.location.href через JS.
 */
class VkLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var finished = false

    private val profile: VkClientProfiles.Profile = VkClientProfiles.Kate

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (intent.getBooleanExtra(EXTRA_CLEAR_COOKIES, true)) {
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = profile.userAgent
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.d(TAG, "onPageStarted $url")
                    maybeFinishWithToken(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    Log.d(TAG, "onPageFinished $url")
                    if (maybeFinishWithToken(url)) return
                    // Hash часто обрезан — достаём полный URL из JS
                    if (url != null && isBlankRedirect(url)) {
                        readLocationHref(view)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    Log.d(TAG, "visited $url")
                    if (maybeFinishWithToken(url)) return
                    if (url != null && isBlankRedirect(url)) {
                        readLocationHref(view)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString()
                    Log.d(TAG, "override $url")
                    if (maybeFinishWithToken(url)) return true
                    if (url != null && shouldHandOffToVkApp(url)) {
                        return true
                    }
                    return false
                }
            }
        }

        val root = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            }
        )

        webView.loadUrl(buildAuthorizeUrl(profile))
    }

    private fun readLocationHref(view: WebView?) {
        if (finished || view == null) return
        view.evaluateJavascript("(function(){return window.location.href;})();") { raw ->
            val href = raw
                ?.trim()
                ?.removeSurrounding("\"")
                ?.replace("\\u003d", "=")
                ?.replace("\\u0026", "&")
                ?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() && it != "null" }
            Log.d(TAG, "js href=$href")
            maybeFinishWithToken(href)
        }
    }

    private fun maybeFinishWithToken(url: String?): Boolean {
        if (finished || url.isNullOrBlank()) return false
        if (!isBlankRedirect(url) && !url.contains("access_token=")) return false

        val params = parseHashOrQuery(url)
        val error = params["error"]
        if (!error.isNullOrBlank()) {
            finished = true
            Log.w(TAG, "oauth error=$error")
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(
                    EXTRA_ERROR,
                    params["error_description"] ?: error
                )
            )
            finish()
            return true
        }

        val token = params["access_token"]?.takeIf { it.isNotBlank() } ?: return false
        val userId = params["user_id"]?.toLongOrNull() ?: return false
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L

        finished = true
        val cookies = collectCookies()
        Log.i(TAG, "token captured userId=$userId cookiesLen=${cookies.length} hasRemix=${cookies.contains("remixsid")}")
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_ACCESS_TOKEN, token)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_EXPIRES_IN, expiresIn)
                .putExtra(EXTRA_USER_AGENT, profile.userAgent)
                .putExtra(EXTRA_PROFILE, profile.name)
                .putExtra(EXTRA_COOKIES, cookies)
        )
        finish()
        return true
    }

    private fun collectCookies(): String {
        val cm = CookieManager.getInstance()
        return com.vkmusic.app.vk.VkWebAudioClient.collectVkCookies { host ->
            cm.getCookie(host)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VkMusicAuth"

        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_EXPIRES_IN = "expires_in"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CLEAR_COOKIES = "clear_cookies"
        const val EXTRA_COOKIES = "cookies"

        private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"
        private const val SCOPE =
            "audio,offline,friends,groups,status,wall,messages,photos,video,docs,notify"
        private const val API_V = "5.95"

        fun createIntent(
            context: Context,
            clearCookies: Boolean = true
        ): Intent =
            Intent(context, VkLoginActivity::class.java)
                .putExtra(EXTRA_CLEAR_COOKIES, clearCookies)

        fun buildAuthorizeUrl(profile: VkClientProfiles.Profile): String {
            return Uri.parse("https://oauth.vk.com/authorize").buildUpon()
                .appendQueryParameter("client_id", profile.clientId)
                .appendQueryParameter("display", "mobile")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("response_type", "token")
                .appendQueryParameter("v", API_V)
                .appendQueryParameter("revoke", "1")
                .build()
                .toString()
        }

        fun isBlankRedirect(url: String): Boolean =
            url.contains("oauth.vk.com/blank.html", ignoreCase = true) ||
                url.contains("oauth.vk.ru/blank.html", ignoreCase = true)

        fun parseHashOrQuery(url: String): Map<String, String> {
            val hash = url.substringAfter('#', missingDelimiterValue = "")
            val query = when {
                hash.isNotBlank() -> hash
                else -> url.substringAfter('?', missingDelimiterValue = "")
            }
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = Uri.decode(part.substring(0, idx))
                val value = Uri.decode(part.substring(idx + 1))
                key to value
            }.toMap()
        }

        private fun shouldHandOffToVkApp(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith("vk://") ||
                lower.startsWith("vkauthorize://") ||
                lower.startsWith("intent://") && lower.contains("vk")
        }
    }
}
