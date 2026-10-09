package com.vkmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vk.id.AccessToken
import com.vk.id.auth.AuthCodeData
import com.vk.id.auth.VKIDAuthUiParams
import com.vk.id.onetap.common.OneTapOAuth
import com.vk.id.onetap.compose.onetap.OneTap
import com.vkmusic.app.auth.AuthState
import com.vkmusic.app.auth.VkLoginActivity
import com.vkmusic.app.data.Track

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val state = ui.authState) {
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

            is AuthState.Ready -> ReadyPane(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                sessionLabel = state.session.displayName
                    ?: "id${state.session.userId}",
                source = state.session.source.name,
                audioVia = state.session.audioVia.ifBlank { "web" },
                tracks = ui.tracks,
                tracksLoading = ui.tracksLoading,
                tracksError = ui.tracksError,
                busy = ui.busy,
                onReload = viewModel::reloadLibrary,
                onLogout = viewModel::logout
            )

            AuthState.LoggedOut -> LoginPane(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                ui = ui,
                viewModel = viewModel
            )

            is AuthState.NeedsAudioFallback -> FallbackPane(
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
        }
    }
}

@Composable
private fun ReadyPane(
    modifier: Modifier,
    sessionLabel: String,
    source: String,
    audioVia: String,
    tracks: List<Track>,
    tracksLoading: Boolean,
    tracksError: String?,
    busy: Boolean,
    onReload: () -> Unit,
    onLogout: () -> Unit
) {
    val viaLabel = when (audioVia) {
        "api" -> "OAuth API"
        else -> "веб (al_audio)"
    }
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("VK Music", style = MaterialTheme.typography.headlineSmall)
        Text(
            "$sessionLabel · $source · $viaLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (tracksLoading) "Загрузка…" else "Моя музыка: ${tracks.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReload, enabled = !tracksLoading && !busy) {
                Text("Обновить")
            }
            OutlinedButton(onClick = onLogout, enabled = !busy) {
                Text("Выйти")
            }
        }
        if (tracksError != null) {
            Text(
                tracksError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (tracksLoading && tracks.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = tracks,
                    key = { track: Track -> track.id }
                ) { track: Track ->
                    TrackRow(track)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            track.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            buildString {
                append(track.artist)
                if (track.durationSeconds > 0) {
                    append(" · ")
                    append("%d:%02d".format(track.durationSeconds / 60, track.durationSeconds % 60))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            "Войдите через VK — как в браузере, с подтверждением доступа. Регистрация приложения на id.vk.com не нужна.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = { viewModel.startWebLogin(VkLoginActivity.PROFILE_OFFICIAL) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Войти через VK")
        }
        OutlinedButton(
            onClick = { viewModel.startWebLogin(VkLoginActivity.PROFILE_KATE) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Войти (Kate, запасной)")
        }
        Text(
            "Основной вход — как официальный клиент (нужен для музыки). " +
                "На странице VK введите логин/пароль; «Войти через приложение» лучше не жать.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (ui.vkidConfigured) {
            Text("или VK ID", style = MaterialTheme.typography.labelLarge)
            OneTap(
                modifier = Modifier.fillMaxWidth(),
                onAuth = { _: OneTapOAuth?, token: AccessToken ->
                    viewModel.onVkIdAuth(token)
                },
                onAuthCode = { _: AuthCodeData, _: Boolean -> },
                onFail = { _: OneTapOAuth?, fail ->
                    viewModel.onVkIdFail(fail.description)
                },
                authParams = VKIDAuthUiParams {
                    scopes = setOf("vkid.personal_info")
                }
            )
        }

        Text("или", style = MaterialTheme.typography.labelLarge)

        if (!ui.showClientForm) {
            TextButton(onClick = viewModel::openClientForm) {
                Text("Войти логином и паролем")
            }
        } else {
            ClientLoginForm(ui, viewModel)
        }

        if (ui.busy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun FallbackPane(
    modifier: Modifier,
    ui: AuthUiState,
    viewModel: AuthViewModel,
    userLabel: String
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Нужен доступ к музыке", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Сессия $userLabel есть, но audio.* недоступен. Попробуйте другой способ входа или логин/пароль.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { viewModel.startWebLogin(VkLoginActivity.PROFILE_OFFICIAL) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Войти (официальный клиент)")
        }
        OutlinedButton(
            onClick = { viewModel.startWebLogin(VkLoginActivity.PROFILE_KATE) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text("Повторить (Kate)")
        }
        ClientLoginForm(ui, viewModel)
        TextButton(onClick = viewModel::logout) {
            Text("Выйти")
        }
        if (ui.busy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ClientLoginForm(ui: AuthUiState, viewModel: AuthViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = ui.login,
            onValueChange = viewModel::updateLogin,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Телефон или email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        OutlinedTextField(
            value = ui.password,
            onValueChange = viewModel::updatePassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        if (ui.needsTwoFactor) {
            ui.twoFactorHint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = ui.twoFactorCode,
                onValueChange = viewModel::updateTwoFactor,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Код 2FA") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Button(
            onClick = viewModel::submitClientLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.busy
        ) {
            Text(if (ui.needsTwoFactor) "Подтвердить код" else "Получить audio-токен")
        }
        if (ui.authState is AuthState.LoggedOut) {
            TextButton(onClick = viewModel::closeClientForm) {
                Text("Скрыть форму")
            }
        }
    }
}
