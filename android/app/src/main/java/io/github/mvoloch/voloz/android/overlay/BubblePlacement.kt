package io.github.mvoloch.voloz.android.overlay

/**
 * Where the focused field is, in screen pixels, and the band the bubble must
 * stay in: below the status bar and, when the keyboard is up, above its window.
 */
data class BubbleAnchor(
    val fieldTop: Int,
    val fieldBottom: Int,
    /** Top of the IME window, or null when no keyboard window is on screen. */
    val imeTop: Int?,
    val statusBarBottom: Int,
)

/** Pure geometry for the bubble and its tooltip (screen pixels, y grows downward). */
object BubblePlacement {

    /**
     * Top of the squircle (not its window) for a [size] px bubble centred on the
     * field's vertical centre, clamped into [minTop, maxBottom]. When the band is
     * shorter than the bubble, the top limit wins.
     */
    fun squircleTop(fieldTop: Int, fieldBottom: Int, size: Int, minTop: Int, maxBottom: Int): Int {
        val centred = (fieldTop + fieldBottom) / 2 - size / 2
        val highest = maxBottom - size
        return if (highest < minTop) minTop else centred.coerceIn(minTop, highest)
    }

    enum class Side { ABOVE, BELOW, BESIDE }

    data class TooltipSpot(val side: Side, val top: Int)

    /**
     * Vertical spot for a tooltip of [height] px next to a bubble spanning
     * [bubbleTop, bubbleBottom]: above the bubble when that fits in
     * [minTop, maxBottom] without covering the field, otherwise below it, and
     * beside the bubble (centred on it) when neither fits or the field is unknown.
     */
    fun tooltip(
        bubbleTop: Int,
        bubbleBottom: Int,
        fieldTop: Int?,
        fieldBottom: Int?,
        height: Int,
        gap: Int,
        minTop: Int,
        maxBottom: Int,
    ): TooltipSpot {
        val beside = TooltipSpot(Side.BESIDE, bubbleTop + (bubbleBottom - bubbleTop - height) / 2)
        if (fieldTop == null || fieldBottom == null) return beside
        fun coversField(top: Int) = top < fieldBottom && top + height > fieldTop

        var above = bubbleTop - gap - height
        if (coversField(above)) above = fieldTop - gap - height
        if (above >= minTop) return TooltipSpot(Side.ABOVE, above)

        var below = bubbleBottom + gap
        if (coversField(below)) below = fieldBottom + gap
        if (below + height <= maxBottom) return TooltipSpot(Side.BELOW, below)

        return beside
    }
}
