package com.example.musicplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

// 画面右端に表示する、ドラッグ操作可能なスクロールバー(つまみ)。
// RecyclerViewFastScrollHelperと組み合わせて使う。このView自体は
// 「つまみの高さ・位置を受け取って描画する」「ドラッグされたら割合を通知する」だけを行う。
//
// 自動フェード表示対応: 基本的には非表示(GONE)で、一覧がスクロールされた瞬間に
// showTemporarily()が呼ばれてフェードイン表示され、操作が止まると自動的にフェードアウトして隠れる。
// 非表示中(GONE)はタッチを一切受け取らないため、裏にある三点リーダー等のボタンと
// タッチ領域が競合しなくなる。
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ドラッグ中に呼ばれる。0f(一番上)〜1f(一番下)の割合を渡す
    var onDragFraction: ((Float) -> Unit)? = null

    private var thumbHeightPx = 0f
    private var thumbTopPx = 0f
    private var isDragging = false

    // 一覧がスクロール可能かどうか。falseの間はshowTemporarily()を呼んでも何もしない
    private var isScrollable = false

    private val thumbWidthPx = 4 * resources.displayMetrics.density

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { fadeOut() }

    companion object {
        private const val AUTO_HIDE_DELAY_MS = 800L
        private const val FADE_IN_MS = 150L
        private const val FADE_OUT_MS = 300L
    }

    init {
        // 基本は非表示。スクロールが実際に起きたときだけshowTemporarily()で表示する
        visibility = GONE
        alpha = 0f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            Color.GRAY
        )
    }

    // RecyclerViewFastScrollHelperから、つまみの高さ(px)と上端位置(px)を受け取る
    fun setThumb(heightPx: Int, topPx: Float) {
        thumbHeightPx = heightPx.toFloat()
        thumbTopPx = topPx
        invalidate()
    }

    // 一覧がスクロール可能かどうかをRecyclerViewFastScrollHelperから受け取る。
    // 不可になった場合(表示件数が画面に収まる等)は、フェードなしで即座に隠す
    fun setScrollable(scrollable: Boolean) {
        isScrollable = scrollable
        if (!scrollable) {
            fadeHandler.removeCallbacks(hideRunnable)
            animate().cancel()
            visibility = GONE
            alpha = 0f
        }
    }

    // 一覧が実際にスクロールされたタイミングで呼ぶ。つまみをフェードインさせ、
    // ドラッグ中でなければ一定時間後に自動でフェードアウトするタイマーをセットする
    fun showTemporarily() {
        if (!isScrollable) return

        fadeHandler.removeCallbacks(hideRunnable)

        if (visibility != VISIBLE || alpha < 1f) {
            visibility = VISIBLE
            animate().cancel()
            animate()
                .alpha(1f)
                .setDuration(FADE_IN_MS)
                .start()
        }

        if (!isDragging) {
            fadeHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun fadeOut() {
        if (isDragging) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (alpha == 0f) visibility = GONE
                }
            })
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbHeightPx <= 0f) return

        val centerX = width / 2f
        paint.alpha = if (isDragging) 255 else 140

        val rect = RectF(
            centerX - thumbWidthPx / 2f,
            thumbTopPx,
            centerX + thumbWidthPx / 2f,
            thumbTopPx + thumbHeightPx
        )
        canvas.drawRoundRect(rect, thumbWidthPx / 2f, thumbWidthPx / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                fadeHandler.removeCallbacks(hideRunnable)
                invalidate()
                dragTo(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                // ドラッグ終了後、通常の自動フェードアウトタイマーを(再)開始する
                showTemporarily()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // タッチ位置(縦方向のpx)を、一覧全体に対する割合(0f〜1f)に変換して通知する
    private fun dragTo(touchY: Float) {
        val travel = (height - thumbHeightPx).coerceAtLeast(1f)
        val half = thumbHeightPx / 2f
        val fraction = ((touchY - half) / travel).coerceIn(0f, 1f)
        onDragFraction?.invoke(fraction)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeHandler.removeCallbacks(hideRunnable)
    }
}