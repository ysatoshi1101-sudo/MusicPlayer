package com.example.musicplayer

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

interface OnStartDragListener {
    fun requestDrag(viewHolder: RecyclerView.ViewHolder)
}

class PlaylistAdapter(
    private val playlistList: MutableList<Playlist>,
    private val songCounts: Map<Long, Int> = emptyMap(),
    private val coverUris: Map<Long, Uri?> = emptyMap(),
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onRenameClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit,
    private val dragListener: OnStartDragListener
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_DELETE = 2
    }

    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: TextView = view.findViewById(R.id.textViewDragHandle)
        val coverImage: ImageView = view.findViewById(R.id.imageViewPlaylistCover)
        val nameText: TextView = view.findViewById(R.id.textViewPlaylistName)
        val songCountText: TextView = view.findViewById(R.id.textViewPlaylistSongCount)
        val moreButton: TextView = view.findViewById(R.id.buttonMorePlaylist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun getItemCount(): Int = playlistList.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlistList[position]
        holder.nameText.text = playlist.name
        holder.songCountText.text = "${songCounts[playlist.id] ?: 0}曲"
        holder.coverImage.load(coverUris[playlist.id]) {
            crossfade(true)
            placeholder(R.drawable.ic_album_placeholder)
            error(R.drawable.ic_album_placeholder)
        }

        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }
        holder.moreButton.setOnClickListener { anchor ->
            showPlaylistMenu(anchor, playlist)
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragListener.requestDrag(holder)
            }
            false
        }
    }

    // 「⋮」を押したときに出す、名前変更・削除のメニュー
    private fun showPlaylistMenu(anchor: View, playlist: Playlist) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, MENU_RENAME, 0, "名前を変更")
        popup.menu.add(0, MENU_DELETE, 1, "削除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> onRenameClick(playlist)
                MENU_DELETE -> onDeleteClick(playlist)
            }
            true
        }
        popup.show()
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val item = playlistList.removeAt(fromPosition)
        playlistList.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getCurrentList(): List<Playlist> = playlistList
}
