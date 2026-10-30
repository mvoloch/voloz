package io.github.mvoloch.voloz.android.overlay

import android.graphics.Color

/**
 * Every visual knob of the floating bubble (drawn by [BubbleView], not Compose,
 * because it lives in an accessibility overlay window). Dimensions are dp at the
 * 60dp design size and scale with the bubble-size setting.
 */
object BubbleStyle {
    // Colours (desktop Voloz tokens)
    val INK = Color.parseColor("#0D0D0D")
    val LIME = Color.parseColor("#C7FF38")
    val REC_RED = Color.parseColor("#FF4D4F")
    val AMBER = Color.parseColor("#FFB224")
    val RING_TRACK = Color.parseColor("#3A3A38")
    val PAPER = Color.parseColor("#F2F2F0")
    val SHADOW = Color.argb(70, 13, 13, 13)

    /** Opacity of the idle bubble; touch and every active state are fully opaque. */
    const val IDLE_ALPHA = 0.6f
    const val ALPHA_ANIM_MS = 200L

    // Geometry at the 60dp reference size
    const val DESIGN_SIZE_DP = 60f
    const val CORNER_DP = 22f
    const val SHADOW_RADIUS_DP = 8f
    const val SHADOW_DY_DP = 3f
    /** Transparent margin around the squircle so the shadow is not clipped. */
    const val WINDOW_PAD_DP = 12f
    /** Gap between the squircle and the screen edge after snapping. */
    const val EDGE_MARGIN_DP = 6f

    const val BAR_COUNT = 5
    const val BAR_WIDTH_DP = 4f
    const val BAR_GAP_DP = 3f
    const val BAR_IDLE_HEIGHT_DP = 6f
    const val BAR_MAX_HEIGHT_DP = 26f
    /** Relative height of each bar at full level (centre tallest). */
    val BAR_PROFILE = floatArrayOf(0.46f, 0.77f, 1f, 0.69f, 0.38f)

    const val REC_DOT_DP = 9f
    const val REC_DOT_INSET_DP = 7f

    const val RING_DIAMETER_DP = 26f
    const val RING_STROKE_DP = 3f
    const val SPINNER_PERIOD_MS = 900L

    /** Glyphs: 24-unit SVG paths drawn at [GLYPH_DP]. */
    const val GLYPH_DP = 28f
    const val CHECK_PATH = "M5 12.5l4.5 4.5L19 7.5"
    const val CHECK_STROKE = 2.6f
    val WARNING_PATHS = listOf("M12 3.5L2.5 20h19z", "M12 10v4.5", "M12 17.3v.2")
    const val WARNING_STROKE = 2.2f
    val PASTE_PATHS = listOf("M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z", "M5 15V6a2 2 0 0 1 2-2h9")
    const val PASTE_STROKE = 2f

    // Hands-free tooltip
    const val TOOLTIP_CORNER_DP = 10f
    const val TOOLTIP_PAD_H_DP = 12f
    const val TOOLTIP_PAD_V_DP = 8f
    const val TOOLTIP_GAP_DP = 10f
    const val TOOLTIP_LABEL_SP = 13f
    const val TOOLTIP_TEXT_SP = 14f
}
