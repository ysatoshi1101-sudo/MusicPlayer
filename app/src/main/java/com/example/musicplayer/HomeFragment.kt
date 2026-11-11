package com.example.musicplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private lateinit var database: AppDatabase

    private var recentSongsCache: List<Song> = emptyList()
    private var favoriteSongsPreview: List<Song> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())

        view.findViewById<ImageButton>(R.id.buttonHomeSearch).setOnClickListener {
            openSearch()
        }
        view.findViewById<ImageButton>(R.id.buttonHomeSettings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        view.findViewById<View>(R.id.cardShuffleAll).setOnClickListener { playAllShuffled() }

        val recentAdapter = HomeRecentSongAdapter { song -> playFromRecent(song) }
        view.findViewById<RecyclerView>(R.id.recyclerViewRecentSongs).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
        }

        val playlistAdapter = HomePlaylistPreviewAdapter { playlist -> openPlaylistDetail(playlist) }
        view.findViewById<RecyclerView>(R.id.recyclerViewPlaylists).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
        }

        view.findViewById<TextView>(R.id.textViewSeeAllRecent).setOnClickListener { openRecentlyAdded() }
        view.findViewById<TextView>(R.id.textViewSeeAllFavorites).setOnClickListener { openFavorites() }
        view.findViewById<TextView>(R.id.textViewSeeAllPlaylists).setOnClickListener { openPlaylists() }
        view.findViewById<TextView>(R.id.textViewSeeAllFolders).setOnClickListener { openFolders() }

        loadHomeData(view, recentAdapter, playlistAdapter)
    }

    private fun getController() = (activity as? MainActivity)?.mediaController

    private fun loadHomeData(
        rootView: View,
        recentAdapter: HomeRecentSongAdapter,
        playlistAdapter: HomePlaylistPreviewAdapter
    ) {
        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()

            // 最近追加した曲(横スクロール、最新15件)
            recentSongsCache = allSongs.sortedByDescending { it.addedAt }.take(15)
            rootView.findViewById<View>(R.id.sectionRecent).visibility =
                if (recentSongsCache.isEmpty()) View.GONE else View.VISIBLE
            recentAdapter.submitList(recentSongsCache)

            // お気に入り(カード内プレビュー、最大3件。全件は「すべて見る」でFavoritesFragmentへ)
            val favoriteIds = database.favoriteDao().getAllFavoriteIds().toSet()
            favoriteSongsPreview = allSongs.filter { it.id in favoriteIds }.take(3)
            rootView.findViewById<View>(R.id.sectionFavorites).visibility =
                if (favoriteSongsPreview.isEmpty()) View.GONE else View.VISIBLE
            bindFavoriteRows(rootView)

            // プレイリスト(横スクロール、全件。カバー画像は手動設定 or 先頭曲のアルバムアート)
            val playlists = database.playlistDao().getAllPlaylists()
            val songCounts = playlists.associate { it.id to database.playlistDao().getSongIdsInPlaylist(it.id).size }
            rootView.findViewById<View>(R.id.sectionPlaylists).visibility =
                if (playlists.isEmpty()) View.GONE else View.VISIBLE
            playlistAdapter.submitList(playlists.map { playlist ->
                HomePlaylistInfo(
                    playlist = playlist,
                    songCount = songCounts[playlist.id] ?: 0,
                    coverUri = PlaylistCoverHelper.resolveCoverUri(database, playlist, allSongs)
                )
            })

            // フォルダ(カード内プレビュー、トップ階層のフォルダ名ごとに曲数を集計、最大5件)
            val folderPreviews = allSongs
                .mapNotNull { song -> song.folderPath.substringBefore("/").takeIf { it.isNotBlank() } }
                .distinct()
                .sorted()
                .map { segment ->
                    val count = allSongs.count { it.folderPath == segment || it.folderPath.startsWith("$segment/") }
                    segment to count
                }
                .take(5)
            rootView.findViewById<View>(R.id.sectionFolders).visibility =
                if (folderPreviews.isEmpty()) View.GONE else View.VISIBLE
            bindFolderRows(rootView, folderPreviews)
        }
    }

    private fun bindFavoriteRows(rootView: View) {
        val container = rootView.findViewById<LinearLayout>(R.id.containerFavorites)
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        favoriteSongsPreview.forEach { song ->
            val row = inflater.inflate(R.layout.item_home_favorite_row, container, false)
            row.findViewById<TextView>(R.id.textViewTitle).text = song.title
            row.findViewById<TextView>(R.id.textViewArtist).text = song.artist
            row.findViewById<ImageView>(R.id.imageViewAlbumArt).load(song.albumArtUri) {
                crossfade(true)
                placeholder(R.drawable.ic_album_placeholder)
                error(R.drawable.ic_album_placeholder)
            }
            row.setOnClickListener { playFromFavorites(song) }
            container.addView(row)
        }
    }

    private fun bindFolderRows(rootView: View, folders: List<Pair<String, Int>>) {
        val container = rootView.findViewById<LinearLayout>(R.id.containerFolders)
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        folders.forEach { (name, count) ->
            val row = inflater.inflate(R.layout.item_home_folder_row, container, false)
            row.findViewById<TextView>(R.id.textViewFolderName).text = name
            row.findViewById<TextView>(R.id.textViewFolderCount).text = "${count}曲"
            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, FoldersFragment.newInstance(name))
                    .addToBackStack(null)
                    .commit()
            }
            container.addView(row)
        }
    }

    private fun playAllShuffled() {
        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()
            if (allSongs.isEmpty()) return@launch
            val controller = getController() ?: return@launch

            val shuffledSongs = allSongs.shuffled()
            val mediaItems = shuffledSongs.map { toMediaItem(it) }
            controller.shuffleModeEnabled = false
            controller.setMediaItems(mediaItems, 0, 0L)
            controller.prepare()
            controller.play()

            (activity as? MainActivity)?.openFullScreenPlayer()
        }
    }

    private fun playFromRecent(song: Song) {
        val controller = getController() ?: return
        val startIndex = recentSongsCache.indexOf(song)
        if (startIndex == -1) return

        val queueSongs = recentSongsCache.subList(startIndex, recentSongsCache.size)
        val mediaItems = queueSongs.map { toMediaItem(it) }
        controller.shuffleModeEnabled = false
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.prepare()
        controller.play()

        (activity as? MainActivity)?.openFullScreenPlayer()
    }

    private fun playFromFavorites(song: Song) {
        val controller = getController() ?: return
        val startIndex = favoriteSongsPreview.indexOf(song)
        if (startIndex == -1) return

        val queueSongs = favoriteSongsPreview.subList(startIndex, favoriteSongsPreview.size)
        val mediaItems = queueSongs.map { toMediaItem(it) }
        controller.shuffleModeEnabled = false
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.prepare()
        controller.play()

        (activity as? MainActivity)?.openFullScreenPlayer()
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        val detailFragment = PlaylistDetailFragment.newInstance(playlist.id, playlist.name)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openSearch() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SongsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openRecentlyAdded() {
        val detailFragment = FilteredSongsFragment.newInstance("recent", "")
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openFavorites() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, FavoritesFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openPlaylists() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, PlaylistsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openFolders() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, FoldersFragment.newInstance(""))
            .addToBackStack(null)
            .commit()
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