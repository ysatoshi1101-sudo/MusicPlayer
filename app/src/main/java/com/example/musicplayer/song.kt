package com.example.musicplayer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: android.net.Uri,
    val albumArtUri: android.net.Uri?,
    val addedAt: Long,
    val folderPath: String,
    val lyrics: String? = null
)