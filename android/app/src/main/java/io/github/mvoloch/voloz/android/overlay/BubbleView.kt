package io.github.mvoloch.voloz.android.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.dictation.BubbleState
import kotlin.math.max
import kotlin.math.min

/** The bubble itself: a black squircle (the desktop notch pill) with symbol-only content per [BubbleState]. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var state: BubbleState = BubbleState.Idle
    private var pressedDown = false

    /** Bubble (squircle) size in px; the view is this plus the shadow pad on each side. */
    var bubblePx: Int = dp(BubbleStyle.DESIGN_SIZE_DP).toInt()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    val padPx: Int get() = dp(BubbleStyle.WINDOW_PAD_DP).toInt()
    val windowPx: Int get() = bubblePx + 2 * padPx

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BubbleStyle.NOTCH }
    private val rect = RectF()
    private val rimRect = RectF()

    private val levelHistory = FloatArray(BubbleStyle.BAR_COUNT)
    private var spinAngle = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = BubbleStyle.SPINNER_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spinAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val checkPath = PathParser.createPathFromPathData(BubbleStyle.CHECK_PATH)
    private val warningPaths = BubbleStyle.WARNING_PATHS.map { PathParser.createPathFromPathData(it) }
    private val pastePaths = BubbleStyle.PASTE_PATHS.map { PathParser.createPathFromPathData(it) }
    private val retryPaths = BubbleStyle.RETRY_PATHS.map { PathParser.createPathFromPathData(it) }
    private val scratch = Path()
    private val glyphMatrix = Matrix()

    init {
        alpha = BubbleStyle.IDLE_ALPHA
        contentDescription = context.getString(R.string.bubble_cd_idle)
    }

    private fun dp(v: Float) = v * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(windowPx, windowPx)
    }

    fun setState(newState: BubbleState) {
        val wasIdle = state == BubbleState.Idle
        state = newState
        if (newState is BubbleState.Recording) {
            System.arraycopy(levelHistory, 1, levelHistory, 0, levelHistory.size - 1)
            levelHistory[levelHistory.size - 1] = newState.level
        } else {
            levelHistory.fill(0f)
        }
        if (newState == BubbleState.Processing) {
            if (!spinner.isStarted) spinner.start()
        } else {
            spinner.cancel()
        }
        contentDescription = context.getString(
            when (newState) {
                BubbleState.Idle -> R.string.bubble_cd_idle
                is BubbleState.Recording -> R.string.bubble_cd_recording
                BubbleState.Processing -> R.string.bubble_cd_processing
                BubbleState.Done -> R.string.bubble_cd_done
                is BubbleState.Error -> R.string.bubble_cd_error
                BubbleState.PasteReady -> R.string.bubble_cd_paste
                BubbleState.RetryReady -> R.string.bubble_cd_retry
            },
        )
        updateAlpha(animate = !wasIdle && newState == BubbleState.Idle)
        invalidate()
    }

    /** Touch down makes the bubble opaque at once, before tap vs hold is known. */
    fun setTouching(touching: Boolean) {
        pressedDown = touching
        updateAlpha(animate = !touching)
    }

    private fun updateAlpha(animate: Boolean) {
        val target = if (state == BubbleState.Idle && !pressedDown) BubbleStyle.IDLE_ALPHA else 1f
        animate().cancel()
        if (animate && target < alpha) {
            animate().alpha(target).setDuration(BubbleStyle.ALPHA_ANIM_MS).start()
        } else {
            alpha = target
        }
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = padPx.toFloat()
        val size = bubblePx.toFloat()
        val k = size / dp(BubbleStyle.DESIGN_SIZE_DP) // scale vs the 60dp design
        val corner = dp(BubbleStyle.CORNER_DP) * k
        rect.set(pad, pad, pad + size, pad + size)
        bodyPaint.setShadowLayer(dp(BubbleStyle.SHADOW_RADIUS_DP), 0f, dp(BubbleStyle.SHADOW_DY_DP), BubbleStyle.SHADOW)
        canvas.drawRoundRect(rect, corner, corner, bodyPaint)
        stroke.color = BubbleStyle.RIM
        stroke.strokeWidth = dp(BubbleStyle.RIM_DP)
        val inset = stroke.strokeWidth / 2
        rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(rimRect, corner - inset, corner - inset, stroke)

        val cx = rect.centerX()
        val cy = rect.centerY()
        when (val s = state) {
            BubbleState.Idle -> drawBars(canvas, cx, cy, k, null)
            is BubbleState.Recording -> {
                drawBars(canvas, cx, cy, k, s.level)
                fill.color = BubbleStyle.SIGNAL
                val r = dp(BubbleStyle.REC_DOT_DP) * k / 2
                val inset = dp(BubbleStyle.REC_DOT_INSET_DP) * k
                canvas.drawCircle(rect.right - inset - r, rect.top + inset + r, r, fill)
            }
            BubbleState.Processing -> drawSpinner(canvas, cx, cy, k)
            BubbleState.Done -> drawGlyph(canvas, cx, cy, k, listOf(checkPath), BubbleStyle.DONE, BubbleStyle.CHECK_STROKE)
            is BubbleState.Error -> drawGlyph(canvas, cx, cy, k, warningPaths, BubbleStyle.AMBER, BubbleStyle.WARNING_STROKE)
            BubbleState.PasteReady -> drawGlyph(canvas, cx, cy, k, pastePaths, BubbleStyle.ON_NOTCH, BubbleStyle.PASTE_STROKE)
            BubbleState.RetryReady -> drawGlyph(canvas, cx, cy, k, retryPaths, BubbleStyle.ON_NOTCH, BubbleStyle.RETRY_STROKE)
        }
    }

    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, k: Float, level: Float?) {
        val n = BubbleStyle.BAR_COUNT
        val w = dp(BubbleStyle.BAR_WIDTH_DP) * k
        val gap = dp(BubbleStyle.BAR_GAP_DP) * k
        val minH = dp(BubbleStyle.BAR_IDLE_HEIGHT_DP) * k
        val maxH = dp(BubbleStyle.BAR_MAX_HEIGHT_DP) * k
        val total = n * w + (n - 1) * gap
        var x = cx - total / 2
        fill.color = if (level == null) BubbleStyle.IDLE_BAR else BubbleStyle.ON_NOTCH
        for (i in 0 until n) {
            val h = if (level == null) {
                minH
            } else {
                // Mostly the live level, a little recent history so the bars move like a meter.
                val l = 0.65f * level + 0.35f * levelHistory[(i * 2) % n]
                max(minH, minH + (maxH - minH) * BubbleStyle.BAR_PROFILE[i] * min(1f, l))
            }
            canvas.drawRoundRect(x, cy - h / 2, x + w, cy + h / 2, w / 2, w / 2, fill)
            x += w + gap
        }
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        val sw = dp(BubbleStyle.RING_STROKE_DP) * k
        val r = dp(BubbleStyle.RING_DIAMETER_DP) * k / 2 - sw / 2
        stroke.strokeWidth = sw
        stroke.color = BubbleStyle.RING_TRACK
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.color = BubbleStyle.ON_NOTCH
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, spinAngle - 90f, 90f, false, stroke)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, k: Float, paths: List<Path>, color: Int, strokeUnits: Float) {
        val sizePx = dp(BubbleStyle.GLYPH_DP) * k
        val unit = sizePx / 24f
        glyphMatrix.reset()
        glyphMatrix.setScale(unit, unit)
        glyphMatrix.postTranslate(cx - sizePx / 2, cy - sizePx / 2)
        stroke.color = color
        stroke.strokeWidth = strokeUnits * unit
        for (p in paths) {
            scratch.reset()
            p.transform(glyphMatrix, scratch)
            canvas.drawPath(scratch, stroke)
        }
    }
}
