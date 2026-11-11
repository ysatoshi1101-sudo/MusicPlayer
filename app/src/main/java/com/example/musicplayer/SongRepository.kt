package com.example.musicplayer

class SongRepository(private val database: AppDatabase) {

    suspend fun getAllSongs(): List<Song> {
        return database.songDao().getAllSongs().map {
            Song(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
                duration = it.duration,
                uri = android.net.Uri.parse(it.uri),
                albumArtUri = it.albumArtPath?.let { path ->
                    android.net.Uri.fromFile(java.io.File(path))
                },
                addedAt = it.addedAt,
                folderPath = it.folderPath,
                lyrics = it.lyrics
            )
        }
    }
}