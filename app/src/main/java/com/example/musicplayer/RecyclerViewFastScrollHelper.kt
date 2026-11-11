package com.example.musicplayer

import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

// RecyclerViewの実際のスクロール位置とFastScrollerView(つまみ)を連動させるヘルパー。
//
// つまみの自動フェード表示に対応。つまみの寸法・スクロール可能かどうかは
// レイアウト更新やデータ変更のたびに常に計算し直すが、「実際に表示する/しない」の
// 切り替え自体はFastScrollerView側が担当する。このクラスは、一覧が実際にスクロール
// されたタイミングでのみ FastScrollerView.showTemporarily() を呼び出す。
//
// 使い方: RecyclerViewにadapterをセットした「後」に
//   RecyclerViewFastScrollHelper(recyclerView, fastScrollerView).attach()
// を呼ぶ。
class RecyclerViewFastScrollHelper(
    private val recyclerView: RecyclerView,
    private val thumbView: FastScrollerView
) {

    fun attach() {
        thumbView.onDragFraction = { fraction -> onThumbDragged(fraction) }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateGeometry()
                // 一覧が実際にスクロールされたタイミングでのみ、つまみを一時的に表示する
                thumbView.showTemporarily()
            }
        })

        // RecyclerViewが実際にレイアウトされるたびに、つまみの寸法だけを更新する
        // (表示/非表示は切り替えない)。データが非同期(コルーチン)で後から入ってくる
        // 画面でも、「アイテムが実際に画面へ配置されたタイミング」で正しいサイズに揃う
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener {
            updateGeometry()
        }

        recyclerView.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateGeometry()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = updateGeometry()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = updateGeometry()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateGeometry()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateGeometry()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = updateGeometry()
        })

        updateGeometry()
    }

    // つまみの寸法・位置・「スクロール可能かどうか」を計算する。
    // 表示/非表示自体はFastScrollerView.setScrollable()/showTemporarily()に委ねる
    private fun updateGeometry() {
        val trackHeightPx = recyclerView.height
        if (trackHeightPx == 0) return

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            // データ読み込み中などでアイテムが0件の場合はスクロール不可扱いにする
            thumbView.setScrollable(false)
            return
        }

        val extent = recyclerView.computeVerticalScrollExtent()
        val range = recyclerView.computeVerticalScrollRange()
        val offset = recyclerView.computeVerticalScrollOffset()

        // 画面に収まりきる件数しかない場合はスクロールバー自体を使わない
        if (range <= extent || extent <= 0) {
            thumbView.setScrollable(false)
            return
        }
        thumbView.setScrollable(true)

        val minThumbHeightPx = (48 * recyclerView.resources.displayMetrics.density).toInt()
        val thumbHeightPx = (trackHeightPx.toFloat() * extent / range)
            .toInt()
            .coerceAtLeast(minThumbHeightPx)
            .coerceAtMost(trackHeightPx)

        val maxOffset = (range - extent).coerceAtLeast(1)
        val fraction = (offset.toFloat() / maxOffset).coerceIn(0f, 1f)
        val travel = (trackHeightPx - thumbHeightPx).coerceAtLeast(0)
        val thumbTopPx = fraction * travel

        thumbView.setThumb(thumbHeightPx, thumbTopPx)
    }

    // つまみがドラッグされたら、割合(0f〜1f)から対象のアイテム位置を計算してジャンプする
    private fun onThumbDragged(fraction: Float) {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) return
        val targetPosition = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        recyclerView.layoutManager?.scrollToPosition(targetPosition)
    }
}