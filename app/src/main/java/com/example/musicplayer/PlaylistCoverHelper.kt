package com.example.musicplayer

import android.net.Uri
import java.io.File

// プレイリストのカバー画像を解決する共通ロジック。
// 手動設定したカバーがあればそれを、なければプレイリスト内の一番上の曲(曲名順で最初)の
// アルバムアートを使う。曲が1曲もない場合はnull(呼び出し側でプレースホルダーにフォールバック)
object PlaylistCoverHelper {

    suspend fun resolveCoverUri(database: AppDatabase, playlist: Playlist, allSongs: List<Song>): Uri? {
        playlist.coverImagePath?.let { path ->
            return Uri.fromFile(File(path))
        }

        val songIds = database.playlistDao().getSongIdsInPlaylist(playlist.id).toSet()
        if (songIds.isEmpty()) return null

        // allSongsはSongRepository.getAllSongs()由来(曲名順)。プレイリスト詳細画面の
        // デフォルト表示順(未ソート時)と同じ「一番上の曲」を採用する
        val firstSong = allSongs.firstOrNull { it.id in songIds } ?: return null
        return firstSong.albumArtUri
    }
}