package com.vkmusic.app.data

data class Track(
    val id: String,
    val ownerId: Long,
    val audioId: Long,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0L,
    val streamUrl: String? = null,
    val thumbnailUrl: String? = null,
    /** ownerId_audioId_actionHash_urlHash для act=reload_audio */
    val reloadKey: String? = null,
    val addHash: String? = null,
    val deleteHash: String? = null,
    val accessKey: String? = null,
    val trackCode: String? = null,
    val source: TrackSource = TrackSource.Library
) {
    val canAddToLibrary: Boolean get() = !addHash.isNullOrBlank()
    val canRemoveFromLibrary: Boolean get() = !deleteHash.isNullOrBlank()
}

data class Playlist(
    val id: Long,
    val ownerId: Long,
    val title: String,
    val trackCount: Int = 0,
    val accessHash: String? = null,
    /** edit_hash — нужен для удаления/правки своего плейлиста. */
    val editHash: String? = null,
    val coverUrl: String? = null
) {
    val fullId: String get() = "${ownerId}_$id"
}

enum class TrackSource {
    Library,
    Recoms,
    Search,
    Playlist
}

enum class MainTab {
    Player,
    Search,
    Library
}

enum class LibrarySection {
    Recoms,
    MyMusic,
    Playlists
}

sealed class LibraryActionResult {
    data object Ok : LibraryActionResult()
    data class Error(val message: String) : LibraryActionResult()
}
