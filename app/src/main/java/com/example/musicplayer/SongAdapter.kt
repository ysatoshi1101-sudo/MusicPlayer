package com.example.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onMenuClick: (Song, View) -> Unit,
    private val showFavoriteRemoveButton: Boolean = false,
    private val onFavoriteRemoveClick: ((Song) -> Unit)? = null,
    private val isSelectionMode: () -> Boolean = { false },
    private val isSelected: (Song) -> Boolean = { false },
    private val onSelectionToggle: ((Song) -> Unit)? = null,
    private val onSongLongClick: ((Song) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    private companion object {
        // 再生中表示だけを部分更新するための目印(アルバムアートなどを再読み込みしないため)
        const val PAYLOAD_NOW_PLAYING = "now_playing"
    }

    // 再生中アニメーション機能で追加。今再生中の曲IDと、再生中/一時停止中の状態
    private var nowPlayingSongId: Long? = null
    private var nowPlayingIsPlaying: Boolean = false

    init {
        setHasStableIds(true)
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkboxSelect)
        val albumArt: ImageView = view.findViewById(R.id.imageViewAlbumArt)
        val nowPlayingIndicator: NowPlayingIndicatorView = view.findViewById(R.id.nowPlayingIndicator)
        val titleText: TextView = view.findViewById(R.id.textViewTitle)
        val artistText: TextView = view.findViewById(R.id.textViewArtist)
        val albumText: TextView = view.findViewById(R.id.textViewAlbum)
        val moreButton: TextView = view.findViewById(R.id.buttonMore)
        val favoriteRemoveButton: TextView = view.findViewById(R.id.buttonFavoriteRemove)
    }

    // 再生中アニメーション機能で追加。Fragment側から「今再生中の曲」が変わるたびに呼ぶ。
    // 変化があった行(前の曲・新しい曲)だけを部分更新する
    fun setNowPlaying(songId: Long?, isPlaying: Boolean) {
        val oldSongId = nowPlayingSongId
        if (oldSongId == songId && nowPlayingIsPlaying == isPlaying) return

        nowPlayingSongId = songId
        nowPlayingIsPlaying = isPlaying

        if (oldSongId != songId) notifyNowPlayingChanged(oldSongId)
        notifyNowPlayingChanged(songId)
    }

    private fun notifyNowPlayingChanged(songId: Long?) {
        if (songId == null) return
        val index = currentList.indexOfFirst { it.id == songId }
        if (index != -1) notifyItemChanged(index, PAYLOAD_NOW_PLAYING)
    }

    private fun bindNowPlaying(holder: SongViewHolder, song: Song) {
        val isCurrent = song.id == nowPlayingSongId
        holder.nowPlayingIndicator.visibility = if (isCurrent) View.VISIBLE else View.GONE
        holder.nowPlayingIndicator.setPlaying(isCurrent && nowPlayingIsPlaying)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    // 再生中表示だけの更新(payloadあり)の場合は、そこだけ更新して終わる
    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_NOW_PLAYING)) {
            bindNowPlaying(holder, getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.titleText.text = song.title
        holder.artistText.text = song.artist
        holder.albumText.text = song.album

        holder.albumArt.load(song.albumArtUri) {
            crossfade(true)
            placeholder(R.drawable.ic_album_placeholder)
            error(R.drawable.ic_album_placeholder)
        }

        bindNowPlaying(holder, song)

        val selectionMode = isSelectionMode()

        if (selectionMode) {
            holder.checkbox.visibility = View.VISIBLE
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = isSelected(song)
            holder.checkbox.setOnCheckedChangeListener { _, _ -> onSelectionToggle?.invoke(song) }
            holder.moreButton.visibility = View.GONE
            holder.favoriteRemoveButton.visibility = View.GONE
            holder.itemView.setOnClickListener { onSelectionToggle?.invoke(song) }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.checkbox.visibility = View.GONE
            holder.moreButton.visibility = View.VISIBLE
            holder.moreButton.setOnClickListener { onMenuClick(song, holder.moreButton) }
            holder.favoriteRemoveButton.visibility = if (showFavoriteRemoveButton) View.VISIBLE else View.GONE
            if (showFavoriteRemoveButton) {
                holder.favoriteRemoveButton.setOnClickListener { onFavoriteRemoveClick?.invoke(song) }
            }
            holder.itemView.setOnClickListener { onSongClick(song) }
            holder.itemView.setOnLongClickListener {
                onSongLongClick?.invoke(song)
                true
            }
        }
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}