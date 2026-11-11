package com.example.musicplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream

class LibraryScanner(private val context: Context, private val database: AppDatabase) {

    private data class ParsedMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val albumArtBytes: ByteArray?,
        val lyrics: String? = null // 歌詞表示機能で追加(v10)
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    fun getSavedFolderUris(): Set<String> {
        return prefs.getStringSet("scanned_folders", emptySet()) ?: emptySet()
    }

    private fun saveFolderUri(uri: Uri) {
        val current = getSavedFolderUris().toMutableSet()
        current.add(uri.toString())
        prefs.edit().putStringSet("scanned_folders", current).apply()
    }

    // フォルダを新しく登録してスキャンする(「フォルダを追加」ボタンから呼ばれる)
    suspend fun addFolder(treeUri: Uri): Int {
        // 曲情報編集機能(元ファイルへの反映)に対応するため、読み取りに加えて書き込み権限も取得する
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        saveFolderUri(treeUri)
        return scanFolder(treeUri)
    }

    // 登録済みの全フォルダを再スキャンする(起動時の自動再スキャン・設定画面の手動更新の両方から呼ばれる)
    suspend fun rescanAllFolders(): Int {
        var addedCount = 0
        for (folderUriString in getSavedFolderUris()) {
            addedCount += scanFolder(Uri.parse(folderUriString))
        }
        return addedCount
    }

    // 実ファイルが存在しなくなった曲をライブラリから取り除く(お気に入り・プレイリストの紐付けも一緒に削除)
    suspend fun removeMissingFiles(): Int = withContext(Dispatchers.IO) {
        var removedCount = 0
        val allSongs = database.songDao().getAllSongs()
        for (song in allSongs) {
            val exists = try {
                DocumentFile.fromSingleUri(context, Uri.parse(song.uri))?.exists() == true
            } catch (e: Exception) {
                false
            }
            if (!exists) {
                database.playlistDao().removeSongFromAllPlaylists(song.id)
                database.favoriteDao().removeFavorite(song.id)
                database.songDao().deleteSongById(song.id)
                removedCount++
            }
        }
        removedCount
    }

    // フォルダ配下(サブフォルダ含む)を再帰的にたどり、音楽ファイルだけを新規追加する
    private suspend fun scanFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        var addedCount = 0

        // フォルダとその相対パス(表示用)をセットで管理する
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(rootDoc to (rootDoc.name ?: ""))

        while (stack.isNotEmpty()) {
            val (dir, path) = stack.removeLast()
            val children = dir.listFiles()
            for (child in children) {
                if (child.isDirectory) {
                    val childPath = if (path.isBlank()) (child.name ?: "") else "$path/${child.name ?: ""}"
                    stack.addLast(child to childPath)
                } else if (child.isFile && child.type?.startsWith("audio/") == true) {
                    val inserted = insertSongIfNew(child.uri, child.name ?: "不明な曲", path)
                    if (inserted) addedCount++
                }
            }
        }
        addedCount
    }
    // 曲を1件、DBへ登録する(すでに登録済み・読み込み失敗なら false を返す)
    suspend fun insertSongIfNew(uri: Uri, fallbackDisplayName: String, folderPath: String): Boolean {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            null
        }

        if (documentId != null) {
            if (database.songDao().findByDocumentId(documentId) != null) return false
        } else {
            if (database.songDao().findByUri(uri.toString()) != null) return false
        }

        var parsed = parseMetadataWithJAudiotagger(uri, fallbackDisplayName)
        if (parsed == null) {
            parsed = parseMetadataWithMediaMetadataRetriever(uri, fallbackDisplayName) ?: return false
        }

        val albumArtPath = parsed.albumArtBytes?.let { saveAlbumArtBytes(it) }

        database.songDao().insertSong(
            SongEntity(
                title = parsed.title,
                artist = parsed.artist,
                album = parsed.album,
                duration = parsed.duration,
                uri = uri.toString(),
                documentId = documentId ?: uri.toString(),
                albumArtPath = albumArtPath,
                addedAt = System.currentTimeMillis(),
                folderPath = folderPath,
                lyrics = parsed.lyrics
            )
        )
        return true
    }

    // ファイルピッカーで1曲だけ手動追加するとき用(フォルダ経由ではないのでfolderPathは空にする)
    suspend fun insertManualSong(uri: Uri, fallbackDisplayName: String): Boolean {
        return insertSongIfNew(uri, fallbackDisplayName, "")
    }

    private fun saveAlbumArtBytes(bytes: ByteArray): String? {
        return try {
            val artDir = File(context.filesDir, "album_art").apply { mkdirs() }
            val artFile = File(artDir, "${System.currentTimeMillis()}_${(0..99999).random()}.jpg")
            FileOutputStream(artFile).use { output -> output.write(bytes) }
            artFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun guessExtension(displayName: String, uri: Uri): String {
        if (displayName.contains(".")) {
            return displayName.substringAfterLast(".").lowercase()
        }
        val mimeType = context.contentResolver.getType(uri) ?: ""
        return when {
            mimeType.contains("flac") -> "flac"
            mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
            mimeType.contains("ogg") -> "ogg"
            mimeType.contains("wav") -> "wav"
            else -> "mp3"
        }
    }

    private suspend fun parseMetadataWithJAudiotagger(uri: Uri, displayName: String): ParsedMetadata? {
        return withContext(Dispatchers.IO) {
            val extension = guessExtension(displayName, uri)
            val tempFile = File(context.cacheDir, "tag_temp_${System.currentTimeMillis()}.$extension")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: return@withContext null

                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag

                val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: displayName
                val artist = tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() } ?: "不明なアーティスト"
                val album = tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() } ?: "不明なアルバム"
                val durationMs = audioFile.audioHeader.trackLength.toLong() * 1000L
                val albumArtBytes = tag?.firstArtwork?.binaryData
                // 歌詞タグ(USLT等)を読み取る。無ければnullのまま
                val lyrics = tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }

                ParsedMetadata(title, artist, album, durationMs, albumArtBytes, lyrics)
            } catch (e: Exception) {
                null
            } finally {
                tempFile.delete()
            }
        }
    }

    // MediaMetadataRetrieverは歌詞タグの取得に対応していないため、lyricsは常にnullになる
    private fun parseMetadataWithMediaMetadataRetriever(uri: Uri, fallbackDisplayName: String): ParsedMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fallbackDisplayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "不明なアーティスト"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "不明なアルバム"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val albumArtBytes = retriever.embeddedPicture
            ParsedMetadata(title, artist, album, duration, albumArtBytes, lyrics = null)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}