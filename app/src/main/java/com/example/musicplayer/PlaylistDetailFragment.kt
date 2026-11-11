package com.example.musicplayer

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PlaylistDetailFragment : Fragment() {

    private enum class SortField { TITLE, ARTIST, ALBUM }

    companion object {
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_NAME = "playlist_name"

        fun newInstance(playlistId: Long, playlistName: String): PlaylistDetailFragment {
            val fragment = PlaylistDetailFragment()
            fragment.arguments = Bundle().apply {
                putLong(ARG_PLAYLIST_ID, playlistId)
                putString(ARG_PLAYLIST_NAME, playlistName)
            }
            return fragment
        }
    }

    private lateinit var recyclerView: RecyclerView
    // 再生中アニメーション機能で追加。displaySongs()のたびにアダプターを作り直すため、
    // 今再生中の曲の状態はこのFragment側にも覚えておく
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private var playlistSongAdapter: PlaylistSongAdapter? = null
    private var nowPlayingSongId: Long? = null
    private var nowPlayingIsPlaying: Boolean = false
    private lateinit var database: AppDatabase
    private lateinit var textViewSelectAll: TextView
    private lateinit var buttonPlayShuffled: ImageButton
    private lateinit var buttonDeleteSelected: ImageButton
    private lateinit var imageViewCover: ImageView
    private lateinit var textViewName: TextView
    private var playlistId: Long = 0
    private var playlistName: String = ""

    private var currentPlaylist: Playlist? = null
    private var playlistSongs: List<Song> = emptyList()

    private var sortField: SortField? = null
    private var sortAscending = true

    private var selectionMode = false
    private var selectedIds: MutableSet<Long> = mutableSetOf()

    // カバー画像選択(v11で追加)
    private val pickCoverImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) saveCoverImage(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistId = arguments?.getLong(ARG_PLAYLIST_ID) ?: 0
        playlistName = arguments?.getString(ARG_PLAYLIST_NAME) ?: ""
        database = AppDatabase.getDatabase(requireContext())

        recyclerView = view.findViewById(R.id.recyclerViewPlaylistSongs)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        imageViewCover = view.findViewById(R.id.imageViewPlaylistCover)
        textViewName = view.findViewById(R.id.textViewPlaylistDetailName)
        textViewName.text = playlistName

        textViewSelectAll = view.findViewById(R.id.textViewSelectAll)
        buttonPlayShuffled = view.findViewById(R.id.buttonPlayShuffled)
        buttonDeleteSelected = view.findViewById(R.id.buttonDeleteSelected)

        buttonPlayShuffled.setOnClickListener { playShuffled() }
        buttonDeleteSelected.setOnClickListener { showDeleteSelectedConfirmDialog() }
        textViewSelectAll.setOnClickListener { toggleSelectAll() }
        view.findViewById<ImageButton>(R.id.buttonMenu).setOnClickListener { showMainMenu(it) }

        loadCoverHeader()
        loadPlaylistSongs()

        // 再生中アニメーション機能で追加。「今再生中の曲ID」と「再生中か」だけを監視し、
        // 変わったときにアダプターへ反映する(再生位置が0.5秒ごとに更新されても反応しない)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.uiState
                    .map { state -> state.currentMediaId?.toLongOrNull() to state.isPlaying }
                    .distinctUntilChanged()
                    .collect { (songId, isPlaying) ->
                        nowPlayingSongId = songId
                        nowPlayingIsPlaying = isPlaying
                        playlistSongAdapter?.setNowPlaying(songId, isPlaying)
                    }
            }
        }
    }

    private fun getController() = (activity as? MainActivity)?.mediaController

    // カバー画像(手動設定 or 先頭曲のアルバムアート)を読み込んで表示する
    private fun loadCoverHeader() {
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists()
            val playlist = playlists.firstOrNull { it.id == playlistId } ?: return@launch
            currentPlaylist = playlist

            val allSongs = SongRepository(database).getAllSongs()
            val coverUri = PlaylistCoverHelper.resolveCoverUri(database, playlist, allSongs)

            imageViewCover.load(coverUri) {
                crossfade(true)
                placeholder(R.drawable.ic_album_placeholder)
                error(R.drawable.ic_album_placeholder)
            }
        }
    }

    // 選んだ画像を内部ストレージにコピーして保存する(LibraryScannerのアルバムアート保存と同じ考え方)
    private fun saveCoverImage(uri: Uri) {
        lifecycleScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val dir = File(requireContext().filesDir, "playlist_covers").apply { mkdirs() }
                    val file = File(dir, "playlist_${playlistId}_${System.currentTimeMillis()}.jpg")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    file.absolutePath
                } catch (e: Exception) {
                    null
                }
            }

            if (savedPath != null) {
                database.playlistDao().updatePlaylistCover(playlistId, savedPath)
                Toast.makeText(requireContext(), "カバーを設定しました", Toast.LENGTH_SHORT).show()
                loadCoverHeader()
            } else {
                Toast.makeText(requireContext(), "カバーの設定に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearCoverImage() {
        lifecycleScope.launch {
            database.playlistDao().updatePlaylistCover(playlistId, null)
            Toast.makeText(requireContext(), "カバーを解除しました", Toast.LENGTH_SHORT).show()
            loadCoverHeader()
        }
    }

    private fun loadPlaylistSongs() {
        lifecycleScope.launch {
            val songIds = database.playlistDao().getSongIdsInPlaylist(playlistId)
            val allSongs = SongRepository(database).getAllSongs()
            var songs = allSongs.filter { it.id in songIds }

            sortField?.let { field ->
                val comparator: Comparator<Song> = when (field) {
                    SortField.TITLE -> compareBy { it.title }
                    SortField.ARTIST -> compareBy { it.artist }
                    SortField.ALBUM -> compareBy { it.album }
                }
                songs = if (sortAscending) songs.sortedWith(comparator) else songs.sortedWith(comparator.reversed())
            }

            playlistSongs = songs
            displaySongs()
        }
    }

    private fun displaySongs() {
        val adapter = PlaylistSongAdapter(
            songList = playlistSongs,
            onSongClick = { song -> playSong(playlistSongs, song) },
            onRemoveClick = { song -> removeSong(song) },
            isSelectionMode = { selectionMode },
            isSelected = { song -> song.id in selectedIds },
            onSelectionToggle = { song -> toggleSelection(song) },
            onSongLongClick = { song -> enterSelectionModeWith(song) }
        )
        // 作り直したアダプターにも、今再生中の曲の状態を引き継ぐ
        adapter.setNowPlaying(nowPlayingSongId, nowPlayingIsPlaying)
        playlistSongAdapter = adapter
        recyclerView.adapter = adapter
    }

    // 検索バー右の3本線メニュー
    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "並び替え")
        popup.menu.add(0, 2, 1, if (selectionMode) "複数選択を終了" else "複数選択")
        popup.menu.add(0, 3, 2, "カバーを設定")
        if (currentPlaylist?.coverImagePath != null) {
            popup.menu.add(0, 4, 3, "カバーを解除")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showSortMenu(anchor)
                2 -> toggleSelectionMode()
                3 -> pickCoverImageLauncher.launch("image/*")
                4 -> clearCoverImage()
            }
            true
        }
        popup.show()
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "曲名(昇順)")
        popup.menu.add(0, 2, 1, "曲名(降順)")
        popup.menu.add(0, 3, 2, "アーティスト(昇順)")
        popup.menu.add(0, 4, 3, "アーティスト(降順)")
        popup.menu.add(0, 5, 4, "アルバム(昇順)")
        popup.menu.add(0, 6, 5, "アルバム(降順)")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { sortField = SortField.TITLE; sortAscending = true }
                2 -> { sortField = SortField.TITLE; sortAscending = false }
                3 -> { sortField = SortField.ARTIST; sortAscending = true }
                4 -> { sortField = SortField.ARTIST; sortAscending = false }
                5 -> { sortField = SortField.ALBUM; sortAscending = true }
                6 -> { sortField = SortField.ALBUM; sortAscending = false }
            }
            loadPlaylistSongs()
            true
        }
        popup.show()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        selectedIds.clear()
        updateSelectionUi()
        displaySongs()
    }
    private fun enterSelectionModeWith(song: Song) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
            selectedIds.add(song.id)
            updateSelectionUi()
            displaySongs()
        }
    }
    private fun toggleSelection(song: Song) {
        if (song.id in selectedIds) {
            selectedIds.remove(song.id)
        } else {
            selectedIds.add(song.id)
        }
        displaySongs()
    }

    private fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == playlistSongs.size) {
            mutableSetOf()
        } else {
            playlistSongs.map { it.id }.toMutableSet()
        }
        displaySongs()
    }

    private fun updateSelectionUi() {
        if (selectionMode) {
            buttonPlayShuffled.visibility = View.GONE
            textViewSelectAll.visibility = View.VISIBLE
            buttonDeleteSelected.visibility = View.VISIBLE
        } else {
            buttonPlayShuffled.visibility = View.VISIBLE
            textViewSelectAll.visibility = View.INVISIBLE
            buttonDeleteSelected.visibility = View.GONE
        }
    }
    private fun showDeleteSelectedConfirmDialog() {
        val songs = playlistSongs.filter { it.id in selectedIds }
        if (songs.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle("プレイリストから削除")
            .setMessage("選択した${songs.size}曲をこのプレイリストから削除します(端末のファイル・ライブラリ本体は残ります)。")
            .setPositiveButton("削除") { _, _ -> deleteSelectedSongs(songs) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteSelectedSongs(songs: List<Song>) {
        lifecycleScope.launch {
            for (song in songs) {
                database.playlistDao().removeSongFromPlaylist(playlistId, song.id)
            }
            Toast.makeText(requireContext(), "${songs.size}曲をプレイリストから削除しました", Toast.LENGTH_SHORT).show()
            selectionMode = false
            selectedIds.clear()
            updateSelectionUi()
            loadPlaylistSongs()
            loadCoverHeader()
        }
    }

    private fun playSong(songs: List<Song>, song: Song) {
        val controller = getController() ?: return
        val startIndex = songs.indexOf(song)
        if (startIndex == -1) return

        val queueSongs = songs.subList(startIndex, songs.size)
        val mediaItems = queueSongs.map { toMediaItem(it) }

        controller.shuffleModeEnabled = false
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.prepare()
        controller.play()

        (activity as? MainActivity)?.openFullScreenPlayer()
    }

    private fun playShuffled() {
        if (playlistSongs.isEmpty()) return
        val controller = getController() ?: return

        val shuffledSongs = playlistSongs.shuffled()
        val mediaItems = shuffledSongs.map { toMediaItem(it) }
        controller.shuffleModeEnabled = false
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.prepare()
        controller.play()
    }

    private fun toMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.albumArtUri)
                    .build()
            )
            .build()
    }

    private fun removeSong(song: Song) {
        lifecycleScope.launch {
            database.playlistDao().removeSongFromPlaylist(playlistId, song.id)
            loadPlaylistSongs()
            loadCoverHeader()
        }
    }
}