package com.hongguo.android

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class MainlandPhoneTest {
    @Test fun autofilledCountryCodeIsDroppedAndLengthIsCapped() {
        assertEquals("13800138000", mainlandPhoneDigits("+86 138-0013-8000"))
        assertEquals("13800138000", mainlandPhoneDigits("138001380009"))
        assertEquals("86", mainlandPhoneDigits("86"))
    }

    @Test fun displayGroupsDigitsAndKeepsCursorMappingInBounds() {
        val phone = "13800138000"
        for (length in 0..phone.length) {
            val shown = MainlandPhoneTransformation.filter(AnnotatedString(phone.take(length)))
            for (offset in 0..length) {
                val moved = shown.offsetMapping.originalToTransformed(offset)
                assert(moved in 0..shown.text.length) { "original $offset of $length" }
                assertEquals(offset, shown.offsetMapping.transformedToOriginal(moved))
            }
            for (offset in 0..shown.text.length) assert(shown.offsetMapping.transformedToOriginal(offset) in 0..length)
        }
        assertEquals("138 0013 8000", MainlandPhoneTransformation.filter(AnnotatedString(phone)).text.text)
    }
}
