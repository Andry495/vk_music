package com.vkmusic.app

import android.app.Application
import com.vk.id.VKID
import com.vkmusic.app.auth.AudioAccessProbe
import com.vkmusic.app.auth.SessionStore
import com.vkmusic.app.auth.VkClientAuth
import com.vkmusic.app.auth.VkIdAuth
import com.vkmusic.app.player.MusicPlayer
import com.vkmusic.app.vk.VkApiClient
import com.vkmusic.app.vk.VkWebAudioClient
import java.util.Locale

class VkMusicApp : Application() {
    lateinit var sessionStore: SessionStore
        private set
    lateinit var vkApiClient: VkApiClient
        private set
    lateinit var webAudioClient: VkWebAudioClient
        private set
    lateinit var audioAccessProbe: AudioAccessProbe
        private set
    lateinit var vkClientAuth: VkClientAuth
        private set
    lateinit var musicPlayer: MusicPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        vkApiClient = VkApiClient()
        webAudioClient = VkWebAudioClient()
        audioAccessProbe = AudioAccessProbe(vkApiClient, webAudioClient)
        vkClientAuth = VkClientAuth(vkApiClient)
        musicPlayer = MusicPlayer(this)

        if (VkIdAuth.isConfigured) {
            VKID.init(this)
            VKID.instance.setLocale(Locale("ru"))
        }
    }
}
