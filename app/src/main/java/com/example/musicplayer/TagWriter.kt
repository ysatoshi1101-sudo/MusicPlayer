package com.example.musicplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream

// 曲情報編集で「元ファイルも更新」が有効なときに、実際の音楽ファイルのタグを書き換える。
// SAFのcontent:// URIは直接編集できないため、①一時ファイルにコピー→②タグ書き込み→
// ③書き込みが成功したことを確認してから元URIへ上書き、という手順を踏む。
// 途中(①②)で失敗した場合は元ファイルには一切触れないため安全。
object TagWriter {

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun writeTags(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        album: String
    ): Result = withContext(Dispatchers.IO) {
        val extension = guessExtension(context, uri)
        val tempFile = File(context.cacheDir, "tagwrite_${System.currentTimeMillis()}.$extension")

        try {
            // ①元ファイルを一時ファイルにコピーする
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return@withContext Result.Failure("ファイルを読み込めませんでした")

            // ②一時ファイル側のタグを書き換える(この時点ではまだ元ファイルは無変更)
            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, album)
            audioFile.commit()

            // ③一時ファイルへの書き込みが成功したので、元のURIへ上書きする。
            //   この書き込みには、フォルダ追加時に取得した永続的な書き込み権限が必要。
            //   権限がない場合はここで例外になりFailureを返す(元ファイルは壊れない)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return@withContext Result.Failure("元ファイルへの書き込み権限がありません")

            Result.Success
        } catch (e: Exception) {
            Result.Failure(e.message ?: "不明なエラーが発生しました")
        } finally {
            tempFile.delete()
        }
    }

    // LibraryScanner.ktのguessExtension()と同じ考え方(ファイル名の拡張子 → 無ければMIMEタイプから推測)
    private fun guessExtension(context: Context, uri: Uri): String {
        val displayName = queryDisplayName(context, uri)
        if (displayName != null && displayName.contains(".")) {
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

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}