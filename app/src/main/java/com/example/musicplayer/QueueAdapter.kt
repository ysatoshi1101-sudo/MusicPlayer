package com.example.musicplayer

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

interface OnStartQueueDragListener {
    fun requestQueueDrag(viewHolder: RecyclerView.ViewHolder)
}

class QueueAdapter(
    private val queueList: MutableList<QueueItem>,
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val dragListener: OnStartQueueDragListener
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: TextView = view.findViewById(R.id.textViewQueueDragHandle)
        val titleText: TextView = view.findViewById(R.id.textViewQueueTitle)
        val artistText: TextView = view.findViewById(R.id.textViewQueueArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun getItemCount(): Int = queueList.size

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = queueList[position]
        holder.titleText.text = item.title
        holder.artistText.text = item.artist

        val context = holder.itemView.context
        val isCurrent = position == currentIndex
        holder.itemView.setBackgroundColor(
            if (isCurrent) ContextCompat.getColor(context, R.color.color_current_item_background)
            else android.graphics.Color.TRANSPARENT
        )
        holder.titleText.setTextColor(
            if (isCurrent) {
                // メインカラー機能に合わせ、固定色ではなくテーマの colorPrimary から解決する
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorPrimary,
                    ContextCompat.getColor(context, R.color.color_accent)
                )
            } else {
                // 再生キュー背景対応(フェーズ3)で変更。固定色ではなく、背景の有無で
                // 自動的に白系へ切り替わる?attr/bgTextColorPrimaryから解決する
                MaterialColors.getColor(
                    context,
                    R.attr.bgTextColorPrimary,
                    ContextCompat.getColor(context, R.color.color_text_primary)
                )
            }
        )

        holder.itemView.setOnClickListener {
            onItemClick(holder.bindingAdapterPosition)
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragListener.requestQueueDrag(holder)
            }
            false
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val item = queueList.removeAt(fromPosition)
        queueList.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun removeItem(position: Int) {
        queueList.removeAt(position)
        notifyItemRemoved(position)
    }

    // ドラッグ&ドロップ対応で変更。渡されたリストが現在保持しているリストと内容が
    // 一致する場合はnotifyDataSetChangedを呼ばない(再生中インデックスのみ部分更新する)。
    // ドラッグ操作で自分自身が行った並び替えがMedia3側から折り返ってきても、内容が
    // 一致していれば再描画をスキップするため、ドラッグ中にViewHolderの状態が
    // 巻き戻って操作が中断されなくなる
    fun updateData(newList: List<QueueItem>, newCurrentIndex: Int) {
        if (queueList == newList) {
            if (currentIndex != newCurrentIndex) {
                val oldIndex = currentIndex
                currentIndex = newCurrentIndex
                if (oldIndex in queueList.indices) notifyItemChanged(oldIndex)
                if (newCurrentIndex in queueList.indices) notifyItemChanged(newCurrentIndex)
            }
            return
        }
        queueList.clear()
        queueList.addAll(newList)
        currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }
}