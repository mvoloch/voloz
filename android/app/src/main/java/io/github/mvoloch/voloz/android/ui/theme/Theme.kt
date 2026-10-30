package io.github.mvoloch.voloz.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import androidx.compose.ui.text.font.FontVariation

/**
 * The single place for the app's look: colour tokens, type and shapes from the
 * desktop Voloz brand. (The overlay bubble has its own tokens in overlay/BubbleStyle.kt.)
 */
object VolozColors {
    val Paper = Color(0xFFF2F2F0)
    val Card = Color(0xFFFFFFFF)
    val CardHairline = Color(0xFFE0E0DB) // 1dp line under the mic preview card
    val Divider = Color(0xFFECECE7)
    val FieldBorder = Color(0xFFCFCFC9)
    val ProgressTrack = Color(0xFFD3D3CE)
    val Ink = Color(0xFF0D0D0D)
    val InkRaised = Color(0xFF1E1E1C) // chips/tiles on ink surfaces
    val OnInkSecondary = Color(0xFFB5B5AE)
    val OnInkChip = Color(0xFFE4E4DE)
    val TextSecondary = Color(0xFF45453F)
    val TextTertiary = Color(0xFF5C5C57)
    val Olive = Color(0xFF4C7A0F)
    val OliveDark = Color(0xFF3A5E0B)
    val OliveTint = Color(0xFFEEF3E4)
    val OliveTintText = Color(0xFF2E4A08)
    val OliveDashed = Color(0xFF9BA88A)
    val Lime = Color(0xFFC7FF38) // only on ink surfaces
    val RecRed = Color(0xFFFF4D4F)
    val Amber = Color(0xFFFFB224)
    val AmberText = Color(0xFF8A4B00)
}

/**
 * Fonts. The spec asks for Familjen Grotesk (display), Roboto Flex (body) and
 * JetBrains Mono (API key). Display and Mono are bundled; body uses the system
 * Roboto, which is visually close to Roboto Flex at these sizes.
 */
@OptIn(ExperimentalTextApi::class)
object VolozFonts {
    // Familjen Grotesk and JetBrains Mono: variable TTFs from google/fonts (OFL,
    // licences in android/fonts-licenses). Body text stays the system Roboto.
    val Display: FontFamily = FontFamily(
        Font(R.font.familjen_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.familjen_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
    val Body: FontFamily = FontFamily.Default
    val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    )
}

object VolozType {
    val Headline = TextStyle(fontFamily = VolozFonts.Display, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-0.02).em)
    val HeadlineXL = Headline.copy(fontSize = 40.sp, lineHeight = 42.sp)
    val Wordmark = TextStyle(fontFamily = VolozFonts.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.01).em)
    val CardTitle = TextStyle(fontFamily = VolozFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
    val BodyLarge = TextStyle(fontFamily = VolozFonts.Body, fontSize = 17.sp, lineHeight = 25.sp)
    val Body = TextStyle(fontFamily = VolozFonts.Body, fontSize = 16.sp, lineHeight = 24.sp)
    val BodySmall = TextStyle(fontFamily = VolozFonts.Body, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = VolozFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val Caption = TextStyle(fontFamily = VolozFonts.Body, fontSize = 13.sp, lineHeight = 19.sp)
    val Button = TextStyle(fontFamily = VolozFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val MonoKey = TextStyle(fontFamily = VolozFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp)
}

object VolozShapes {
    val Card = RoundedCornerShape(20.dp)
    val CardLarge = RoundedCornerShape(24.dp)
    val Group = RoundedCornerShape(18.dp)
    val ListItem = RoundedCornerShape(16.dp)
    val Field = RoundedCornerShape(14.dp)
    val Button = RoundedCornerShape(16.dp)
    val Small = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(999.dp)
}

object VolozDimens {
    val ScreenPadding = 24.dp
    val ButtonHeight = 56.dp
    val FieldHeight = 52.dp
    val MinTouch = 44.dp
}

private val LightScheme = lightColorScheme(
    primary = VolozColors.Ink,
    onPrimary = VolozColors.Paper,
    secondary = VolozColors.Olive,
    onSecondary = Color.White,
    tertiary = VolozColors.Olive,
    background = VolozColors.Paper,
    onBackground = VolozColors.Ink,
    surface = VolozColors.Card,
    onSurface = VolozColors.Ink,
    surfaceVariant = VolozColors.Paper,
    onSurfaceVariant = VolozColors.TextTertiary,
    outline = VolozColors.FieldBorder,
    outlineVariant = VolozColors.Divider,
)

/** Light only for the prototype: the spec defines a single (paper) palette. */
@Composable
fun VolozTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = Typography(
            bodyLarge = VolozType.Body,
            bodyMedium = VolozType.BodySmall,
            bodySmall = VolozType.Caption,
            labelLarge = VolozType.Label,
        ),
        shapes = Shapes(
            small = VolozShapes.Small,
            medium = VolozShapes.Field,
            large = VolozShapes.Card,
        ),
        content = content,
    )
}
