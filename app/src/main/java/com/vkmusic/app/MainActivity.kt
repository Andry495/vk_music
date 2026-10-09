package com.vkmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vkmusic.app.auth.VkLoginActivity
import com.vkmusic.app.ui.AuthScreen
import com.vkmusic.app.ui.AuthViewModel
import com.vkmusic.app.ui.theme.VkMusicTheme

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authViewModel.onWebLoginResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authViewModel.launchWebLogin = { profile ->
            webLoginLauncher.launch(VkLoginActivity.createIntent(this, profile))
        }
        enableEdgeToEdge()
        setContent {
            AppRoot(authViewModel)
        }
    }

    override fun onDestroy() {
        authViewModel.launchWebLogin = null
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(authViewModel: AuthViewModel) {
    VkMusicTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AuthScreen(viewModel = authViewModel)
            }
        }
    }
}
