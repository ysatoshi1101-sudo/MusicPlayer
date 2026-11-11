package com.example.musicplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// 曲のアルバムアート手動設定機能で追加。選んだ画像を内部ストレージにコピーして保存する
// (PlaylistCoverHelper.ktと同じ考え方)。元の音声ファイルへの書き込みは行わず、
// アプリ内表示(DBのalbumArtPath)のみを更新する
object AlbumArtHelper {

    suspend fun saveManualAlbumArt(context: Context, songId: Long, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "album_art_manual").apply { mkdirs() }
                val file = File(dir, "song_${songId}_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }
}