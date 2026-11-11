package com.example.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

// ホーム画面「最近追加した曲」の横スクロールカード用アダプター
class HomeRecentSongAdapter(
    private val onSongClick: (Song) -> Unit
) : ListAdapter<Song, HomeRecentSongAdapter.SongViewHolder>(SongDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.imageViewAlbumArt)
        val titleText: TextView = view.findViewById(R.id.textViewTitle)
        val artistText: TextView = view.findViewById(R.id.textViewArtist)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_recent_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.titleText.text = song.title
        holder.artistText.text = song.artist
        holder.albumArt.load(song.albumArtUri) {
            crossfade(true)
            placeholder(R.drawable.ic_album_placeholder)
            error(R.drawable.ic_album_placeholder)
        }
        holder.itemView.setOnClickListener { onSongClick(song) }
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem == newItem
    }
}