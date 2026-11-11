package com.example.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class ArtistInfo(
    val name: String,
    val songCount: Int
)

class ArtistAdapter(
    private val onArtistClick: (ArtistInfo) -> Unit
) : ListAdapter<ArtistInfo, ArtistAdapter.ArtistViewHolder>(ArtistDiffCallback()) {

    class ArtistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val initialText: TextView = view.findViewById(R.id.textViewArtistInitial)
        val nameText: TextView = view.findViewById(R.id.textViewArtistName)
        val countText: TextView = view.findViewById(R.id.textViewArtistSongCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist, parent, false)
        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = getItem(position)
        holder.nameText.text = artist.name
        holder.countText.text = "${artist.songCount}曲"
        holder.initialText.text = artist.name.firstOrNull()?.uppercase() ?: "?"
        holder.itemView.setOnClickListener { onArtistClick(artist) }
    }

    private class ArtistDiffCallback : DiffUtil.ItemCallback<ArtistInfo>() {
        override fun areItemsTheSame(oldItem: ArtistInfo, newItem: ArtistInfo): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: ArtistInfo, newItem: ArtistInfo): Boolean {
            return oldItem == newItem
        }
    }
}