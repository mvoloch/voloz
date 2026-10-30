package io.github.mvoloch.voloz.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.mvoloch.voloz.android.VolozApp
import io.github.mvoloch.voloz.android.data.VolozSettings
import io.github.mvoloch.voloz.android.dictation.BubbleState
import io.github.mvoloch.voloz.android.dictation.InsertOutcome
import io.github.mvoloch.voloz.android.dictation.InsertResult
import io.github.mvoloch.voloz.android.dictation.TextInserter
import io.github.mvoloch.voloz.android.overlay.BubbleOverlay
import io.github.mvoloch.voloz.android.overlay.BubblePositionStore
import io.github.mvoloch.voloz.android.text.FieldPolicy
import io.github.mvoloch.voloz.android.text.TextMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tracks the focused text field (only *which* field and whether the keyboard
 * is up; screen text is never read beyond the target field's own contents at
 * insertion time), owns the bubble overlay, and inserts dictated text.
 */
class VolozAccessibilityService : AccessibilityService(), TextInserter {
    companion object {
        private const val TAG = "VolozA11y"
        private const val FOCUS_DEBOUNCE_MS = 80L
        private const val CLIPBOARD_RESTORE_MS = 800L

        private val _connected = MutableStateFlow(false)
        /** True while the system has the service bound (i.e. it is enabled and running). */
        val connected: StateFlow<Boolean> = _connected
    }

    /** Where the bubble may appear right now. */
    private data class FocusInfo(val eligibleField: Boolean, val passwordLike: Boolean, val imeVisible: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val focus = MutableStateFlow(FocusInfo(eligibleField = false, passwordLike = false, imeVisible = false))
    private var lastTarget: AccessibilityNodeInfo? = null
    private var bubble: BubbleOverlay? = null
    private val refreshFocus = Runnable { refreshFocusNow() }

    private val app get() = application as VolozApp

    override fun onServiceConnected() {
        super.onServiceConnected()
        val controller = app.controller
        val overlay = BubbleOverlay(this, BubblePositionStore(this), object : BubbleOverlay.Listener {
            override fun onTap() = controller.onTap()
            override fun onHoldStart() = controller.onHoldStart()
            override fun onHoldEnd() = controller.onHoldEnd()
        })
        bubble = overlay
        controller.inserter = this
        controller.onUserMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        _connected.value = true

        scope.launch {
            combine(focus, app.settings.settings, controller.state) { f, s, st -> Triple(f, s ?: VolozSettings(), st) }
                .collect { (f, s, st) ->
                    overlay.setBubbleSizeDp(s.bubbleSize.dp)
                    overlay.setState(st)
                    val active = st != BubbleState.Idle
                    val visible = when {
                        active -> true // never vanish mid-dictation or while offering to paste
                        f.passwordLike -> false
                        s.onlyInTextFields -> f.eligibleField && f.imeVisible
                        else -> true
                    }
                    if (visible) overlay.show() else overlay.hide()
                }
        }
        refreshFocusNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        handler.removeCallbacks(refreshFocus)
        handler.postDelayed(refreshFocus, FOCUS_DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.relayout()
    }

    override fun onDestroy() {
        _connected.value = false
        handler.removeCallbacksAndMessages(null)
        bubble?.hide()
        bubble = null
        val controller = app.controller
        if (controller.inserter === this) {
            controller.inserter = null
            controller.onUserMessage = null
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- focus tracking -------------------------------------------------

    private fun refreshFocusNow() {
        val node = findFocusedEditable()
        val ime = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val eligible = node != null && FieldPolicy.isDictationTarget(node.inputType, node.isPassword)
        if (eligible) lastTarget = node
        focus.value = FocusInfo(
            eligibleField = eligible,
            passwordLike = node != null && !eligible,
            imeVisible = ime,
        )
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val all = runCatching { windows }.getOrDefault(emptyList())
        for (w in all) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) continue
            val f = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        return null
    }

    private fun currentTarget(): AccessibilityNodeInfo? {
        findFocusedEditable()?.let { return it }
        return lastTarget?.takeIf { runCatching { it.refresh() && it.isEditable }.getOrDefault(false) }
    }

    // ---- insertion ------------------------------------------------------

    override fun insert(text: String): InsertResult {
        val node = currentTarget() ?: return InsertResult(InsertOutcome.NO_TARGET, null)
        val pkg = node.packageName?.toString()
        if (!FieldPolicy.isDictationTarget(node.inputType, node.isPassword)) {
            return InsertResult(InsertOutcome.NO_TARGET, pkg)
        }
        if (setTextAtCursor(node, text)) return InsertResult(InsertOutcome.SET_TEXT, pkg)
        return if (pasteViaClipboard(node, text)) {
            InsertResult(InsertOutcome.PASTED, pkg)
        } else {
            InsertResult(InsertOutcome.FAILED, pkg)
        }
    }

    /**
     * ACTION_SET_TEXT replaces the whole field, so the new value is the old one
     * with the dictation merged in at the selection. Only used when the field's
     * text and cursor are known; otherwise paste (which the app inserts at its
     * own cursor) is safer than guessing and overwriting.
     */
    private fun setTextAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val raw = node.text?.toString().orEmpty()
        val showingHint = node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())
        val existing = if (showingHint) "" else raw
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (existing.isNotEmpty() && (start < 0 || end < 0 || start > existing.length || end > existing.length)) {
            return false
        }
        val merged = TextMerge.merge(existing, start, end, text)
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged.text)
            },
        )
        if (!ok) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merged.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merged.cursor)
            },
        )
        return true
    }

    /**
     * Clipboard + ACTION_PASTE. The previous clip is restored afterwards when
     * Android lets us read it (Android 10+ often hides it from background apps).
     * On failure the dictation is deliberately left on the clipboard.
     */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java) ?: return false
        val previous = runCatching { cm.primaryClip }.getOrNull()
        val raw = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val piece = if (!node.isShowingHintText && start in 0..raw.length && end in 0..raw.length) {
            TextMerge.withSpacing(raw.substring(0, minOf(start, end)), text, raw.substring(maxOf(start, end)))
        } else {
            text.trim()
        }
        cm.setPrimaryClip(ClipData.newPlainText("Voloz", piece))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok && previous != null) {
            handler.postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, CLIPBOARD_RESTORE_MS)
        }
        if (!ok) Log.i(TAG, "paste refused by ${node.packageName}")
        return ok
    }

    override fun pasteClipboard(): Boolean {
        val node = currentTarget() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
