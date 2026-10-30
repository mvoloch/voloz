package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.ui.components.BubbleGlyph
import io.github.mvoloch.voloz.android.ui.components.Footnote
import io.github.mvoloch.voloz.android.ui.components.NumberedStep
import io.github.mvoloch.voloz.android.ui.components.PrimaryButton
import io.github.mvoloch.voloz.android.ui.components.ScreenTitle
import io.github.mvoloch.voloz.android.ui.components.StatusDot
import io.github.mvoloch.voloz.android.ui.components.StepHeader
import io.github.mvoloch.voloz.android.ui.components.BackButton
import io.github.mvoloch.voloz.android.ui.components.TextLink
import io.github.mvoloch.voloz.android.ui.components.VolozCard
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozDimens
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

/** Result of the "Testar" button. */
sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Testing : KeyTestState
    data object Ok : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

/**
 * Shared frame for the onboarding screens: paper background, safe insets, scroll.
 * The content column is at least one viewport tall, so a `Modifier.weight(1f)`
 * spacer pins the CTAs to the bottom as in the mockups; on short screens (or
 * with the keyboard up) it simply scrolls.
 */
@Composable
private fun OnboardingFrame(top: Int = 28, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(VolozColors.Mist).safeDrawingPadding().imePadding(),
    ) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewport)
                    .padding(start = VolozDimens.ScreenPadding, end = VolozDimens.ScreenPadding, top = top.dp, bottom = 32.dp),
                content = content,
            )
        }
    }
}

// ---- 1. Boas-vindas ---------------------------------------------------------

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    OnboardingFrame(top = 56) {
        // Hero centred in the space above the CTA block (mockup: flex-grow + justify-center).
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BubbleGlyph(
                    size = 96.dp,
                    bars = listOf(18f, 32f, 44f, 28f, 14f).map { it / 96f },
                    elevated = true,
                )
                Text("voloz", style = VolozType.Wordmark.copy(fontSize = 34.sp), color = VolozColors.Ink)
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.welcome_headline), style = VolozType.HeadlineXL, color = VolozColors.Ink)
                Text(stringResource(R.string.welcome_body), style = VolozType.BodyLarge, color = VolozColors.Graphite)
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrimaryButton(stringResource(R.string.welcome_cta), onStart)
            Footnote(stringResource(R.string.welcome_footnote))
        }
    }
}

// ---- 2. Chave ---------------------------------------------------------------

/**
 * Groq key entry. [standalone] = opened from Ajustes ("Trocar"): no progress bar,
 * CTA saves and goes back.
 */
@Composable
fun KeyScreen(
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    testState: KeyTestState,
    onTest: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    OnboardingFrame {
        StepHeader(step = 1, onBack = onBack)
        Spacer(Modifier.height(28.dp))
        ScreenTitle(stringResource(R.string.key_title), stringResource(R.string.key_body))
        Spacer(Modifier.height(28.dp))
        VolozCard {
            NumberedStep(1, stringResource(R.string.key_step1))
            NumberedStep(2, stringResource(R.string.key_step2))
            NumberedStep(3, stringResource(R.string.key_step3))
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.key_label), style = VolozType.Label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VolozTextField(
                    value = keyInput,
                    onValueChange = onKeyInputChange,
                    placeholder = stringResource(R.string.key_placeholder),
                    textStyle = VolozType.MonoKey,
                    focusedBorder = focused,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                )
                OutlinedButton(
                    onClick = onTest,
                    enabled = keyInput.isNotBlank() && testState != KeyTestState.Testing,
                    shape = VolozShapes.Field,
                    border = androidx.compose.foundation.BorderStroke(1.dp, VolozColors.Ink),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VolozColors.Ink),
                    modifier = Modifier.height(VolozDimens.FieldHeight),
                ) {
                    Text(
                        stringResource(if (testState == KeyTestState.Testing) R.string.key_testing else R.string.key_test),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            when (testState) {
                KeyTestState.Ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(VolozIcons.Check, null, tint = VolozColors.Ok, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.key_ok), style = VolozType.BodySmall, color = VolozColors.Ok)
                }
                is KeyTestState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(VolozColors.Signal)
                    Text(testState.message, style = VolozType.BodySmall, color = VolozColors.Signal)
                }
                else -> Unit
            }
        }
        Spacer(Modifier.height(28.dp))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                stringResource(R.string.common_continue),
                onContinue,
                enabled = keyInput.isNotBlank(),
            )
            Footnote(stringResource(R.string.key_footnote))
        }
    }
}

