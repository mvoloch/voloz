package io.github.mvoloch.voloz.android.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/** Stroke icons copied from the design mockups' inline SVGs (24-unit viewBox). */
object VolozIcons {
    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (d in paths) {
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.Black), // tinted where used
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    val Back = stroke("back", 2f, "M15 18l-6-6 6-6")
    val Check = stroke("check", 2.6f, "M5 12.5l4.5 4.5L19 7.5")
    val Copy = stroke(
        "copy", 1.8f,
        "M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z",
        "M5 15V6a2 2 0 0 1 2-2h9",
    )
    val Lock = stroke(
        "lock", 2f,
        "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z",
        "M8 11V8a4 4 0 0 1 8 0v3",
    )
    val Close = stroke("close", 2f, "M7 7l10 10", "M17 7l-10 10")
    val Up = stroke("up", 2f, "M12 19V5", "M6 11l6-6 6 6")
    val More = stroke("more", 2.6f, "M5.5 12h.01", "M12 12h.01", "M18.5 12h.01")
    val Search = stroke("search", 2f, "M17 11a6 6 0 1 1 -12 0a6 6 0 1 1 12 0z", "M15.5 15.5L20 20")
    val Chevron = stroke("chevron", 2f, "M9 6l6 6-6 6")
}
