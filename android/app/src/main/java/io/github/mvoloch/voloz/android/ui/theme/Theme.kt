package io.github.mvoloch.voloz.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.data.ThemeMode

/**
 * The single place for the app's look: the desktop Voloz Lite tokens
 * (app/src/index.css `.edition-lite`, approved mockup design/lite-v2): cool grey,
 * white cards, ink, and red ([VolozPalette.signal]) only for voice: the
 * recording dot and the logo caret. (The overlay bubble has its own tokens in
 * overlay/BubbleStyle.kt.)
 */
@Immutable
data class VolozPalette(
    val mist: Color, // page background
    val sheet: Color, // cards, fields
    val ink: Color, // text, primary buttons
    val graphite: Color, // secondary text
    val faint: Color, // meta, section labels
    val hair: Color, // borders, dividers
    val signal: Color, // voice only (and a key that is missing)
    val signalSoft: Color,
    val ok: Color,
    val key: Color, // keycaps
    val keyEdge: Color,
    val dark: Boolean,
)

val LightPalette = VolozPalette(
    mist = Color(0xFFF3F4F6),
    sheet = Color(0xFFFFFFFF),
    ink = Color(0xFF121417),
    graphite = Color(0xFF5E6470),
    faint = Color(0xFF9AA0AA),
    hair = Color(0xFFE4E6EA),
    signal = Color(0xFFE5483C),
    signalSoft = Color(0xFFFDECEA),
    ok = Color(0xFF1F9D63),
    key = Color(0xFFFFFFFF),
    keyEdge = Color(0xFFD5D8DE),
    dark = false,
)

val DarkPalette = VolozPalette(
    mist = Color(0xFF0E0F11),
    sheet = Color(0xFF16181B),
    ink = Color(0xFFECEDEF),
    graphite = Color(0xFF9097A3),
    faint = Color(0xFF5F6570),
    hair = Color(0xFF25282D),
    signal = Color(0xFFFF5A4E),
    signalSoft = Color(0xFF2A1614),
    ok = Color(0xFF3CC487),
    key = Color(0xFF1E2125),
    keyEdge = Color(0xFF33373D),
    dark = true,
)

val LocalVolozPalette = staticCompositionLocalOf { LightPalette }

/** The current palette's colours, by token name. */
object VolozColors {
    val Mist: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.mist
    val Sheet: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.sheet
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.ink
    val Graphite: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.graphite
    val Faint: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.faint
    val Hair: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.hair
    val Signal: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.signal
    val SignalSoft: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.signalSoft
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.ok
    val Key: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.key
    val KeyEdge: Color @Composable @ReadOnlyComposable get() = LocalVolozPalette.current.keyEdge

    /** The desktop pill/notch: black like the phone's hardware in both themes. */
    val Notch = Color(0xFF0B0B0C)
    val OnNotch = Color(0xE6FFFFFF)
}

/**
 * Bricolage Grotesque (titles), Onest (text) and JetBrains Mono (keys, times,
 * model names), as on the desktop. Variable TTFs in res/font, OFL (licences in
 * android/fonts-licenses); Bricolage and Onest are the Latin subsets of the
 * desktop's own WOFF2 files, converted with fontTools.
 */
@OptIn(ExperimentalTextApi::class)
object VolozFonts {
    private fun weights(res: Int, vararg w: Int) = FontFamily(
        w.map { Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it))) },
    )

    val Display: FontFamily = weights(R.font.bricolage_grotesque, 500, 650)
    val Body: FontFamily = weights(R.font.onest, 400, 500, 600)
    val Mono: FontFamily = weights(R.font.jetbrains_mono, 500)
}

object VolozType {
    private val Title = FontWeight(650)

    /** Home hero: mockup h1 (650, line 1.05, -0.035em), sized for a phone. */
    val Hero = TextStyle(fontFamily = VolozFonts.Display, fontWeight = Title, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.035).em)
    val Headline = TextStyle(fontFamily = VolozFonts.Display, fontWeight = Title, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.03).em)
    val HeadlineXL = Hero.copy(fontSize = 38.sp, lineHeight = 41.sp)
    /** Settings page title: mockup h2. */
    val PageTitle = TextStyle(fontFamily = VolozFonts.Display, fontWeight = Title, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.03).em)
    val Wordmark = TextStyle(fontFamily = VolozFonts.Display, fontWeight = Title, fontSize = 20.sp, letterSpacing = (-0.02).em)
    val CardTitle = TextStyle(fontFamily = VolozFonts.Display, fontWeight = Title, fontSize = 20.sp, letterSpacing = (-0.02).em)
    val BodyLarge = TextStyle(fontFamily = VolozFonts.Body, fontSize = 16.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = VolozFonts.Body, fontSize = 15.sp, lineHeight = 22.sp)
    val BodySmall = TextStyle(fontFamily = VolozFonts.Body, fontSize = 14.sp, lineHeight = 21.sp)
    val RowTitle = TextStyle(fontFamily = VolozFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = VolozFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val Caption = TextStyle(fontFamily = VolozFonts.Body, fontSize = 13.sp, lineHeight = 18.sp)
    val Meta = TextStyle(fontFamily = VolozFonts.Body, fontSize = 12.sp, lineHeight = 16.sp)
    val Button = TextStyle(fontFamily = VolozFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val MonoKey = TextStyle(fontFamily = VolozFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val Mono = TextStyle(fontFamily = VolozFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    /** Group headers ("PROVEDORES, NA ORDEM") and day labels: mockup .gh / .day. */
    val Eyebrow = TextStyle(fontFamily = VolozFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.08.em)
}

object VolozShapes {
    val Card = RoundedCornerShape(14.dp) // mockup .group
    val CardLarge = RoundedCornerShape(18.dp)
    val Field = RoundedCornerShape(12.dp) // mockup .search
    val Small = RoundedCornerShape(9.dp) // mockup .sel / .ctl
    val Key = RoundedCornerShape(10.dp) // mockup .kc
    val Pill = RoundedCornerShape(999.dp)
}

object VolozDimens {
    val ScreenPadding = 20.dp
    val ButtonHeight = 52.dp
    val FieldHeight = 48.dp
    val MinTouch = 44.dp
}

/** Follows the system unless the user picked a theme in Ajustes › Geral. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun VolozTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.ink,
        onPrimary = p.mist,
        secondary = p.ink,
        onSecondary = p.mist,
        tertiary = p.ink,
        background = p.mist,
        onBackground = p.ink,
        surface = p.sheet,
        onSurface = p.ink,
        surfaceVariant = p.mist,
        onSurfaceVariant = p.graphite,
        surfaceContainerHigh = p.sheet,
        surfaceContainerHighest = p.sheet,
        outline = p.hair,
        outlineVariant = p.hair,
        error = p.signal,
    )
    CompositionLocalProvider(LocalVolozPalette provides p, LocalContentColor provides p.ink) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                bodyLarge = VolozType.Body,
                bodyMedium = VolozType.BodySmall,
                bodySmall = VolozType.Caption,
                labelLarge = VolozType.Label,
                titleLarge = VolozType.CardTitle,
                headlineSmall = VolozType.CardTitle,
            ),
            shapes = Shapes(small = VolozShapes.Small, medium = VolozShapes.Field, large = VolozShapes.Card),
            content = content,
        )
    }
}