// ---- 3. Microfone -----------------------------------------------------------

@Composable
fun MicScreen(micGranted: Boolean, onAllow: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    OnboardingFrame {
        StepHeader(step = 2, onBack = onBack)
        Spacer(Modifier.height(28.dp))
        ScreenTitle(stringResource(R.string.mic_title), stringResource(R.string.mic_body))
        // Notification preview centred in the free space (mockup: flex-grow + align-center).
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth()
                .clip(VolozShapes.CardLarge).background(VolozColors.Sheet)
                .border(1.dp, VolozColors.Hair, VolozShapes.CardLarge)
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BubbleGlyph(size = 52.dp, bars = listOf(10f, 18f, 24f, 16f, 9f).map { it / 52f }, recDot = true)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.notif_recording_title), style = VolozType.RowTitle, color = VolozColors.Ink)
                    Text(stringResource(R.string.notif_recording_text), fontSize = 14.sp, color = VolozColors.Graphite)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VolozColors.Hair))
            Text(stringResource(R.string.mic_preview_note), fontSize = 14.sp, lineHeight = 20.sp, color = VolozColors.Graphite)
        }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (micGranted) {
                PrimaryButton(stringResource(R.string.common_continue), onContinue)
            } else {
                PrimaryButton(stringResource(R.string.mic_cta), onAllow)
                TextLink(stringResource(R.string.mic_skip), onContinue)
            }
        }
    }
}

// ---- 4. Acessibilidade ------------------------------------------------------

@Composable
fun AccessibilityScreen(
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingFrame {
        StepHeader(step = 3, onBack = onBack)
        Spacer(Modifier.height(22.dp))
        ScreenTitle(stringResource(R.string.a11y_title), stringResource(R.string.a11y_body))
        Spacer(Modifier.height(22.dp))
        VolozCard(spacing = 14.dp) {
            NumberedStep(1, stringResource(R.string.a11y_step1))
            NumberedStep(2, stringResource(R.string.a11y_step2))
            // Not in the mockup, kept as the shortcut for step 2: an inline link
            // aligned with the step text rather than a centred button.
            Text(
                stringResource(R.string.a11y_open_app_info),
                fontSize = 15.sp,
                color = VolozColors.Ink,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(start = 38.dp).heightIn(min = VolozDimens.MinTouch)
                    .clickable(onClick = onOpenAppInfo).wrapContentHeight(Alignment.CenterVertically),
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(VolozIcons.Lock, null, tint = VolozColors.Graphite, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Text(stringResource(R.string.a11y_privacy), fontSize = 14.sp, lineHeight = 20.sp, color = VolozColors.Graphite)
        }
        Spacer(Modifier.height(22.dp))
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (enabled) VolozColors.Ok else VolozColors.Signal)
            Text(
                stringResource(if (enabled) R.string.a11y_status_on else R.string.a11y_status_off),
                fontSize = 14.sp,
                color = if (enabled) VolozColors.Ok else VolozColors.Signal,
            )
        }
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (enabled) {
                PrimaryButton(stringResource(R.string.a11y_done), onDone)
            } else {
                PrimaryButton(stringResource(R.string.a11y_cta), onOpenSettings)
                TextLink(stringResource(R.string.a11y_skip), onDone)
            }
        }
    }
}
