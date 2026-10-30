package io.github.mvoloch.voloz.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.ui.theme.LocalVolozPalette
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozDimens
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

// ---- brand ------------------------------------------------------------------

/** The Lite logo: four voice bars, the third one the red caret (mockup .logo svg, 18-unit box). */
@Composable
fun VolozMark(size: Dp = 18.dp, ink: Color = VolozColors.Ink, caret: Color = VolozColors.Signal) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 18f
        val bars = listOf(Triple(1f, 6f, 6f), Triple(5.2f, 3f, 12f), Triple(9.4f, 1f, 16f), Triple(13.6f, 5f, 8f))
        bars.forEachIndexed { i, (x, y, h) ->
            drawRoundRect(if (i == 2) caret else ink, Offset(x * u, y * u), Size(2.4f * u, h * u), CornerRadius(1.2f * u))
        }
    }
}

@Composable
fun VolozWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VolozMark()
        Text("voloz", style = VolozType.Wordmark, color = VolozColors.Ink)
    }
}

enum class Tab { HOME, SETTINGS }

/** Desktop LiteTopBar: wordmark and Início | Ajustes, a hairline below. */
@Composable
fun TopBar(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = VolozDimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolozWordmark()
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NavPill(stringResource(R.string.nav_home), tab == Tab.HOME) { onTab(Tab.HOME) }
                NavPill(stringResource(R.string.nav_settings), tab == Tab.SETTINGS) { onTab(Tab.SETTINGS) }
            }
        }
        Hairline()
    }
}

@Composable
private fun NavPill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 36.dp).clip(VolozShapes.Pill)
            .then(if (on) Modifier.background(VolozColors.Sheet).border(1.dp, VolozColors.Hair, VolozShapes.Pill) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VolozType.Label.copy(fontWeight = FontWeight.Medium), color = if (on) VolozColors.Ink else VolozColors.Graphite)
    }
}

/** "Groq · whisper-large-v3 → gpt-oss-120b" with a green dot: what dictation runs on right now. */
@Composable
fun ModelStatus(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (ok) VolozColors.Ok else VolozColors.Signal, 7.dp)
        Text(text, style = VolozType.Mono, color = VolozColors.Graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- buttons ----------------------------------------------------------------

/** Ink pill, mist text; [dot] = the red recording dot of the desktop's "Ditar aqui". */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dot: Boolean = false) {
    val ink = VolozColors.Ink
    Row(
        modifier.fillMaxWidth().height(VolozDimens.ButtonHeight).clip(VolozShapes.Pill)
            .background(if (enabled) ink else ink.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            RecDot()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = VolozType.Button, color = VolozColors.Mist)
    }
}

/** Sheet pill with a hairline (mockup .btn). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 40.dp) {
    Box(
        modifier.height(height).clip(VolozShapes.Pill).background(VolozColors.Sheet)
            .border(1.dp, VolozColors.Hair, VolozShapes.Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VolozType.Label, color = if (enabled) VolozColors.Ink else VolozColors.Faint)
    }
}

/** Secondary action under a primary button: ink text, 44dp touch target. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().heightIn(min = VolozDimens.MinTouch).clip(VolozShapes.Pill).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VolozType.Body.copy(fontWeight = FontWeight.Medium), color = VolozColors.Graphite)
    }
}

/** The red recording dot with its soft ring (mockup .rec). */
@Composable
fun RecDot(size: Dp = 8.dp) {
    val signal = VolozColors.Signal
    Box(Modifier.size(size + 6.dp).clip(CircleShape).background(signal.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(signal))
    }
}

/**
 * 44dp back target pulled 10dp into the left padding. The pull is a layout
 * shift, not an offset, so the next item in the row sits where it should.
 */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val pull = 10.dp.roundToPx()
                layout(p.width - pull, p.height) { p.place(-pull, 0) }
            }
            .size(VolozDimens.MinTouch),
    ) {
        Icon(VolozIcons.Back, contentDescription = stringResource(R.string.cd_back), tint = VolozColors.Ink, modifier = Modifier.size(22.dp))
    }
}

