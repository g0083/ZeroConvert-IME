package com.zeroconvert.ime.assist

import android.text.InputType
import android.view.inputmethod.EditorInfo

object InputPrivacy {
    /**
     * Fields whose content must suppress clipboard capture and hide clipboard/recent-emoji UI.
     * `TYPE_NULL` is excluded so losing editor focus (copying on webpages or non-input screens)
     * does not disable clipboard monitoring.
     */
    fun isPasswordInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun isPrivateInput(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        return isPasswordInput(inputType)
    }

    fun isSensitive(inputType: Int, imeOptions: Int): Boolean =
        isPrivateInput(inputType, imeOptions) || inputType == InputType.TYPE_NULL

    fun shouldAcceptClipboardEvent(
        privateMode: Boolean,
        clipTimestamp: Long,
        lastPrivateInputEndMillis: Long
    ): Boolean {
        if (privateMode) return false
        if (lastPrivateInputEndMillis > 0 && clipTimestamp in 1..lastPrivateInputEndMillis) return false
        return true
    }
}
