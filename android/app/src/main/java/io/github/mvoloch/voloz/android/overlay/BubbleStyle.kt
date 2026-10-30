package io.github.mvoloch.voloz.android.overlay

import android.graphics.Color

/**
 * Every visual knob of the floating bubble (drawn by [BubbleView], not Compose,
 * because it lives in an accessibility overlay window). Dimensions are dp at the
 * 60dp design size and scale with the bubble-size setting.
 */
object BubbleStyle {
    // Colours: the desktop Lite pill (CapturePill, notch): black like the phone's
    // hardware, white marks, red only for the recording dot.
    val NOTCH = Color.parseColor("#0B0B0C")
    val ON_NOTCH = Color.WHITE
    /** Idle bars: the desktop's white/30, a little stronger since the idle bubble is also translucent. */
    val IDLE_BAR = Color.argb(140, 255, 255, 255)
    val SIGNAL = Color.parseColor("#FF5A4E") // --signal (dark), on black
    val DONE = Color.parseColor("#34D399") // desktop pill check (emerald-400)
    val AMBER = Color.parseColor("#FFB224")
    val RING_TRACK = Color.argb(38, 255, 255, 255)
    val TOOLTIP_HINT = Color.argb(178, 255, 255, 255)
    val SHADOW = Color.argb(70, 0, 0, 0)
    /** Faint rim so the black bubble keeps its outline over dark apps. */
    val RIM = Color.argb(36, 255, 255, 255)
    const val RIM_DP = 1f

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
    const val TOOLTIP_CORNER_DP = 999f // a capsule, like the desktop pill
    const val TOOLTIP_PAD_H_DP = 12f
    const val TOOLTIP_PAD_V_DP = 8f
    const val TOOLTIP_GAP_DP = 10f
    const val TOOLTIP_LABEL_SP = 13f
    const val TOOLTIP_TEXT_SP = 14f
}
