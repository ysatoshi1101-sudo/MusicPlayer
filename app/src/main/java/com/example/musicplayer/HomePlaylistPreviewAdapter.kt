package com.example.musicplayer

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

// ホーム画面「プレイリスト」の横スクロールカードで使う、Playlist+曲数+カバーURIのセット
data class HomePlaylistInfo(
    val playlist: Playlist,
    val songCount: Int,
    val coverUri: Uri?
)

class HomePlaylistPreviewAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : ListAdapter<HomePlaylistInfo, HomePlaylistPreviewAdapter.PlaylistViewHolder>(DiffCallback()) {

    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val coverImage: ImageView = view.findViewById(R.id.imageViewPlaylistCover)
        val nameText: TextView = view.findViewById(R.id.textViewPlaylistName)
        val countText: TextView = view.findViewById(R.id.textViewPlaylistCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_playlist_card, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val item = getItem(position)
        holder.nameText.text = item.playlist.name
        holder.countText.text = "${item.songCount}曲"
        holder.coverImage.load(item.coverUri) {
            crossfade(true)
            placeholder(R.drawable.ic_album_placeholder)
            error(R.drawable.ic_album_placeholder)
        }
        holder.itemView.setOnClickListener { onPlaylistClick(item.playlist) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HomePlaylistInfo>() {
        override fun areItemsTheSame(oldItem: HomePlaylistInfo, newItem: HomePlaylistInfo): Boolean =
            oldItem.playlist.id == newItem.playlist.id
        override fun areContentsTheSame(oldItem: HomePlaylistInfo, newItem: HomePlaylistInfo): Boolean =
            oldItem == newItem
    }
}