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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozDimens
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

/** Ink, 56dp tall, 16dp radius, paper text. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(VolozDimens.ButtonHeight),
        shape = VolozShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = VolozColors.Ink,
            contentColor = VolozColors.Paper,
            disabledContainerColor = VolozColors.Ink.copy(alpha = 0.3f),
            disabledContentColor = VolozColors.Paper,
        ),
    ) { Text(text, style = VolozType.Button) }
}

/** Secondary action: an olive text link with a 44dp touch target. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = VolozDimens.MinTouch),
        colors = ButtonDefaults.textButtonColors(contentColor = VolozColors.Olive),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Normal) }
}

/**
 * 44dp back target pulled 10dp into the left padding (mockup: margin-left -10px).
 * The pull is a layout shift, not an offset, so the next item in the row sits
 * where the mockup puts it instead of 10dp further right.
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

/** Onboarding header: back arrow + 3-segment olive progress bar. */
@Composable
fun StepHeader(step: Int, onBack: () -> Unit, total: Int = 3) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BackButton(onBack)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) VolozColors.Olive else VolozColors.ProgressTrack),
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, body: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = VolozType.Headline, color = VolozColors.Ink)
        if (body != null) Text(body, style = VolozType.Body, color = VolozColors.TextSecondary)
    }
}

/** White card, 20dp radius. */
@Composable
fun VolozCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(VolozShapes.Card).background(VolozColors.Card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Simple HTML (<b>, <a href>) from strings.xml, links olive and underlined. */
@Composable
fun htmlText(html: String): AnnotatedString = AnnotatedString.fromHtml(
    html,
    linkStyles = TextLinkStyles(style = SpanStyle(color = VolozColors.Olive, textDecoration = TextDecoration.Underline)),
)

/** Ink circle badge with a lime digit + text. */
@Composable
fun NumberedStep(number: Int, html: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(VolozColors.Ink), contentAlignment = Alignment.Center) {
            Text("$number", color = VolozColors.Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(htmlText(html), style = VolozType.BodySmall, color = VolozColors.Ink, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = VolozType.Caption, color = VolozColors.TextTertiary, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 52dp field, 14dp radius, 1.5dp border (olive when focused). */
@Composable
fun VolozTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = VolozType.Body,
    focusedBorder: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = VolozColors.Ink),
        cursorBrush = SolidColor(VolozColors.Olive),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        modifier = modifier.heightIn(min = VolozDimens.FieldHeight),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = VolozDimens.FieldHeight)
                    .clip(VolozShapes.Field).background(VolozColors.Card)
                    .border(1.5.dp, if (focusedBorder) VolozColors.Olive else VolozColors.FieldBorder, VolozShapes.Field)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = textStyle, color = VolozColors.TextTertiary)
                }
                inner()
            }
        },
    )
}

/**
 * The bubble as a static picture (welcome, home, mic preview). [bars] are bar
 * heights as a fraction of the bubble size; null = flat idle bars.
 */
@Composable
fun BubbleGlyph(
    size: Dp,
    modifier: Modifier = Modifier,
    bars: List<Float>? = null,
    recDot: Boolean = false,
    body: Color = VolozColors.Ink,
    elevated: Boolean = false,
) {
    val corner = size * (22f / 60f)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.size(size)
            .then(if (elevated) Modifier.shadow(size / 6, shape, ambientColor = VolozColors.Ink, spotColor = VolozColors.Ink) else Modifier)
            .clip(shape).background(body),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / 60f // scale vs the 60dp design bubble
            val barW = 4f * k
            val gap = 3f * k
            val heights = bars ?: List(5) { 6f / 60f }
            val total = heights.size * barW + (heights.size - 1) * gap
            var x = this.size.width / 2 - total / 2
            val cy = this.size.height / 2
            for (frac in heights) {
                val h = frac * this.size.height
                drawRoundRect(VolozColors.Lime, Offset(x, cy - h / 2), Size(barW, h), CornerRadius(barW / 2, barW / 2))
                x += barW + gap
            }
            if (recDot) {
                val r = 9f * k / 2
                val inset = 7f * k
                drawCircle(VolozColors.RecRed, r, Offset(this.size.width - inset - r, inset + r))
            }
        }
    }
}

/** Group header + white rounded group, as in Ajustes. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VolozColors.TextTertiary, modifier = Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().clip(VolozShapes.Group).background(VolozColors.Card), content = content)
    }
}

@Composable
fun GroupDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(VolozColors.Divider))
}

@Composable
fun Pill(text: String, background: Color, color: Color, modifier: Modifier = Modifier, dot: Color? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(VolozShapes.Pill).background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) StatusDot(dot, 7.dp)
        Text(text, fontSize = 13.sp, color = color)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
