package com.example.musicplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

// 再生中アニメーション機能で追加。再生中の曲を示す、イコライザー風の小さなアニメーションView。
// アルバムアートのサムネイルに重ねて使う(暗い半透明の膜の上に、白い3本のバーを描く)。
// setPlaying(true)でバーが波打ち、setPlaying(false)で静止した状態になる。
// 画面から外れたとき(RecyclerViewでの再利用含む)はアニメーションを自動的に止める。
class NowPlayingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barCount = 3

    // 一時停止中に表示する、バーの高さ(0f〜1f)
    private val staticLevels = floatArrayOf(0.45f, 0.85f, 0.6f)

    // 3本のバーが同じ動きにならないよう、動く速さを少しずつ変える
    private val barDurationsMs = longArrayOf(520L, 700L, 610L)

    // 現在描画しているバーの高さ。アニメーション中は毎フレーム更新される
    private val levels = staticLevels.copyOf()

    private val scrimPaint = Paint().apply { color = Color.argb(115, 0, 0, 0) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val barRect = RectF()

    private var isPlayingState = false
    private val animators = mutableListOf<ValueAnimator>()

    // trueでバーが動き、falseで静止する(見えていない/画面外のときは自動的に止まる)
    fun setPlaying(playing: Boolean) {
        if (isPlayingState == playing) return
        isPlayingState = playing
        updateAnimation()
    }

    private fun updateAnimation() {
        if (isPlayingState && isAttachedToWindow) startAnimators() else stopAnimators()
    }

    private fun startAnimators() {
        if (animators.isNotEmpty()) return
        for (i in 0 until barCount) {
            val animator = ValueAnimator.ofFloat(0.2f, 1f).apply {
                duration = barDurationsMs[i]
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    levels[i] = it.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(animator)
            animator.start()
            // 開始位置をずらして、3本が最初から同じ高さにならないようにする
            animator.currentPlayTime = i * 150L
        }
    }

    private fun stopAnimators() {
        animators.forEach { it.cancel() }
        animators.clear()
        for (i in 0 until barCount) levels[i] = staticLevels[i]
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimators()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 全面に暗い膜を敷く(角丸は親のCardViewが自動的に切り抜く)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // バーを描く領域: 中央の、短辺の半分の大きさの正方形
        val areaSize = minOf(w, h) * 0.5f
        val gap = areaSize * 0.14f
        val barWidth = (areaSize - gap * (barCount - 1)) / barCount
        val areaLeft = (w - areaSize) / 2f
        val areaBottom = (h + areaSize) / 2f

        for (i in 0 until barCount) {
            val barHeight = areaSize * levels[i]
            val left = areaLeft + i * (barWidth + gap)
            barRect.set(left, areaBottom - barHeight, left + barWidth, areaBottom)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }
}