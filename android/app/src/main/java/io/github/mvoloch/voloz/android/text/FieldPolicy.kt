package io.github.mvoloch.voloz.android.text

import android.text.InputType

/** Which focused fields get the bubble. Pure (InputType constants are compile-time ints). */
object FieldPolicy {
    /** False for passwords, PINs and numeric/phone/date fields: never dictate into those. */
    fun isDictationTarget(inputType: Int, isPassword: Boolean): Boolean {
        if (isPassword) return false
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        ) return false
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
        ) return false
        return true
    }
}
