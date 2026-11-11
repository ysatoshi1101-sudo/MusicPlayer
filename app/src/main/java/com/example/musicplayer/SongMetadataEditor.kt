package com.example.musicplayer

import android.content.Context
import kotlinx.coroutines.flow.first

// 曲情報編集(曲名・アーティスト・アルバム)の保存処理を1箇所にまとめたヘルパー。
// 設定画面の「曲情報の編集方法」(アプリ内のみ / 元ファイルも更新)に応じて、
// DBだけを更新するか、実ファイルのタグ書き込みも試みるかを振り分ける。
// 呼び出し元(SongsFragment等)は、このヘルパーを呼んで返ってきたメッセージを
// Toast表示するだけでよい(DBは呼び出し元で改めて更新する必要はない)
object SongMetadataEditor {

    suspend fun save(
        context: Context,
        database: AppDatabase,
        song: Song,
        title: String,
        artist: String,
        album: String
    ): String {
        val editOriginalFile = ThemePreferences(context).editOriginalFileEnabled.first()

        if (!editOriginalFile) {
            database.songDao().updateSongMetadata(song.id, title, artist, album)
            return "曲情報を更新しました"
        }

        return when (val result = TagWriter.writeTags(context, song.uri, title, artist, album)) {
            is TagWriter.Result.Success -> {
                database.songDao().updateSongMetadata(song.id, title, artist, album)
                "曲情報を更新しました(元ファイルにも反映しました)"
            }
            is TagWriter.Result.Failure -> {
                // ファイルへの書き込みが失敗しても、アプリ内表示だけは更新しておく
                database.songDao().updateSongMetadata(song.id, title, artist, album)
                "曲情報を更新しました(元ファイルへの反映に失敗: ${result.message})"
            }
        }
    }
}