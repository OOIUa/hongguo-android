// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuDensityTest {
    @Test fun `sparse sits on the left and is looser`() {
        assertEquals("稀疏", DanmakuPrefs.densityLabel(0))
        assertEquals(1.6f, DanmakuPrefs.densityLinePitch(0), 0.001f)
        assertEquals(36f, DanmakuPrefs.densityGapDp(0), 0.001f)
    }

    @Test fun `standard keeps the original spacing`() {
        assertEquals("标准", DanmakuPrefs.densityLabel(DanmakuPrefs.DENSITY_STANDARD))
        assertEquals(1.12f, DanmakuPrefs.densityLinePitch(DanmakuPrefs.DENSITY_STANDARD), 0.001f)
        assertEquals(24f, DanmakuPrefs.densityGapDp(DanmakuPrefs.DENSITY_STANDARD), 0.001f)
    }

    @Test fun `sparse rows are fewer than standard for the same band`() {
        // 行数 = 带高 ÷ 行距，稀疏行距是标准的 1.6/1.12 倍，同一块带子里的行数必然更少。
        val band = 1000f
        val ink = 80f
        val standard = band / (ink * DanmakuPrefs.densityLinePitch(DanmakuPrefs.DENSITY_STANDARD))
        val sparse = band / (ink * DanmakuPrefs.densityLinePitch(0))
        assertEquals(1.6f / 1.12f, standard / sparse, 0.01f)
    }

    @Test fun `out of range values are clamped to the ends`() {
        assertEquals(DanmakuPrefs.densityLinePitch(0), DanmakuPrefs.densityLinePitch(-3), 0.001f)
        assertEquals(DanmakuPrefs.densityLinePitch(DanmakuPrefs.DENSITY_STANDARD),
            DanmakuPrefs.densityLinePitch(99), 0.001f)
    }
}
