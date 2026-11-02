package io.github.mvoloch.voloz.android.text

/** Result of inserting dictated text into a field: the full new text and where the cursor goes. */
data class Merge(val text: String, val cursor: Int)

/**
 * Pure insertion of dictated text at a cursor/selection. Existing text is kept;
 * a selection is replaced (like typing over it). Spacing is fixed up at both
 * seams so "hello|world" + "there" gives "hello there world".
 */
object TextMerge {
    private const val NO_SPACE_AFTER = "([{\"'“‘¿¡/@#\n\t"
    private const val NO_SPACE_BEFORE = ",.;:!?)]}\"'”’%…"

    /**
     * [selStart]/[selEnd] are the field's selection; a negative or out-of-range
     * value means "unknown" and the text is appended at the end.
     */
    fun merge(existing: String, selStart: Int, selEnd: Int, insert: String): Merge {
        val len = existing.length
        var s = selStart
        var e = selEnd
        if (s < 0 || e < 0 || s > len || e > len) {
            s = len
            e = len
        }
        if (s > e) s = e.also { e = s }
        val before = existing.substring(0, s)
        val after = existing.substring(e)
        val piece = withSpacing(before, insert, after)
        return Merge(before + piece + after, before.length + piece.length)
    }

    /** True when the reported selection is a real position inside a text of [length]. */
    fun selectionFits(length: Int, selStart: Int, selEnd: Int): Boolean =
        selStart in 0..length && selEnd in 0..length

    /** The dictated text trimmed and padded with the spaces its neighbours need. */
    fun withSpacing(before: String, insert: String, after: String): String {
        var t = insert.trim()
        if (t.isEmpty()) return ""
        if (before.isNotEmpty()) {
            val prev = before.last()
            if (!prev.isWhitespace() && prev !in NO_SPACE_AFTER && t.first() !in NO_SPACE_BEFORE) t = " $t"
        }
        if (after.isNotEmpty()) {
            val next = after.first()
            if (!next.isWhitespace() && next !in NO_SPACE_BEFORE && t.last() !in NO_SPACE_AFTER) t = "$t "
        }
        return t
    }
}
