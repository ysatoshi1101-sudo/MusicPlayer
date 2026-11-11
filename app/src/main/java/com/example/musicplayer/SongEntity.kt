package com.example.musicplayer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["documentId"], unique = true)]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,
    val documentId: String,
    val albumArtPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val folderPath: String = "",
    val lyrics: String? = null // 歌詞表示機能で追加(v10)。既存曲はnullのまま
)