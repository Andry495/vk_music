package com.vkmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkmusic.app.auth.AuthState

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearMessage()
    }
    LaunchedEffect(ui.playbackError) {
        val msg = ui.playbackError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearMessage()
    }

    when (val state = ui.authState) {
        is AuthState.Ready -> {
            Box(Modifier.fillMaxSize()) {
                MainScreen(
                    session = state.session,
                    ui = ui,
                    playback = playback,
                    onSelectTab = viewModel::selectMainTab,
                    onLibrarySection = viewModel::selectLibrarySection,
                    onSearchChange = viewModel::updateSearchQuery,
                    onSearch = viewModel::submitSearch,
                    onReload = viewModel::reloadLibrary,
                    onPlay = viewModel::playTrack,
                    onToggle = viewModel::togglePlayback,
                    onNext = viewModel::playNext,
                    onPrevious = viewModel::playPrevious,
                    onSeek = viewModel::seekTo,
                    onOpenSettings = viewModel::openSettings,
                    onCloseSettings = viewModel::closeSettings,
                    onCacheSettingsChange = viewModel::updateCacheSettings,
                    onClearCache = viewModel::clearAudioCache,
                    onLogout = viewModel::logout,
                    onAddToMyMusic = viewModel::addToMyMusic,
                    onRemoveFromMyMusic = viewModel::removeFromMyMusic,
                    onOpenAddToPlaylist = viewModel::openAddToPlaylist,
                    onRemoveFromPlaylist = viewModel::removeFromPlaylist,
                    onClosePlaylistPicker = viewModel::closePlaylistPicker,
                    onPickPlaylist = viewModel::addTrackToPlaylist,
                    onOpenPlaylist = viewModel::openPlaylist,
                    onClosePlaylist = viewModel::closePlaylist,
                    onOpenCreatePlaylist = viewModel::openCreatePlaylist,
                    onCloseCreatePlaylist = viewModel::closeCreatePlaylist,
                    onNewPlaylistTitle = viewModel::updateNewPlaylistTitle,
                    onCreatePlaylist = viewModel::createPlaylist,
                    onDeletePlaylist = viewModel::deletePlaylist
                )
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 140.dp)
                )
            }
        }

        else -> {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                when (state) {
                    AuthState.Loading -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Проверка сессии…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    AuthState.LoggedOut -> LoginPane(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        ui = ui,
                        viewModel = viewModel
                    )

                    is AuthState.NeedsReauth -> ReauthPane(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        ui = ui,
                        viewModel = viewModel,
                        userLabel = state.session.displayName
                            ?: "id${state.session.userId}"
                    )

                    is AuthState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun LoginPane(
    modifier: Modifier,
    ui: AuthUiState,
    viewModel: AuthViewModel
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VK Music", style = MaterialTheme.typography.displaySmall)
        Text(
            "Войдите через браузер Kate — телефон, SMS, Max или пароль. Сессия сохранится для музыки.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = viewModel::startWebLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Войти через Kate")
        }

        if (ui.busy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ReauthPane(
    modifier: Modifier,
    ui: AuthUiState,
    viewModel: AuthViewModel,
    userLabel: String
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Нужен повторный вход", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Сессия $userLabel не даёт доступ к музыке. Войдите через Kate ещё раз.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = viewModel::startWebLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Войти через Kate")
        }
        TextButton(onClick = viewModel::logout) {
            Text("Выйти")
        }
        if (ui.busy) {
            CircularProgressIndicator()
        }
    }
}
