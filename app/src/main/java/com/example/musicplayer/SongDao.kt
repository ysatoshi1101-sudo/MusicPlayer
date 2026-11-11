package com.example.musicplayer

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SongDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: Long)

    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): SongEntity?

    @Query("SELECT * FROM songs WHERE documentId = :documentId LIMIT 1")
    suspend fun findByDocumentId(documentId: String): SongEntity?

    // 曲名・アーティスト・アルバムをアプリ内表示用に書き換える(実ファイルのタグは変更しない)
    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album WHERE id = :id")
    suspend fun updateSongMetadata(id: Long, title: String, artist: String, album: String)

    // アルバムアート手動設定機能で追加
    @Query("UPDATE songs SET albumArtPath = :albumArtPath WHERE id = :id")
    suspend fun updateAlbumArtPath(id: Long, albumArtPath: String?)
    // 歌詞表示機能で追加(v10)
    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :id")
    suspend fun updateLyrics(id: Long, lyrics: String?)

    // 歌詞表示機能で追加(v10)。フル画面プレーヤーで現在の曲の歌詞を取得するために使用
    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: Long): SongEntity?
}