// ---- onboarding ---------------------------------------------------------------

/** Onboarding header: back arrow + 3-segment progress bar in ink. */
@Composable
fun StepHeader(step: Int, onBack: () -> Unit, total: Int = 3) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BackButton(onBack)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) VolozColors.Ink else VolozColors.Hair),
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, body: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = VolozType.Headline, color = VolozColors.Ink)
        if (body != null) Text(body, style = VolozType.BodyLarge, color = VolozColors.Graphite)
    }
}

/** White card with a hairline, 14dp radius (mockup .group). */
@Composable
fun VolozCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(VolozShapes.Card).background(VolozColors.Sheet)
            .border(1.dp, VolozColors.Hair, VolozShapes.Card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Simple HTML (<b>, <a href>) from strings.xml, links in ink and underlined. */
@Composable
fun htmlText(html: String): AnnotatedString = AnnotatedString.fromHtml(
    html,
    linkStyles = TextLinkStyles(style = SpanStyle(color = VolozColors.Ink, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
)

/** Numbered step: the desktop's rank circle (mockup .rank.main) + text. */
@Composable
fun NumberedStep(number: Int, html: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        RankBadge(number, main = true)
        Text(htmlText(html), style = VolozType.BodySmall, color = VolozColors.Ink, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = VolozType.Caption, color = VolozColors.Graphite, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 48dp field, 12dp radius, hairline border (ink when focused). */
@Composable
fun VolozTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = VolozType.Body,
    focusedBorder: Boolean = false,
    singleLine: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val ink = VolozColors.Ink
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = ink),
        cursorBrush = SolidColor(ink),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        modifier = modifier.heightIn(min = VolozDimens.FieldHeight),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = VolozDimens.FieldHeight)
                    .clip(VolozShapes.Field).background(VolozColors.Sheet)
                    .border(1.dp, if (focusedBorder) ink else VolozColors.Hair, VolozShapes.Field)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = textStyle, color = VolozColors.Faint)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * The bubble as a static picture (welcome, home, mic preview), drawn like the
 * desktop's notch pill: black in both themes, white bars, red dot only while
 * recording. [bars] are bar heights as a fraction of the bubble size; null =
 * flat idle bars.
 */
@Composable
fun BubbleGlyph(
    size: Dp,
    modifier: Modifier = Modifier,
    bars: List<Float>? = null,
    recDot: Boolean = false,
    elevated: Boolean = false,
) {
    val corner = size * (22f / 60f)
    val shape = RoundedCornerShape(corner)
    val palette = LocalVolozPalette.current
    val signal = palette.signal
    Box(
        modifier.size(size)
            .then(if (elevated) Modifier.shadow(size / 6, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape).background(VolozColors.Notch)
            // Black on the dark theme's near-black page: a rim keeps its outline (as the overlay's BubbleStyle.RIM).
            .then(if (palette.dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), shape) else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / 60f // scale vs the 60dp design bubble
            val barW = 4f * k
            val gap = 3f * k
            val heights = bars ?: List(5) { 6f / 60f }
            val total = heights.size * barW + (heights.size - 1) * gap
            var x = this.size.width / 2 - total / 2
            val cy = this.size.height / 2
            val barColor = if (bars == null) Color.White.copy(alpha = 0.3f) else Color.White
            for (frac in heights) {
                val h = frac * this.size.height
                drawRoundRect(barColor, Offset(x, cy - h / 2), Size(barW, h), CornerRadius(barW / 2, barW / 2))
                x += barW + gap
            }
            if (recDot) {
                val r = 9f * k / 2
                val inset = 7f * k
                drawCircle(signal, r, Offset(this.size.width - inset - r, inset + r))
            }
        }
    }
}

// ---- grouped settings (desktop SettingSection / SettingRow as grouped cards) -----

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(VolozColors.Hair))
}

