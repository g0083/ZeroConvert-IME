package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceInputPolicyTest {

    @Test
    fun defaultFollowsKeyboardMode() {
        assertEquals(
            SpaceInputPolicy.FULLWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.JAPANESE, alwaysHalfwidth = false)
        )
        assertEquals(
            SpaceInputPolicy.HALFWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.ENGLISH, alwaysHalfwidth = false)
        )
        assertEquals(
            SpaceInputPolicy.HALFWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.NUMERIC, alwaysHalfwidth = false)
        )
    }

    @Test
    fun alwaysHalfwidthOverridesJapaneseMode() {
        assertEquals(
            SpaceInputPolicy.HALFWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.JAPANESE, alwaysHalfwidth = true)
        )
        assertEquals(
            SpaceInputPolicy.HALFWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.ENGLISH, alwaysHalfwidth = true)
        )
        assertEquals(
            SpaceInputPolicy.HALFWIDTH_SPACE,
            SpaceInputPolicy.spaceText(KeyboardMode.NUMERIC, alwaysHalfwidth = true)
        )
    }
}
