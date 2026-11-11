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

data class AlbumInfo(
    val name: String,
    val artist: String,
    val albumArtUri: Uri?,
    val songCount: Int
)

class AlbumAdapter(
    private val onAlbumClick: (AlbumInfo) -> Unit
) : ListAdapter<AlbumInfo, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.imageViewAlbumArt)
        val nameText: TextView = view.findViewById(R.id.textViewAlbumName)
        val artistText: TextView = view.findViewById(R.id.textViewAlbumArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = getItem(position)
        holder.nameText.text = album.name
        holder.artistText.text = album.artist
        holder.albumArt.load(album.albumArtUri) {
            crossfade(true)
            placeholder(R.drawable.ic_album_placeholder)
            error(R.drawable.ic_album_placeholder)
        }
        holder.itemView.setOnClickListener { onAlbumClick(album) }
    }

    private class AlbumDiffCallback : DiffUtil.ItemCallback<AlbumInfo>() {
        override fun areItemsTheSame(oldItem: AlbumInfo, newItem: AlbumInfo): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: AlbumInfo, newItem: AlbumInfo): Boolean {
            return oldItem == newItem
        }
    }
}