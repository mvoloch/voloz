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
     * Top of the squircle (not its window) for a [size] px bubble that must not
     * cover the field (its own send/mic/emoji buttons sit inside it): just above
     * the field ([gap] between them), or just below it when there is no room
     * above, inside [minTop, maxBottom]. When neither fits (a tall editor filling
     * the band), the bubble goes to the bottom of the band, just above the
     * keyboard. When the band is shorter than the bubble, the top limit wins.
     */
    fun squircleTop(fieldTop: Int, fieldBottom: Int, size: Int, gap: Int, minTop: Int, maxBottom: Int): Int {
        val highest = maxBottom - size
        if (highest < minTop) return minTop
        val above = fieldTop - gap - size
        if (above in minTop..highest) return above
        val below = fieldBottom + gap
        if (below in minTop..highest) return below
        return highest
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