/** Mono, uppercase, faint (mockup .gh / .day). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = VolozType.Eyebrow, color = VolozColors.Faint, modifier = modifier)
}

/** A section header + a white card whose rows are split by hairlines. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Eyebrow(title, Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(VolozShapes.Card).background(VolozColors.Sheet).border(1.dp, VolozColors.Hair, VolozShapes.Card),
            content = content,
        )
    }
}

/** Between two rows of a [SettingsGroup]. */
@Composable
fun GroupDivider() = Hairline()

/** One row: bold title, graphite subtitle, something on the right (mockup .grow). */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    subtitleStyle: TextStyle = VolozType.Caption,
    trailing: @Composable RowScope.() -> Unit = {},
) = RowLayout(title, subtitle, subtitleStyle, if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, trailing)

/** A [SettingRow] with the desktop's switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    RowLayout(title, subtitle, VolozType.Caption, Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange)) {
        Toggle(checked)
    }

@Composable
private fun RowLayout(
    title: String,
    subtitle: String?,
    subtitleStyle: TextStyle,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(modifier).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = VolozType.RowTitle, color = VolozColors.Ink)
            if (subtitle != null) Text(subtitle, style = subtitleStyle, color = VolozColors.Graphite)
        }
        trailing()
    }
}

/** Mockup .tog: ink track with a mist knob when on, hairline track when off. Drawn only; the row handles touch. */
@Composable
fun Toggle(checked: Boolean) {
    val track = if (checked) VolozColors.Ink else VolozColors.Hair
    val knob = if (checked) VolozColors.Mist else VolozColors.Sheet
    Box(Modifier.width(44.dp).height(26.dp).clip(VolozShapes.Pill).background(track)) {
        Box(
            Modifier.offset(x = if (checked) 21.dp else 3.dp, y = 3.dp).size(20.dp)
                .shadow(if (checked) 0.dp else 1.dp, CircleShape).clip(CircleShape).background(knob),
        )
    }
}

/** Rank circle: ink-filled for the main provider (mockup .rank / .rank.main). */
@Composable
fun RankBadge(number: Int, main: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (main) Modifier.background(VolozColors.Ink) else Modifier.border(1.dp, VolozColors.Hair, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = VolozType.Mono.copy(fontSize = 11.sp), color = if (main) VolozColors.Mist else VolozColors.Graphite)
    }
}

enum class PillTone { NEUTRAL, OK, WARN }

/** Small status pill (mockup .pill / .pill.ok / .pill.warn). */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val hair = VolozColors.Hair
    val (fg, border, bg) = when (tone) {
        PillTone.OK -> Triple(VolozColors.Ok, lerpColor(VolozColors.Ok, hair, 0.65f), Color.Transparent)
        PillTone.WARN -> Triple(VolozColors.Signal, lerpColor(VolozColors.Signal, hair, 0.65f), VolozColors.SignalSoft)
        PillTone.NEUTRAL -> Triple(VolozColors.Graphite, hair, Color.Transparent)
    }
    Box(
        modifier.clip(VolozShapes.Pill).background(bg).border(1.dp, border, VolozShapes.Pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = VolozType.Meta.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small square control (mockup .ctl span): ↑, ⋯. */
@Composable
fun SquareControl(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(VolozDimens.MinTouch).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(VolozShapes.Small).border(1.dp, VolozColors.Hair, VolozShapes.Small), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = VolozColors.Graphite, modifier = Modifier.size(16.dp))
        }
    }
}

/** Dashed "+ Adicionar…" row at the end of a group (mockup .add). */
@Composable
fun AddRow(text: String, onClick: () -> Unit) {
    val hair = VolozColors.Hair
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(hair, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        }
        Text(
            "+ $text", style = VolozType.Body.copy(fontWeight = FontWeight.Medium), color = VolozColors.Graphite,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Dashed rounded outline (vocabulary "+ Adicionar" chip). */
fun Modifier.dashedOutline(color: Color, radius: Dp = 999.dp): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = color,
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            cornerRadius = CornerRadius(minOf(radius.toPx(), size.height / 2)),
        )
    },
)
