package com.zeroconvert.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.zeroconvert.ime.assist.InputPrivacy

/**
 * Maps the focused editor's declared input type to the keyboard mode it asks for.
 *
 * Android never declares "Japanese" or "English", so only the cases the platform
 * actually distinguishes are mapped. `null` means "no opinion": keep whatever mode
 * the user currently has, which is also the answer for every ordinary text field.
 *
 * Only `InputType` / `EditorInfo` constants are used, so this stays a pure function
 * that JVM unit tests can exercise without the Android framework.
 */
object InputTypeMode {
    fun modeFor(inputType: Int, imeOptions: Int): KeyboardMode? {
        // Passwords and no-learning fields keep the current mode: the keyboard must not
        // change under a sensitive field, and the user's choice there is theirs.
        if (InputPrivacy.isSensitive(inputType, imeOptions)) return null

        if (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0) return KeyboardMode.ENGLISH

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> KeyboardMode.NUMERIC

            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> KeyboardMode.ENGLISH

                // Normal text, FILTER, PHONETIC and the rest keep the current mode.
                else -> null
            }

            else -> null
        }
    }
}
