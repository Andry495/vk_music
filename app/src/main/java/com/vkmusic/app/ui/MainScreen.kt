package com.vkmusic.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vkmusic.app.auth.VkSession
import com.vkmusic.app.data.LibrarySection
import com.vkmusic.app.data.MainTab
import com.vkmusic.app.data.Playlist
import com.vkmusic.app.data.Track
import com.vkmusic.app.player.CacheSettings
import com.vkmusic.app.player.CacheSettingsStore
import com.vkmusic.app.player.CacheUiStats
import com.vkmusic.app.player.PlaybackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    session: VkSession,
    ui: AuthUiState,
    playback: PlaybackState,
    onSelectTab: (MainTab) -> Unit,
    onLibrarySection: (LibrarySection) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onReload: () -> Unit,
    onPlay: (Track) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onCacheSettingsChange: ((CacheSettings) -> CacheSettings) -> Unit,
    onClearCache: () -> Unit,
    onLogout: () -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onRemoveFromPlaylist: (Track) -> Unit,
    onClosePlaylistPicker: () -> Unit,
    onPickPlaylist: (Playlist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onClosePlaylist: () -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onCloseCreatePlaylist: () -> Unit,
    onNewPlaylistTitle: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            ui.mainTab == MainTab.Player -> "Слушать"
                            ui.mainTab == MainTab.Search -> "Поиск"
                            ui.openPlaylist != null -> ui.openPlaylist.title
                            else -> "Медиатека"
                        }
                    )
                },
                navigationIcon = {
                    if (ui.mainTab == MainTab.Library && ui.openPlaylist != null) {
                        IconButton(onClick = onClosePlaylist) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (ui.mainTab == MainTab.Library &&
                        ui.librarySection == LibrarySection.Playlists &&
                        ui.openPlaylist == null
                    ) {
                        IconButton(onClick = onOpenCreatePlaylist) {
                            Icon(Icons.Default.Add, contentDescription = "Создать плейлист")
                        }
                    }
                    val openPl = ui.openPlaylist
                    if (ui.mainTab == MainTab.Library &&
                        openPl != null &&
                        openPl.ownerId == session.userId
                    ) {
                        IconButton(onClick = { playlistToDelete = openPl }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить плейлист")
                        }
                    }
                    TextButton(onClick = onReload, enabled = !ui.tracksLoading && !ui.playlistsLoading) {
                        Text("↻")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                // Как YT / Яндекс: плавающий баннер над табами, на полном плеере скрыт
                if (playback.track != null && ui.mainTab != MainTab.Player) {
                    MiniPlayerBar(
                        playback = playback,
                        onToggle = onToggle,
                        onNext = onNext,
                        onOpenPlayer = { onSelectTab(MainTab.Player) }
                    )
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = ui.mainTab == MainTab.Player,
                        onClick = { onSelectTab(MainTab.Player) },
                        icon = {
                            Icon(
                                if (ui.mainTab == MainTab.Player) Icons.Default.Headphones
                                else Icons.Outlined.Headphones,
                                null
                            )
                        },
                        label = { Text("Плеер") }
                    )
                    NavigationBarItem(
                        selected = ui.mainTab == MainTab.Search,
                        onClick = { onSelectTab(MainTab.Search) },
                        icon = {
                            Icon(
                                if (ui.mainTab == MainTab.Search) Icons.Default.Search
                                else Icons.Outlined.Search,
                                null
                            )
                        },
                        label = { Text("Поиск") }
                    )
                    NavigationBarItem(
                        selected = ui.mainTab == MainTab.Library,
                        onClick = { onSelectTab(MainTab.Library) },
                        icon = {
                            Icon(
                                if (ui.mainTab == MainTab.Library) Icons.Default.LibraryMusic
                                else Icons.Outlined.LibraryMusic,
                                null
                            )
                        },
                        label = { Text("Медиатека") }
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = ui.mainTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mainTab",
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { tab ->
            when (tab) {
                MainTab.Player -> PlayerHome(
                    playback = playback,
                    onToggle = onToggle,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onPlayQueueItem = onPlay,
                    onAddToMyMusic = onAddToMyMusic,
                    onRemoveFromMyMusic = onRemoveFromMyMusic,
                    onOpenAddToPlaylist = onOpenAddToPlaylist
                )
                MainTab.Search -> SearchHome(
                    ui = ui,
                    playingId = playback.track?.id,
                    onSearchChange = onSearchChange,
                    onSearch = onSearch,
                    onPlay = onPlay,
                    onAddToMyMusic = onAddToMyMusic,
                    onRemoveFromMyMusic = onRemoveFromMyMusic,
                    onOpenAddToPlaylist = onOpenAddToPlaylist
                )
                MainTab.Library -> LibraryHome(
                    userId = session.userId,
                    sessionLabel = session.displayName ?: "id${session.userId}",
                    ui = ui,
                    playingId = playback.track?.id,
                    onSection = onLibrarySection,
                    onPlay = onPlay,
                    onAddToMyMusic = onAddToMyMusic,
                    onRemoveFromMyMusic = onRemoveFromMyMusic,
                    onOpenAddToPlaylist = onOpenAddToPlaylist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenCreatePlaylist = onOpenCreatePlaylist,
                    onRequestDeletePlaylist = { playlistToDelete = it }
                )
            }
        }
    }

    if (ui.showSettings) {
        SettingsSheet(
            sessionLabel = session.displayName ?: "id${session.userId}",
            audioVia = session.audioVia.ifBlank { "web" },
            cacheSettings = ui.cacheSettings,
            cacheStats = ui.cacheStats,
            onCacheSettingsChange = onCacheSettingsChange,
            onClearCache = onClearCache,
            onLogout = onLogout,
            onDismiss = onCloseSettings
        )
    }

    if (ui.trackForPlaylistPicker != null && !ui.showCreatePlaylist) {
        PlaylistPickerSheet(
            track = ui.trackForPlaylistPicker,
            playlists = ui.playlists,
            loading = ui.playlistsLoading,
            onPick = onPickPlaylist,
            onCreate = onOpenCreatePlaylist,
            onDismiss = onClosePlaylistPicker
        )
    }

    if (ui.showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = onCloseCreatePlaylist,
            title = { Text("Новый плейлист") },
            text = {
                OutlinedTextField(
                    value = ui.newPlaylistTitle,
                    onValueChange = onNewPlaylistTitle,
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = onCreatePlaylist, enabled = !ui.busy) {
                    Text(
                        if (ui.trackForPlaylistPicker != null) "Создать и добавить"
                        else "Создать"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseCreatePlaylist) { Text("Отмена") }
            }
        )
    }

    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Удалить плейлист?") },
            text = { Text("«${pl.title}» будет удалён. Треки в Моей музыке останутся.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(pl)
                        playlistToDelete = null
                    },
                    enabled = !ui.busy
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun PlayerHome(
    playback: PlaybackState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayQueueItem: (Track) -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit
) {
    val track = playback.track
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CoverArt(url = track?.thumbnailUrl, size = if (track == null) 200.dp else 160.dp)
                    Spacer(Modifier.height(16.dp))
                    if (track == null) {
                        Text("Очередь пуста", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Выберите трек в медиатеке или поиске",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (playback.status != null || playback.cacheStats.isDownloading) {
                            Text(
                                when {
                                    playback.cacheStats.isDownloading -> "Докачка в кэш…"
                                    else -> playback.status.orEmpty()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        val duration = maxOf(
                            playback.durationMs,
                            track.durationSeconds * 1000L
                        ).coerceAtLeast(1L)
                        Slider(
                            value = (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                            onValueChange = { onSeek((it * duration).toLong()) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatMs(playback.positionMs), style = MaterialTheme.typography.bodySmall)
                            Text(formatMs(duration), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            IconButton(onClick = onPrevious) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий")
                            }
                            IconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                                Icon(
                                    if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    null,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            IconButton(onClick = onNext) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Следующий")
                            }
                            IconButton(onClick = { onOpenAddToPlaylist(track) }) {
                                Icon(Icons.Outlined.PlaylistAdd, contentDescription = "В плейлист")
                            }
                        }
                    }
                }
            }
        }
        if (playback.queue.isNotEmpty()) {
            item {
                Text("Очередь · ${playback.queue.size}", style = MaterialTheme.typography.titleMedium)
            }
            items(playback.queue, key = { it.id }) { item ->
                TrackRow(
                    track = item,
                    playing = playback.track?.id == item.id,
                    onClick = { onPlayQueueItem(item) },
                    onAddToMyMusic = onAddToMyMusic,
                    onRemoveFromMyMusic = onRemoveFromMyMusic,
                    onOpenAddToPlaylist = onOpenAddToPlaylist
                )
            }
        }
    }
}

@Composable
private fun SearchHome(
    ui: AuthUiState,
    playingId: String?,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (Track) -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            placeholder = { Text("Трек, исполнитель…") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Искать")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        TrackListPane(
            tracks = ui.tracks,
            loading = ui.tracksLoading,
            error = ui.tracksError,
            emptyTitle = "Поиск VK Music",
            emptySubtitle = "Введите запрос · у трека ⋯ → в Мою музыку / плейлист",
            playingId = playingId,
            onPlay = onPlay,
            onAddToMyMusic = onAddToMyMusic,
            onRemoveFromMyMusic = onRemoveFromMyMusic,
            onOpenAddToPlaylist = onOpenAddToPlaylist
        )
    }
}

@Composable
private fun LibraryHome(
    userId: Long,
    sessionLabel: String,
    ui: AuthUiState,
    playingId: String?,
    onSection: (LibrarySection) -> Unit,
    onPlay: (Track) -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onRemoveFromPlaylist: (Track) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onRequestDeletePlaylist: (Playlist) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (ui.openPlaylist == null) {
            Text(
                sessionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = ui.librarySection == LibrarySection.Recoms,
                    onClick = { onSection(LibrarySection.Recoms) },
                    label = { Text("Реком") }
                )
                FilterChip(
                    selected = ui.librarySection == LibrarySection.MyMusic,
                    onClick = { onSection(LibrarySection.MyMusic) },
                    label = { Text("Моя") }
                )
                FilterChip(
                    selected = ui.librarySection == LibrarySection.Playlists,
                    onClick = { onSection(LibrarySection.Playlists) },
                    label = { Text("Плейлисты") }
                )
            }
        }

        when {
            ui.librarySection == LibrarySection.Playlists && ui.openPlaylist == null -> {
                PlaylistsPane(
                    playlists = ui.playlists,
                    userId = userId,
                    loading = ui.playlistsLoading,
                    error = ui.tracksError,
                    onOpen = onOpenPlaylist,
                    onCreate = onOpenCreatePlaylist,
                    onDelete = onRequestDeletePlaylist
                )
            }
            else -> {
                TrackListPane(
                    tracks = ui.tracks,
                    loading = ui.tracksLoading,
                    error = ui.tracksError,
                    emptyTitle = when (ui.librarySection) {
                        LibrarySection.MyMusic -> "Моя музыка пуста"
                        LibrarySection.Playlists -> "Плейлист пуст"
                        else -> "Медиатека пуста"
                    },
                    emptySubtitle = "Из поиска/рекомендаций: ⋯ → Добавить",
                    playingId = playingId,
                    onPlay = onPlay,
                    onAddToMyMusic = onAddToMyMusic,
                    onRemoveFromMyMusic = onRemoveFromMyMusic,
                    onOpenAddToPlaylist = onOpenAddToPlaylist,
                    onRemoveFromPlaylist = if (ui.openPlaylist != null) onRemoveFromPlaylist else null
                )
            }
        }
    }
}

@Composable
private fun PlaylistsPane(
    playlists: List<Playlist>,
    userId: Long,
    loading: Boolean,
    error: String?,
    onOpen: (Playlist) -> Unit,
    onCreate: () -> Unit,
    onDelete: (Playlist) -> Unit
) {
    when {
        loading && playlists.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        playlists.isEmpty() -> {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(title = "Плейлистов нет", subtitle = error ?: "Создайте первый плейлист")
                Button(onClick = onCreate, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Создать плейлист")
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(playlists, key = { it.fullId }) { pl ->
                    var menuOpen by remember(pl.fullId) { mutableStateOf(false) }
                    Surface(
                        onClick = { onOpen(pl) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(url = pl.coverUrl, size = 56.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pl.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (pl.trackCount > 0) "${pl.trackCount} треков" else "Плейлист",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (pl.ownerId == userId) {
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                                    }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Удалить плейлист") },
                                            onClick = {
                                                menuOpen = false
                                                onDelete(pl)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackListPane(
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    emptyTitle: String,
    emptySubtitle: String,
    playingId: String?,
    onPlay: (Track) -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onRemoveFromPlaylist: ((Track) -> Unit)? = null
) {
    when {
        loading && tracks.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        tracks.isEmpty() -> EmptyState(title = emptyTitle, subtitle = error ?: emptySubtitle)
        else -> {
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        playing = playingId == track.id,
                        onClick = { onPlay(track) },
                        onAddToMyMusic = onAddToMyMusic,
                        onRemoveFromMyMusic = onRemoveFromMyMusic,
                        onOpenAddToPlaylist = onOpenAddToPlaylist,
                        onRemoveFromPlaylist = onRemoveFromPlaylist
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    onAddToMyMusic: (Track) -> Unit,
    onRemoveFromMyMusic: (Track) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onRemoveFromPlaylist: ((Track) -> Unit)? = null
) {
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (playing) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArt(url = track.thumbnailUrl, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
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
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                tint = MaterialTheme.colorScheme.primary
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (track.canAddToLibrary) {
                        DropdownMenuItem(
                            text = { Text("В Мою музыку") },
                            onClick = {
                                menuOpen = false
                                onAddToMyMusic(track)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("В плейлист…") },
                        onClick = {
                            menuOpen = false
                            onOpenAddToPlaylist(track)
                        }
                    )
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Удалить из плейлиста") },
                            onClick = {
                                menuOpen = false
                                onRemoveFromPlaylist(track)
                            }
                        )
                    }
                    if (track.canRemoveFromLibrary) {
                        DropdownMenuItem(
                            text = { Text("Удалить из Моей музыки") },
                            onClick = {
                                menuOpen = false
                                onRemoveFromMyMusic(track)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    track: Track,
    playlists: List<Playlist>,
    loading: Boolean,
    onPick: (Playlist) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Добавить «${track.title}»",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                playlists.forEach { pl ->
                    Surface(
                        onClick = { onPick(pl) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            pl.title,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
                if (playlists.isEmpty()) {
                    Text(
                        "Плейлистов пока нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text("Создать плейлист")
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playback: PlaybackState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val track = playback.track ?: return
    val durationSafe = maxOf(
        playback.durationMs.takeIf { it > 0 } ?: 0L,
        track.durationSeconds * 1000L
    ).coerceAtLeast(1L)
    val progress = (playback.positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
    val buffered = (playback.bufferedPositionMs.toFloat() / durationSafe).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Surface(
            onClick = onOpenPlayer,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(18.dp), clip = false)
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverArt(url = track.thumbnailUrl, size = 52.dp, corner = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                playback.cacheStats.isDownloading -> "Докачка…"
                                playback.status != null -> playback.status!!
                                else -> track.artist
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (playback.cacheStats.isDownloading || playback.status != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledIconButton(
                        onClick = onToggle,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playback.playing) "Пауза" else "Играть",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Далее")
                    }
                }
                // Прогресс снизу карточки — как у Яндекс / YT Music
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (buffered > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(buffered)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    sessionLabel: String,
    audioVia: String,
    cacheSettings: CacheSettings,
    cacheStats: CacheUiStats,
    onCacheSettingsChange: ((CacheSettings) -> CacheSettings) -> Unit,
    onClearCache: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Аккаунт", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "$sessionLabel · ${if (audioVia == "api") "OAuth API" else "веб (al_audio)"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Выйти")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Кэш аудио", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Фоновая докачка треков целиком в локальный кэш — сохранённое можно слушать оффлайн.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Занято ${cacheStats.totalMbLabel} / ${cacheStats.maxMbLabel} МБ · ${cacheStats.totalKeys} ключей",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { cacheStats.diskFill },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Text(
                    "Текущий трек: ${cacheStats.trackMbLabel} МБ" +
                        if (cacheStats.isDownloading) " · качается" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Размер кэша", style = MaterialTheme.typography.titleMedium)
            SettingsChipRow(
                options = CacheSettingsStore.MAX_MB_OPTIONS.map { "$it МБ" to it },
                selected = cacheSettings.maxCacheMb
            ) { mb -> onCacheSettingsChange { it.copy(maxCacheMb = mb) } }

            Text("Докачка вперёд (целиком)", style = MaterialTheme.typography.titleMedium)
            SettingsChipRow(
                options = CacheSettingsStore.PREFETCH_AHEAD_OPTIONS.map { "$it" to it },
                selected = cacheSettings.prefetchAhead
            ) { n -> onCacheSettingsChange { it.copy(prefetchAhead = n) } }

            OutlinedButton(onClick = onClearCache, modifier = Modifier.fillMaxWidth()) {
                Text("Очистить кэш аудио")
            }
        }
    }
}

@Composable
private fun <T> SettingsChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.MusicNote,
            null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CoverArt(url: String?, size: Dp, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MusicNote,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.4f)
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
