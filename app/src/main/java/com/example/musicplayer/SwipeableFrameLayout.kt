package com.example.musicplayer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * メイン画面のトップレベル5タブ(ホーム/曲/アーティスト/アルバム/プレイリスト)間を
 * 横スワイプで切り替えるための、fragmentContainerの入れ物。
 *
 * [isSwipeAllowed] で、今スワイプを許可してよいか(詳細画面表示中でないか等)を
 * MainActivity側から都度判定してもらう。ジェスチャー開始時(ACTION_DOWN)に1回だけ判定し、
 * そのジェスチャーが終わるまで結果を使い回す。
 *
 * ホーム画面の横スクロールカード(最近追加した曲・プレイリスト)との競合を避けるため、
 * このViewが「横スワイプだ」と判断するしきい値([INTERCEPT_THRESHOLD_DP])は、
 * RecyclerView内部が使う標準しきい値(touchSlop、8dp程度)より意図的にかなり大きくしてある。
 * こうすることで、指の移動量がまだ小さいうちはRecyclerView側に先にイベントが渡り、
 * RecyclerViewが横スクロール可能だと判断すれば先に自分自身で処理を始め、
 * 親であるこのView側へ「割り込ませない」よう伝える(requestDisallowInterceptTouchEvent)。
 * その結果、このViewのonInterceptTouchEvent自体が以降呼ばれなくなり、タブ切替とは競合しない。
 */
class SwipeableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var isSwipeAllowed: (() -> Boolean)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    // 横スワイプと判断するまでの移動量(RecyclerViewの内部しきい値より意図的に大きくする)
    private val interceptThresholdPx = (INTERCEPT_THRESHOLD_DP * resources.displayMetrics.density)

    private var downX = 0f
    private var downY = 0f
    private var isDraggingHorizontally = false
    private var swipeAllowedForThisGesture = true

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val deltaX = e2.x - e1.x
            val deltaY = e2.y - e1.y
            if (abs(deltaX) > abs(deltaY) &&
                abs(deltaX) > SWIPE_DISTANCE_THRESHOLD_PX &&
                abs(velocityX) > SWIPE_VELOCITY_THRESHOLD_PX
            ) {
                if (deltaX < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                return true
            }
            return false
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // ACTION_DOWNの時点から常にジェスチャー検知器へイベントを渡しておく。
        // ここで渡し始めないと、GestureDetectorが最初の指の位置(down)を把握できず、
        // 後からonTouchEvent側だけでイベントを渡してもonFlingが正しく発火しないことがある
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isDraggingHorizontally = false
                swipeAllowedForThisGesture = isSwipeAllowed?.invoke() ?: true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeAllowedForThisGesture && !isDraggingHorizontally) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > interceptThresholdPx && abs(dx) > abs(dy)) {
                        isDraggingHorizontally = true
                        // ここから先はこのView自身がタッチを処理する(子には渡らなくなる)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private const val INTERCEPT_THRESHOLD_DP = 32
        private const val SWIPE_DISTANCE_THRESHOLD_PX = 80
        private const val SWIPE_VELOCITY_THRESHOLD_PX = 100
    }
}