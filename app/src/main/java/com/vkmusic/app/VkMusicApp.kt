package com.vkmusic.app

import android.app.Application
import com.vkmusic.app.auth.AudioAccessProbe
import com.vkmusic.app.auth.SessionKeeper
import com.vkmusic.app.auth.SessionStore
import androidx.media3.common.util.UnstableApi
import com.vkmusic.app.player.CacheSettingsStore
import com.vkmusic.app.player.MusicPlayer
import com.vkmusic.app.vk.VkWebAudioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VkMusicApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sessionStore: SessionStore
        private set
    lateinit var sessionKeeper: SessionKeeper
        private set
    lateinit var webAudioClient: VkWebAudioClient
        private set
    lateinit var audioAccessProbe: AudioAccessProbe
        private set
    lateinit var musicPlayer: MusicPlayer
        private set

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        sessionKeeper = SessionKeeper()
        webAudioClient = VkWebAudioClient()
        audioAccessProbe = AudioAccessProbe(webAudioClient, sessionKeeper)
        appScope.launch { CacheSettingsStore.hydrate(this@VkMusicApp) }
        musicPlayer = MusicPlayer(this)
    }
}
