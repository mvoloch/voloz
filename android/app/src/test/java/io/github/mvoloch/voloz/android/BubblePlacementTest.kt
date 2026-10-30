package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.overlay.BubblePlacement
import io.github.mvoloch.voloz.android.overlay.BubblePlacement.Side
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pixel numbers from the API 34 emulator (1080x2340): status bar 0..136, IME from 1507. */
class BubblePlacementTest {
    private val size = 165 // 60dp at 2.75x

    private val gap = 22 // 8dp

    @Test
    fun bubbleSitsJustAboveTheField() {
        // Voloz Home "Teste aqui" field 1100..1243, keyboard up (band 152..1491).
        assertEquals(1100 - gap - size, BubblePlacement.squircleTop(1100, 1243, size, gap, minTop = 152, maxBottom = 1491))
    }

    @Test
    fun bubbleAboveAFieldRightOnTopOfTheKeyboard() {
        // Messages compose field 1351..1483 just above the keyboard: still above it, never over its buttons.
        assertEquals(1351 - gap - size, BubblePlacement.squircleTop(1351, 1483, size, gap, minTop = 152, maxBottom = 1491))
    }

    @Test
    fun bubbleGoesBelowWhenNoRoomAbove() {
        // Field right under the status bar (e.g. a search box).
        assertEquals(250 + gap, BubblePlacement.squircleTop(160, 250, size, gap, minTop = 152, maxBottom = 1491))
    }

    @Test
    fun tallEditorPutsTheBubbleAboveTheKeyboard() {
        // Neither above nor below fits: bottom of the band.
        assertEquals(1491 - size, BubblePlacement.squircleTop(200, 1450, size, gap, minTop = 152, maxBottom = 1491))
    }

    @Test
    fun topLimitWinsWhenTheBandIsTooShort() {
        assertEquals(152, BubblePlacement.squircleTop(200, 260, size, gap, minTop = 152, maxBottom = 250))
    }

    @Test
    fun tooltipGoesAboveWithoutCoveringTheField() {
        // Single-line field shorter than the bubble: just above the bubble already clears it.
        val short = BubblePlacement.tooltip(1089, 1254, 1100, 1243, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.ABOVE, short.side)
        assertEquals(1089 - 27 - 100, short.top)
        // Multi-line field taller than the bubble: "above the bubble" would overlap it, so go above the field.
        val tall = BubblePlacement.tooltip(1093, 1258, 1050, 1300, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.ABOVE, tall.side)
        assertEquals(1050 - 27 - 100, tall.top)
    }

    @Test
    fun tooltipGoesBelowWhenNoRoomAbove() {
        val spot = BubblePlacement.tooltip(180, 345, 170, 300, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.BELOW, spot.side)
        assertEquals(345 + 27, spot.top)
    }

    @Test
    fun tooltipBelowAlsoClearsATallerField() {
        val spot = BubblePlacement.tooltip(180, 345, 150, 500, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.BELOW, spot.side)
        assertEquals(500 + 27, spot.top)
    }

    @Test
    fun tooltipBesideWhenNeitherFitsOrFieldUnknown() {
        assertEquals(
            Side.BESIDE,
            BubblePlacement.tooltip(180, 345, 170, 1450, height = 100, gap = 27, minTop = 163, maxBottom = 1507).side,
        )
        val unknown = BubblePlacement.tooltip(1000, 1165, null, null, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.BESIDE, unknown.side)
        assertEquals(1000 + (165 - 100) / 2, unknown.top)
    }

    @Test
    fun tooltipAboveIgnoresAFieldFarBelow() {
        // A dragged bubble high on the screen, field near the bottom: tooltip hugs the bubble.
        val spot = BubblePlacement.tooltip(600, 765, 1300, 1400, height = 100, gap = 27, minTop = 163, maxBottom = 1507)
        assertEquals(Side.ABOVE, spot.side)
        assertEquals(600 - 27 - 100, spot.top)
    }
}
