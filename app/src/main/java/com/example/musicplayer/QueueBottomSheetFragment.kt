package com.example.musicplayer

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class QueueBottomSheetFragment : BottomSheetDialogFragment(), OnStartQueueDragListener {

    private val playerViewModel: PlayerViewModel by activityViewModels()

    private lateinit var recyclerView: RecyclerView
    private var adapter: QueueAdapter? = null
    private lateinit var itemTouchHelper: ItemTouchHelper

    // 再生キュー背景対応(フェーズ3)で追加。ダイアログが実際に画面に表示された瞬間
    // (setOnShowListener)に、背景機能の有無を確認して見た目を反映する。
    // onStart()より確実に design_bottom_sheet が用意されているタイミングのため、こちらを使う
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            lifecycleScope.launch {
                val hasBackground = ThemePreferences(requireContext()).backgroundType.first() != "none"

                // scrim(ダイアログの外側にかかる薄暗いオーバーレイ)の濃さを調整する。
                // 背景が有効なときは弱めにして、裏のアプリ背景が透けて見えるようにする
                val dimAmount = if (hasBackground) 0.15f else 0.6f
                dialog.window?.setDimAmount(dimAmount)

                // シート自体の色を、背景の有無に応じたテーマ属性から取得して反映する。
                // MaterialShapeDrawable(角丸の形状)自体はそのまま維持し、色だけ差し替える
                val sheetColor = MaterialColors.getColor(requireContext(), R.attr.queueBackgroundColor, Color.WHITE)
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheetView ->
                    sheetView.backgroundTintList = ColorStateList.valueOf(sheetColor)
                }
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_queue_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerViewQueue)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<View>(R.id.buttonCloseQueue).setOnClickListener {
            dismiss()
        }

        setupDragAndSwipe()

        // ドラッグ&ドロップ対応で変更。uiState全体ではなく、queueとcurrentQueueIndexの
        // 組み合わせだけをmap+distinctUntilChangedで監視する。再生位置(0.5秒ごとに更新)
        // の変化では発火しなくなるため、ドラッグ中に notifyDataSetChanged が呼ばれて
        // ViewHolderの状態が巻き戻る問題を防ぐ
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.uiState
                    .map { state -> state.queue to state.currentQueueIndex }
                    .distinctUntilChanged()
                    .collect { (queue, currentIndex) ->
                        val currentAdapter = adapter
                        if (currentAdapter == null) {
                            adapter = QueueAdapter(
                                queueList = queue.toMutableList(),
                                currentIndex = currentIndex,
                                onItemClick = { index -> playerViewModel.playQueueItem(index) },
                                dragListener = this@QueueBottomSheetFragment
                            )
                            recyclerView.adapter = adapter
                        } else {
                            currentAdapter.updateData(queue, currentIndex)
                        }
                    }
            }
        }
    }

    // 上下ドラッグで並び替え、左右スワイプで削除できるようにする
    private fun setupDragAndSwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter?.moveItem(from, to)
                playerViewModel.moveQueueItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                adapter?.removeItem(position)
                playerViewModel.removeQueueItem(position)
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun requestQueueDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(viewHolder)
    }
}