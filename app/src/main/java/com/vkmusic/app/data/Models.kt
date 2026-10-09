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
    val source: TrackSource = TrackSource.Library
)

enum class TrackSource {
    Library,
    Recoms,
    Search
}
