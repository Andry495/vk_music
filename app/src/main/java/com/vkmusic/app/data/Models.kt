package com.vkmusic.app.data

data class Track(
    val id: String,
    val ownerId: Long,
    val audioId: Long,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0L,
    val streamUrl: String? = null,
    val thumbnailUrl: String? = null
)
