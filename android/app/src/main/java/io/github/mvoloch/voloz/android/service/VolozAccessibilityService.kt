package io.github.mvoloch.voloz.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Rect
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
import io.github.mvoloch.voloz.android.overlay.BubbleAnchor
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
        /** Cap for the Compose focus fallback walk (a chat screen is a few hundred nodes). */
        private const val MAX_FOCUS_SCAN_NODES = 600
        /** Used only when no status-bar window is reported (e.g. immersive apps). */
        private const val STATUS_BAR_FALLBACK_DP = 24f

        private val _connected = MutableStateFlow(false)
        /** True while the system has the service bound (i.e. it is enabled and running). */
        val connected: StateFlow<Boolean> = _connected
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Where the bubble may appear right now, and where the field is (for placement). */
    private data class FocusInfo(
        val eligibleField: Boolean,
        val passwordLike: Boolean,
        val imeVisible: Boolean,
        val anchor: BubbleAnchor? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val focus = MutableStateFlow(FocusInfo(eligibleField = false, passwordLike = false, imeVisible = false))
    private var lastTarget: AccessibilityNodeInfo? = null
    private var bubble: BubbleOverlay? = null
    private val refreshFocus = Runnable { refreshFocusNow() }
    /** The field a cursor-move event came from, when only such events arrived since the last refresh. */
    private var selectionSource: AccessibilityNodeInfo? = null
    private var fullRefresh = true

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
            combine(focus, app.settings.settings, controller.state, controller.step) { f, s, st, step -> Quad(f, s ?: VolozSettings(), st, step) }
                .collect { (f, s, st, step) ->
                    overlay.setBubbleSizeDp(s.bubbleSize.dp)
                    overlay.setAnchor(f.anchor)
                    overlay.setStep(step)
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
        // Typing moves the cursor on every key: take the field from the event itself
        // instead of asking every window (each query is answered by the typing app).
        val source = if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            event.source?.takeIf { it.isEditable && it.isFocused }
        } else {
            null
        }
        if (source != null) selectionSource = source else fullRefresh = true
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
        // Without the service there is no bubble to stop a take: release the mic
        // (before hide(), which would end a hold and upload the take).
        app.controller.abortRecording()
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
        val wins = runCatching { windows }.getOrDefault(emptyList())
        val ime = wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val quick = selectionSource.takeIf { !fullRefresh }
        selectionSource = null
        fullRefresh = false
        // The tree walk runs only on focus/window events; without a keyboard no field is being typed in.
        val node = quick ?: findFocusedEditable(scanTree = ime)
        val eligible = node != null && FieldPolicy.isDictationTarget(node.inputType, node.isPassword)
        if (eligible) lastTarget = node
        focus.value = FocusInfo(
            eligibleField = eligible,
            passwordLike = node != null && !eligible,
            imeVisible = ime,
            anchor = if (eligible) anchorFor(node!!, wins) else null,
        )
    }

    /** The field's on-screen bounds plus the keyboard top and status-bar bottom. */
    private fun anchorFor(node: AccessibilityNodeInfo, wins: List<AccessibilityWindowInfo>): BubbleAnchor? {
        val field = Rect().also { node.getBoundsInScreen(it) }
        if (field.isEmpty) return null
        var imeTop: Int? = null
        var statusBottom = 0
        val b = Rect()
        for (w in wins) {
            w.getBoundsInScreen(b)
            when {
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !b.isEmpty ->
                    imeTop = minOf(imeTop ?: b.top, b.top)
                // The status bar: a thin system window along the top edge.
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM && b.top == 0 && b.height() < b.width() / 4 ->
                    statusBottom = maxOf(statusBottom, b.bottom)
            }
        }
        if (statusBottom == 0) statusBottom = (STATUS_BAR_FALLBACK_DP * resources.displayMetrics.density).toInt()
        return BubbleAnchor(field.top, field.bottom, imeTop, statusBottom)
    }

    private fun findFocusedEditable(scanTree: Boolean = true): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        active?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val all = runCatching { windows }.getOrDefault(emptyList())
        for (w in all) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) continue
            val f = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        // Jetpack Compose text fields are virtual nodes, and findFocus(FOCUS_INPUT)
        // does not return them (seen on API 34 with Compose BOM 2024.12, Voloz's own
        // "Teste aqui" field). Fall back to a bounded walk of the active window for
        // a node that is both focused and editable. Only flags are read, never text.
        return if (scanTree) active?.let { findFocusedEditableIn(it) } else null
    }

    private fun findFocusedEditableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_FOCUS_SCAN_NODES) {
            val n = queue.removeFirst()
            visited++
            if (n.isFocused && n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun currentTarget(): AccessibilityNodeInfo? {
        findFocusedEditable()?.let { return it }
        // Still on screen only: never drop text into a field the user has navigated away from.
        return lastTarget?.takeIf { runCatching { it.refresh() && it.isEditable && it.isVisibleToUser }.getOrDefault(false) }
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
     * ACTION_SET_TEXT replaces the whole field, so it is used only on an empty field
     * (or one showing its hint). A field with text gets a paste instead: the app
     * inserts at its own cursor, keeping its formatting, mentions and undo, which a
     * rewrite from the plain `node.text` would lose (or cut, where the app exposes
     * only part of its text).
     */
    private fun setTextAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val raw = node.text?.toString().orEmpty()
        val showingHint = node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())
        val existing = if (showingHint) "" else raw
        if (existing.isNotEmpty()) return false
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        // An empty `existing` with a cursor past 0 means the app hides its text
        // (rich editors, WebViews): SET_TEXT would wipe it, so paste instead.
        if (!TextMerge.selectionFits(existing.length, start, end)) return false
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

    override fun selectedText(): String = runCatching {
        val node = currentTarget() ?: return ""
        val raw = node.text?.toString().orEmpty()
        if (node.isShowingHintText) return ""
        val a = minOf(node.textSelectionStart, node.textSelectionEnd)
        val b = maxOf(node.textSelectionStart, node.textSelectionEnd)
        if (a < 0 || b > raw.length || a == b) "" else raw.substring(a, b)
    }.getOrDefault("")

    override fun targetPackage(): String? = runCatching { currentTarget()?.packageName?.toString() }.getOrNull()

    override fun pasteClipboard(): Boolean {
        val node = currentTarget() ?: return false
        if (!FieldPolicy.isDictationTarget(node.inputType, node.isPassword)) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
