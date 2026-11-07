package com.zeroconvert.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * InputTypeMode only reads InputType / EditorInfo constants, so these cases run on the JVM
 * with the default-value Android stubs.
 */
class InputTypeModeTest {
    @Test fun numberClassAsksForNumeric() {
        assertEquals(KeyboardMode.NUMERIC, InputTypeMode.modeFor(InputType.TYPE_CLASS_NUMBER, 0))
    }

    @Test fun phoneClassAsksForNumeric() {
        assertEquals(KeyboardMode.NUMERIC, InputTypeMode.modeFor(InputType.TYPE_CLASS_PHONE, 0))
    }

    @Test fun datetimeClassAsksForNumeric() {
        assertEquals(KeyboardMode.NUMERIC, InputTypeMode.modeFor(InputType.TYPE_CLASS_DATETIME, 0))
    }

    @Test fun plainTextKeepsCurrentMode() {
        assertNull(InputTypeMode.modeFor(InputType.TYPE_CLASS_TEXT, 0))
    }

    @Test fun uriVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0)
        )
    }

    @Test fun emailVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0
            )
        )
    }

    @Test fun webEmailVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, 0
            )
        )
    }

    @Test fun emailSubjectVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0
            )
        )
    }

    @Test fun personNameVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 0
            )
        )
    }

    @Test fun postalAddressVariationAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, 0
            )
        )
    }

    @Test fun forceAsciiFlagAsksForEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH,
            InputTypeMode.modeFor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_FORCE_ASCII)
        )
    }

    @Test fun textPasswordKeepsCurrentMode() {
        assertNull(
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0
            )
        )
    }

    @Test fun numberPasswordKeepsCurrentMode() {
        assertNull(
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0
            )
        )
    }

    @Test fun filterVariationKeepsCurrentMode() {
        assertNull(
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER, 0
            )
        )
    }

    @Test fun phoneticVariationKeepsCurrentMode() {
        assertNull(
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PHONETIC, 0
            )
        )
    }

    @Test fun nullInputTypeKeepsCurrentMode() {
        assertNull(InputTypeMode.modeFor(InputType.TYPE_NULL, 0))
    }

    @Test fun noPersonalizedLearningKeepsCurrentModeEvenForUri() {
        assertNull(
            InputTypeMode.modeFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            )
        )
    }
}
