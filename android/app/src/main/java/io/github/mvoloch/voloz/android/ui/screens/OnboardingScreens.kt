package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** Shared frame for the onboarding screens: paper background, safe insets, scroll. */
@Composable
private fun OnboardingFrame(top: Int = 28, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(VolozColors.Paper).safeDrawingPadding().imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = VolozDimens.ScreenPadding, end = VolozDimens.ScreenPadding, top = top.dp, bottom = 32.dp),
        ) { content() }
    }
}

// ---- 1. Boas-vindas ---------------------------------------------------------

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    OnboardingFrame(top = 56) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BubbleGlyph(
                    size = 96.dp,
                    bars = listOf(18f, 32f, 44f, 28f, 14f).map { it / 96f },
                    elevated = true,
                )
                Text(stringResource(R.string.app_name), style = VolozType.Wordmark.copy(fontSize = 30.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.welcome_headline), style = VolozType.HeadlineXL, color = VolozColors.Ink)
                Text(stringResource(R.string.welcome_body), style = VolozType.BodyLarge, color = VolozColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(48.dp))
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
    standalone: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    OnboardingFrame {
        if (standalone) BackButton(onBack) else StepHeader(step = 1, onBack = onBack)
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
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, VolozColors.Ink),
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
                    Icon(VolozIcons.Check, null, tint = VolozColors.Olive, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.key_ok), fontSize = 14.sp, color = VolozColors.OliveDark)
                }
                is KeyTestState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(VolozColors.Amber)
                    Text(testState.message, fontSize = 14.sp, color = VolozColors.AmberText)
                }
                else -> Unit
            }
        }
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                stringResource(if (standalone) R.string.key_save else R.string.common_continue),
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
        Spacer(Modifier.height(40.dp))
        Column(
            Modifier.fillMaxWidth().clip(VolozShapes.CardLarge).background(VolozColors.Card)
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BubbleGlyph(size = 52.dp, bars = listOf(10f, 18f, 24f, 16f, 9f).map { it / 52f }, recDot = true)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.notif_recording_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.notif_recording_text), fontSize = 14.sp, color = VolozColors.TextTertiary)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VolozColors.Divider))
            Text(stringResource(R.string.mic_preview_note), fontSize = 14.sp, lineHeight = 20.sp, color = VolozColors.TextSecondary)
        }
        Spacer(Modifier.height(40.dp))
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
            TextLink(stringResource(R.string.a11y_open_app_info), onOpenAppInfo)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(VolozIcons.Lock, null, tint = VolozColors.Olive, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Text(stringResource(R.string.a11y_privacy), fontSize = 14.sp, lineHeight = 20.sp, color = VolozColors.TextSecondary)
        }
        Spacer(Modifier.height(40.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (enabled) VolozColors.Olive else VolozColors.Amber)
            Text(
                stringResource(if (enabled) R.string.a11y_status_on else R.string.a11y_status_off),
                fontSize = 14.sp,
                color = if (enabled) VolozColors.OliveDark else VolozColors.AmberText,
            )
        }
        Spacer(Modifier.height(12.dp))
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
