package com.example.musicplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    // プレイリストカバー機能で追加(v11)。手動設定した画像の内部ストレージパス。未設定はnull
    val coverImagePath: String? = null
)