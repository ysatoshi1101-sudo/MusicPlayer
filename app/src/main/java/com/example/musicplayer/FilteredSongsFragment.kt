package com.example.musicplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import coil.load
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FilteredSongsFragment : Fragment() {
    // アルバムアート手動設定機能で追加
    private var pendingAlbumArtUri: Uri? = null
    private var pendingAlbumArtImageView: ImageView? = null

    private val pickAlbumArtLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingAlbumArtUri = uri
            pendingAlbumArtImageView?.load(uri) { crossfade(true) }
        }
    }
    private enum class SortField { TITLE, ARTIST, ALBUM }

    companion object {
        private const val ARG_FILTER_TYPE = "filter_type"
        private const val ARG_FILTER_VALUE = "filter_value"

        fun newInstance(filterType: String, filterValue: String): FilteredSongsFragment {
            val fragment = FilteredSongsFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_FILTER_TYPE, filterType)
                putString(ARG_FILTER_VALUE, filterValue)
            }
            return fragment
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private val playerViewModel: PlayerViewModel by activityViewModels()
    private lateinit var database: AppDatabase
    private lateinit var textViewSongCount: TextView
    private lateinit var textViewSelectAll: TextView
    private lateinit var buttonPlayShuffled: MaterialButton
    private lateinit var buttonAddAllFavorite: ImageButton
    private lateinit var buttonAddAllPlaylist: ImageButton
    private lateinit var buttonDeleteSelected: ImageButton

    private var filteredSongs: List<Song> = emptyList()
    private var favoriteIds: Set<Long> = emptySet()

    private var sortField: SortField? = null
    private var sortAscending = true

    private var selectionMode = false
    private var selectedIds: MutableSet<Long> = mutableSetOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_songs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())

        recyclerView = view.findViewById(R.id.recyclerViewSongs)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        songAdapter = SongAdapter(
            onSongClick = { song -> playSong(song) },
            onMenuClick = { song, anchor -> showSongMenu(song, anchor) },
            isSelectionMode = { selectionMode },
            isSelected = { song -> song.id in selectedIds },
            onSelectionToggle = { song -> toggleSelection(song) },
            onSongLongClick = { song -> enterSelectionModeWith(song) }
        )
        recyclerView.adapter = songAdapter
        // 再生中アニメーション機能で追加。「今再生中の曲ID」と「再生中か」だけを監視し、
        // 変わったときにアダプターへ反映する(再生位置が0.5秒ごとに更新されても反応しない)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.uiState
                    .map { state -> state.currentMediaId?.toLongOrNull() to state.isPlaying }
                    .distinctUntilChanged()
                    .collect { (songId, isPlaying) ->
                        songAdapter.setNowPlaying(songId, isPlaying)
                    }
            }
        }

        textViewSongCount = view.findViewById(R.id.textViewSongCount)
        textViewSelectAll = view.findViewById(R.id.textViewSelectAll)
        buttonPlayShuffled = view.findViewById(R.id.buttonPlayShuffled)
        buttonAddAllFavorite = view.findViewById(R.id.buttonAddAllFavorite)
        buttonAddAllPlaylist = view.findViewById(R.id.buttonAddAllPlaylist)
        buttonDeleteSelected = view.findViewById(R.id.buttonDeleteSelected)

        buttonPlayShuffled.setOnClickListener { playShuffled() }
        buttonAddAllFavorite.setOnClickListener { addSelectedToFavorites() }
        buttonAddAllPlaylist.setOnClickListener { showAddSelectedToPlaylistDialog() }
        buttonDeleteSelected.setOnClickListener { showDeleteSelectedConfirmDialog() }
        textViewSelectAll.setOnClickListener { toggleSelectAll() }
        view.findViewById<ImageButton>(R.id.buttonMenu).setOnClickListener { showMainMenu(it) }

        view.findViewById<View>(R.id.editTextSearch)?.visibility = View.GONE
        view.findViewById<View>(R.id.buttonSettings)?.visibility = View.GONE

        updateSelectionUi()
        loadFilteredSongs()
    }

    private fun getController() = (activity as? MainActivity)?.mediaController

    private fun loadFilteredSongs() {
        val filterType = arguments?.getString(ARG_FILTER_TYPE) ?: return
        val filterValue = arguments?.getString(ARG_FILTER_VALUE) ?: return

        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()
            var songs = when (filterType) {
                "artist" -> allSongs.filter { it.artist == filterValue }
                "album" -> allSongs.filter { it.album == filterValue }
                "folder" -> allSongs.filter { it.folderPath == filterValue }
                "recent" -> allSongs.sortedByDescending { it.addedAt }
                else -> allSongs
            }

            sortField?.let { field ->
                val comparator: Comparator<Song> = when (field) {
                    SortField.TITLE -> compareBy { it.title }
                    SortField.ARTIST -> compareBy { it.artist }
                    SortField.ALBUM -> compareBy { it.album }
                }
                songs = if (sortAscending) songs.sortedWith(comparator) else songs.sortedWith(comparator.reversed())
            }

            filteredSongs = songs
            favoriteIds = database.favoriteDao().getAllFavoriteIds().toSet()
            displaySongs()
        }
    }

    private fun displaySongs() {
        updateSelectionUi()
        songAdapter.submitList(filteredSongs)
    }

    // 検索バー右の3本線メニュー
    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "並び替え")
        popup.menu.add(0, 2, 1, if (selectionMode) "複数選択を終了" else "複数選択")
        popup.menu.add(0, 3, 2, "表示中の曲をすべてお気に入りに追加")
        popup.menu.add(0, 4, 3, "表示中の曲をすべてプレイリストに追加")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showSortMenu(anchor)
                2 -> toggleSelectionMode()
                3 -> addAllDisplayedToFavorites()
                4 -> showAddAllDisplayedToPlaylistDialog()
            }
            true
        }
        popup.show()
    }

    // 表示中の曲を全部、選択操作なしで一括お気に入り追加する
    private fun addAllDisplayedToFavorites() {
        if (filteredSongs.isEmpty()) return
        lifecycleScope.launch {
            for (song in filteredSongs) {
                if (song.id !in favoriteIds) {
                    database.favoriteDao().addFavorite(FavoriteSong(song.id))
                }
            }
            favoriteIds = database.favoriteDao().getAllFavoriteIds().toSet()
            Toast.makeText(requireContext(), "${filteredSongs.size}曲をお気に入りに追加しました", Toast.LENGTH_SHORT).show()
            displaySongs()
        }
    }

    // 表示中の曲を全部、選択操作なしで一括プレイリスト追加する
    private fun showAddAllDisplayedToPlaylistDialog() {
        if (filteredSongs.isEmpty()) return
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "先にプレイリストを作成してください", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("プレイリストに追加")
                .setItems(playlistNames) { _, which ->
                    val selectedPlaylist = playlists[which]
                    lifecycleScope.launch {
                        for (song in filteredSongs) {
                            database.playlistDao().addSongToPlaylist(
                                PlaylistSongCrossRef(playlistId = selectedPlaylist.id, songId = song.id)
                            )
                        }
                        Toast.makeText(
                            requireContext(),
                            "${filteredSongs.size}曲を ${selectedPlaylist.name} に追加しました",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
        }
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
            loadFilteredSongs()
            true
        }
        popup.show()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        selectedIds.clear()
        updateSelectionUi()
        songAdapter.notifyDataSetChanged()
    }

    private fun enterSelectionModeWith(song: Song) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
            selectedIds.add(song.id)
            updateSelectionUi()
            songAdapter.notifyDataSetChanged()
        }
    }

    private fun toggleSelection(song: Song) {
        if (song.id in selectedIds) {
            selectedIds.remove(song.id)
        } else {
            selectedIds.add(song.id)
        }
        updateSelectionUi()
        songAdapter.notifyDataSetChanged()
    }

    private fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == filteredSongs.size) {
            mutableSetOf()
        } else {
            filteredSongs.map { it.id }.toMutableSet()
        }
        updateSelectionUi()
        songAdapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        if (selectionMode) {
            buttonPlayShuffled.visibility = View.GONE
            textViewSelectAll.visibility = View.VISIBLE
            buttonAddAllFavorite.visibility = View.VISIBLE
            buttonAddAllPlaylist.visibility = View.VISIBLE
            buttonDeleteSelected.visibility = View.VISIBLE
            textViewSongCount.text = "${selectedIds.size}件選択中"
        } else {
            buttonPlayShuffled.visibility = View.VISIBLE
            textViewSelectAll.visibility = View.GONE
            buttonAddAllFavorite.visibility = View.GONE
            buttonAddAllPlaylist.visibility = View.GONE
            buttonDeleteSelected.visibility = View.GONE
            textViewSongCount.text = "${filteredSongs.size}曲"
        }
    }

    private fun getSelectedSongs(): List<Song> = filteredSongs.filter { it.id in selectedIds }

    private fun addSelectedToFavorites() {
        val songs = getSelectedSongs()
        if (songs.isEmpty()) return

        lifecycleScope.launch {
            for (song in songs) {
                if (song.id !in favoriteIds) {
                    database.favoriteDao().addFavorite(FavoriteSong(song.id))
                }
            }
            favoriteIds = database.favoriteDao().getAllFavoriteIds().toSet()
            Toast.makeText(requireContext(), "${songs.size}曲をお気に入りに追加しました", Toast.LENGTH_SHORT).show()
            toggleSelectionMode()
        }
    }

    private fun showAddSelectedToPlaylistDialog() {
        val songs = getSelectedSongs()
        if (songs.isEmpty()) return

        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "先にプレイリストを作成してください", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("プレイリストに追加")
                .setItems(playlistNames) { _, which ->
                    val selectedPlaylist = playlists[which]
                    lifecycleScope.launch {
                        for (song in songs) {
                            database.playlistDao().addSongToPlaylist(
                                PlaylistSongCrossRef(playlistId = selectedPlaylist.id, songId = song.id)
                            )
                        }
                        Toast.makeText(
                            requireContext(),
                            "${songs.size}曲を ${selectedPlaylist.name} に追加しました",
                            Toast.LENGTH_SHORT
                        ).show()
                        toggleSelectionMode()
                    }
                }
                .show()
        }
    }

    private fun showDeleteSelectedConfirmDialog() {
        val songs = getSelectedSongs()
        if (songs.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle("ライブラリから削除")
            .setMessage("選択した${songs.size}曲をアプリのライブラリから削除します(端末のファイル自体は残ります)。")
            .setPositiveButton("削除") { _, _ -> deleteSelectedSongs(songs) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteSelectedSongs(songs: List<Song>) {
        lifecycleScope.launch {
            for (song in songs) {
                database.playlistDao().removeSongFromAllPlaylists(song.id)
                database.favoriteDao().removeFavorite(song.id)
                database.songDao().deleteSongById(song.id)
            }
            Toast.makeText(requireContext(), "${songs.size}曲をライブラリから削除しました", Toast.LENGTH_SHORT).show()
            toggleSelectionMode()
            loadFilteredSongs()
        }
    }

    private fun showSongMenu(song: Song, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val favLabel = if (song.id in favoriteIds) "お気に入り解除" else "お気に入りに追加"
        popup.menu.add(0, 2, 0, favLabel)
        popup.menu.add(0, 3, 1, "プレイリストへ追加")
        popup.menu.add(0, 4, 2, "共有")
        popup.menu.add(0, 5, 3, "曲情報")
        popup.menu.add(0, 6, 4, "アーティストに移動")
        popup.menu.add(0, 7, 5, "アルバムに移動")
        popup.menu.add(0, 8, 6, "編集")
        popup.menu.add(0, 9, 7, "削除")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> toggleFavorite(song)
                3 -> showAddToPlaylistDialog(song)
                4 -> shareSong(song)
                5 -> showSongInfoDialog(song)
                6 -> navigateToArtist(song.artist)
                7 -> navigateToAlbum(song.album)
                8 -> showEditDialog(song)
                9 -> showDeleteConfirmDialog(song)
            }
            true
        }
        popup.show()
    }

    private fun navigateToArtist(artist: String) {
        val detailFragment = newInstance("artist", artist)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToAlbum(album: String) {
        val detailFragment = newInstance("album", album)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showEditDialog(song: Song) {
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val artSize = (96 * resources.displayMetrics.density).toInt()

        pendingAlbumArtUri = null

        val albumArtPreview = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = padding / 2
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(song.albumArtUri) {
                crossfade(true)
                placeholder(R.drawable.ic_album_placeholder)
                error(R.drawable.ic_album_placeholder)
            }
        }
        pendingAlbumArtImageView = albumArtPreview

        val changeArtButton = Button(context).apply {
            text = "画像を変更"
            setOnClickListener { pickAlbumArtLauncher.launch("image/*") }
        }

        val titleInput = EditText(context).apply { setText(song.title); hint = "曲名" }
        val artistInput = EditText(context).apply { setText(song.artist); hint = "アーティスト" }
        val albumInput = EditText(context).apply { setText(song.album); hint = "アルバム" }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(albumArtPreview)
            addView(changeArtButton)
            addView(titleInput)
            addView(artistInput)
            addView(albumInput)
        }

        AlertDialog.Builder(context)
            .setTitle("曲情報を編集(アプリ内表示のみ)")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = titleInput.text.toString().ifBlank { song.title }
                val newArtist = artistInput.text.toString().ifBlank { song.artist }
                val newAlbum = albumInput.text.toString().ifBlank { song.album }
                saveEditedSong(song.id, newTitle, newArtist, newAlbum, pendingAlbumArtUri)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun saveEditedSong(songId: Long, title: String, artist: String, album: String, albumArtUri: Uri? = null) {
        lifecycleScope.launch {
            database.songDao().updateSongMetadata(songId, title, artist, album)

            if (albumArtUri != null) {
                val savedPath = AlbumArtHelper.saveManualAlbumArt(requireContext(), songId, albumArtUri)
                if (savedPath != null) {
                    database.songDao().updateAlbumArtPath(songId, savedPath)
                }
            }

            Toast.makeText(requireContext(), "曲情報を更新しました", Toast.LENGTH_SHORT).show()
            loadFilteredSongs()
        }
    }

    private fun saveEditedSong(song: Song, title: String, artist: String, album: String) {
        lifecycleScope.launch {
            val message = SongMetadataEditor.save(requireContext(), database, song, title, artist, album)
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            loadFilteredSongs()
        }
    }

    private fun shareSong(song: Song) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "曲を共有"))
    }

    private fun showSongInfoDialog(song: Song) {
        val message = """
            曲名: ${song.title}
            アーティスト: ${song.artist}
            アルバム: ${song.album}
            再生時間: ${formatDuration(song.duration)}
            フォルダ: ${song.folderPath.ifBlank { "(手動追加)" }}
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("曲情報")
            .setMessage(message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun toggleFavorite(song: Song) {
        lifecycleScope.launch {
            if (song.id in favoriteIds) {
                database.favoriteDao().removeFavorite(song.id)
            } else {
                database.favoriteDao().addFavorite(FavoriteSong(song.id))
            }
            favoriteIds = database.favoriteDao().getAllFavoriteIds().toSet()
            displaySongs()
        }
    }

    private fun showAddToPlaylistDialog(song: Song) {
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists()

            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "先にプレイリストを作成してください", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("プレイリストに追加")
                .setItems(playlistNames) { _, which ->
                    val selectedPlaylist = playlists[which]
                    addSongToPlaylist(selectedPlaylist, song)
                }
                .show()
        }
    }

    private fun addSongToPlaylist(playlist: Playlist, song: Song) {
        lifecycleScope.launch {
            database.playlistDao().addSongToPlaylist(
                PlaylistSongCrossRef(playlistId = playlist.id, songId = song.id)
            )
            Toast.makeText(
                requireContext(),
                "${song.title} を ${playlist.name} に追加しました",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showDeleteConfirmDialog(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle("ライブラリから削除")
            .setMessage("「${song.title}」をアプリのライブラリから削除します(端末のファイル自体は残ります)。")
            .setPositiveButton("削除") { _, _ -> deleteSong(song) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteSong(song: Song) {
        lifecycleScope.launch {
            database.playlistDao().removeSongFromAllPlaylists(song.id)
            database.favoriteDao().removeFavorite(song.id)
            database.songDao().deleteSongById(song.id)

            Toast.makeText(requireContext(), "${song.title} をライブラリから削除しました", Toast.LENGTH_SHORT).show()
            loadFilteredSongs()
        }
    }

    private fun playSong(song: Song) {
        val controller = getController() ?: return
        val startIndex = filteredSongs.indexOf(song)
        if (startIndex == -1) return

        val queueSongs = filteredSongs.subList(startIndex, filteredSongs.size)
        val mediaItems = queueSongs.map { toMediaItem(it) }

        controller.shuffleModeEnabled = false
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.prepare()
        controller.play()

        (activity as? MainActivity)?.openFullScreenPlayer()
    }

    private fun playShuffled() {
        if (filteredSongs.isEmpty()) return
        val controller = getController() ?: return

        val shuffledSongs = filteredSongs.shuffled()
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
}