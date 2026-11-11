package com.example.musicplayer

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment(), OnStartDragListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var adapter: PlaylistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())

        recyclerView = view.findViewById(R.id.recyclerViewPlaylists)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 画面タイトル右端の設定ボタン
        view.findViewById<ImageButton>(R.id.buttonScreenSettings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddPlaylist)
        fab.setOnClickListener {
            showAddPlaylistDialog()
        }

        loadPlaylists()
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists().toMutableList()

            // プレイリストごとの曲数を集計する(カード表示用)
            val songCounts = playlists.associate { playlist ->
                playlist.id to database.playlistDao().getSongIdsInPlaylist(playlist.id).size
            }

            // プレイリストごとの実効カバー画像(手動設定 or 先頭曲のアルバムアート)を解決する(v11で追加)
            val allSongs = SongRepository(database).getAllSongs()
            val coverUris = playlists.associate { playlist ->
                playlist.id to PlaylistCoverHelper.resolveCoverUri(database, playlist, allSongs)
            }

            adapter = PlaylistAdapter(
                playlistList = playlists,
                songCounts = songCounts,
                coverUris = coverUris,
                onPlaylistClick = { selectedPlaylist ->
                    val detailFragment = PlaylistDetailFragment.newInstance(
                        selectedPlaylist.id,
                        selectedPlaylist.name
                    )
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, detailFragment)
                        .addToBackStack(null)
                        .commit()
                },
                onRenameClick = { playlist -> showRenamePlaylistDialog(playlist) },
                onDeleteClick = { playlist -> showDeletePlaylistDialog(playlist) },
                dragListener = this@PlaylistsFragment
            )
            recyclerView.adapter = adapter

            setupDragAndDrop()
        }
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 左右スワイプは使わないので何もしない
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                saveNewOrder()
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(viewHolder)
    }

    private fun saveNewOrder() {
        lifecycleScope.launch {
            val updatedList = adapter.getCurrentList().mapIndexed { index, playlist ->
                playlist.copy(sortOrder = index)
            }
            database.playlistDao().updatePlaylists(updatedList)
        }
    }

    private fun showAddPlaylistDialog() {
        val editText = EditText(requireContext())

        AlertDialog.Builder(requireContext())
            .setTitle("新しいプレイリスト")
            .setView(editText)
            .setPositiveButton("作成") { _, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) {
                    createPlaylist(name)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        lifecycleScope.launch {
            val newSortOrder = adapter.getCurrentList().size
            database.playlistDao().insertPlaylist(Playlist(name = name, sortOrder = newSortOrder))
            loadPlaylists()
        }
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val editText = EditText(requireContext())
        editText.setText(playlist.name)

        AlertDialog.Builder(requireContext())
            .setTitle("プレイリスト名を変更")
            .setView(editText)
            .setPositiveButton("変更") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    renamePlaylist(playlist, newName)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun renamePlaylist(playlist: Playlist, newName: String) {
        lifecycleScope.launch {
            database.playlistDao().updatePlaylist(playlist.copy(name = newName))
            loadPlaylists()
        }
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle("プレイリストを削除")
            .setMessage("「${playlist.name}」を削除しますか?この操作は取り消せません。")
            .setPositiveButton("削除") { _, _ ->
                deletePlaylist(playlist)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        lifecycleScope.launch {
            database.playlistDao().removeAllSongsFromPlaylist(playlist.id)
            database.playlistDao().deletePlaylist(playlist)
            loadPlaylists()
        }
    }
}