package com.lioravrahami.souschef.cooking

import com.lioravrahami.souschef.ui.cooking.AutoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSizeTest {
    @Test
    fun shrinksByTenPercentButNeverBelowTheMinimum() {
        assertEquals(39.6f, AutoSize.nextSize(44f, 26f)!!, 1e-4f)
        assertEquals(26f, AutoSize.nextSize(27f, 26f)!!, 1e-4f)
        assertNull(AutoSize.nextSize(26f, 26f))
    }

    @Test
    fun candidatesRunFromDisplaySmallDownToTitleLarge() {
        val sizes = AutoSize.candidates(44f, 26f)
        assertEquals(44f, sizes.first(), 0f)
        assertEquals(26f, sizes.last(), 0f)
        assertTrue(sizes.zipWithNext().all { (a, b) -> b < a })
        // A handful of layout passes at most.
        assertTrue(sizes.size <= 7)
    }

    @Test
    fun candidatesWhenMaxIsAlreadyMinimal() {
        assertEquals(listOf(26f), AutoSize.candidates(20f, 26f))
    }

    @Test
    fun lineHeightKeepsTheStyleRatio() {
        assertEquals(26f * 52f / 44f, AutoSize.lineHeightFor(26f, 44f, 52f), 1e-4f)
        assertEquals(24f, AutoSize.lineHeightFor(20f, 0f, 0f), 1e-4f)
    }
}
