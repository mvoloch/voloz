package io.github.mvoloch.voloz.android.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.dictation.BubbleState
import kotlin.math.hypot

/** Remembers which edge the bubble snapped to and how far down the screen it sits. */
class BubblePositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)
    val onRight: Boolean get() = prefs.getBoolean("right", true)
    val yFraction: Float get() = prefs.getFloat("y", 0.32f)
    fun save(onRight: Boolean, yFraction: Float) {
        prefs.edit().putBoolean("right", onRight).putFloat("y", yFraction.coerceIn(0f, 1f)).apply()
    }
}

/**
 * The floating bubble as a TYPE_ACCESSIBILITY_OVERLAY window owned by the
 * accessibility service (no "draw over other apps" permission needed).
 * Not focusable, so tapping it never takes focus from the text field.
 *
 * Gestures: tap = start/stop hands-free; press and hold (>= [HOLD_MS]) =
 * push-to-talk, release stops; drag (moving before the hold fires) moves it,
 * and on release it snaps to the nearest side edge and remembers the spot.
 */
@SuppressLint("ClickableViewAccessibility")
class BubbleOverlay(
    private val context: Context,
    private val store: BubblePositionStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd()
    }

    companion object {
        const val HOLD_MS = 350L
        private const val SNAP_MS = 180L
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bubble = BubbleView(context)
    private val params = overlayParams(touchable = true)
    private var attached = false

    private val tooltip = buildTooltip()
    private val tooltipParams = overlayParams(touchable = false)
    private var tooltipAttached = false

    private var state: BubbleState = BubbleState.Idle
    private var snapAnimator: ValueAnimator? = null

    // gesture state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var holding = false
    private val holdRunnable = Runnable {
        holding = true
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onHoldStart()
    }

    init {
        bubble.setOnTouchListener { v, e -> onTouch(v, e) }
        bubble.setOnClickListener { listener.onTap() } // for TalkBack activation
    }

    private fun dp(v: Float) = (v * density).toInt()

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val isShown: Boolean get() = attached

    fun show() {
        if (attached) return
        placeFromStore()
        attached = runCatching { wm.addView(bubble, params) }.isSuccess
        updateTooltip()
    }

    fun hide() {
        if (!attached) return
        handler.removeCallbacks(holdRunnable)
        snapAnimator?.cancel()
        runCatching { wm.removeView(bubble) }
        attached = false
        updateTooltip()
    }

    fun setState(newState: BubbleState) {
        val tooltipBefore = (state as? BubbleState.Recording)?.handsFree == true
        state = newState
        bubble.setState(newState)
        // Level ticks arrive ~10x/s; only touch the tooltip window when it should appear/disappear.
        if (tooltipBefore != ((newState as? BubbleState.Recording)?.handsFree == true)) updateTooltip()
    }

    fun setBubbleSizeDp(sizeDp: Int) {
        val px = dp(sizeDp.toFloat())
        if (px == bubble.bubblePx) return
        bubble.bubblePx = px
        if (attached) {
            placeFromStore()
            runCatching { wm.updateViewLayout(bubble, params) }
            updateTooltip()
        }
    }

    /** Re-place after rotation or a screen-size change. */
    fun relayout() {
        if (!attached) return
        placeFromStore()
        runCatching { wm.updateViewLayout(bubble, params) }
        updateTooltip()
    }

    // ---- geometry -------------------------------------------------------

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun edgeX(onRight: Boolean, screenW: Int): Int {
        val margin = dp(BubbleStyle.EDGE_MARGIN_DP)
        // Window x so the squircle (not its shadow pad) sits [margin] from the edge.
        return if (onRight) screenW - margin - bubble.bubblePx - bubble.padPx else margin - bubble.padPx
    }

    private fun clampY(y: Int, screenH: Int): Int {
        val top = dp(40f) - bubble.padPx
        val bottom = screenH - dp(64f) - bubble.bubblePx - bubble.padPx
        return y.coerceIn(top, maxOf(top, bottom))
    }

    private fun placeFromStore() {
        val (w, h) = screenSize()
        params.x = edgeX(store.onRight, w)
        params.y = clampY((store.yFraction * h).toInt(), h)
    }

    // ---- touch ----------------------------------------------------------

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                bubble.setTouching(true)
                downX = e.rawX
                downY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                holding = false
                handler.postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!holding && !dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(holdRunnable)
                    hideTooltip()
                }
                if (dragging) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(bubble, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                // Act first so a tap's new state lands before the idle fade could start.
                when {
                    holding -> listener.onHoldEnd()
                    dragging -> snapToEdge()
                    else -> v.performClick()
                }
                bubble.setTouching(false)
                holding = false
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                if (holding) listener.onHoldEnd() else if (dragging) snapToEdge()
                bubble.setTouching(false)
                holding = false
                dragging = false
            }
        }
        return true
    }

    private fun snapToEdge() {
        val (w, h) = screenSize()
        val centerX = params.x + bubble.windowPx / 2
        val onRight = centerX > w / 2
        params.y = clampY(params.y, h)
        store.save(onRight, params.y.toFloat() / h)
        val from = params.x
        val to = edgeX(onRight, w)
        snapAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (attached) runCatching { wm.updateViewLayout(bubble, params) }
            }
            doOnEnd { updateTooltip() }
            start()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    // ---- hands-free tooltip ---------------------------------------------

    private fun buildTooltip(): View {
        val label = TextView(context).apply {
            text = context.getString(R.string.tooltip_hands_free)
            setTextColor(BubbleStyle.LIME)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BubbleStyle.TOOLTIP_LABEL_SP)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.tooltip_tap_again)
            setTextColor(BubbleStyle.PAPER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BubbleStyle.TOOLTIP_TEXT_SP)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ph = dp(BubbleStyle.TOOLTIP_PAD_H_DP)
            val pv = dp(BubbleStyle.TOOLTIP_PAD_V_DP)
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                setColor(BubbleStyle.INK)
                cornerRadius = BubbleStyle.TOOLTIP_CORNER_DP * density
            }
            addView(label)
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10f) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun updateTooltip() {
        val want = attached && !dragging && (state as? BubbleState.Recording)?.handsFree == true
        if (!want) return hideTooltip()
        val (w, _) = screenSize()
        tooltip.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val tw = tooltip.measuredWidth
        val th = tooltip.measuredHeight
        val gap = dp(BubbleStyle.TOOLTIP_GAP_DP)
        val bubbleLeft = params.x + bubble.padPx
        val bubbleRight = bubbleLeft + bubble.bubblePx
        val onRight = bubbleLeft + bubble.bubblePx / 2 > w / 2
        tooltipParams.x = if (onRight) bubbleLeft - gap - tw else bubbleRight + gap
        tooltipParams.y = params.y + bubble.padPx + (bubble.bubblePx - th) / 2
        if (tooltipAttached) {
            runCatching { wm.updateViewLayout(tooltip, tooltipParams) }
        } else {
            tooltipAttached = runCatching { wm.addView(tooltip, tooltipParams) }.isSuccess
        }
    }

    private fun hideTooltip() {
        if (!tooltipAttached) return
        runCatching { wm.removeView(tooltip) }
        tooltipAttached = false
    }
}
