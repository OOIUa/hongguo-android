// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only host for real media acceptance, without Compose's continuous loading animations. */
class PlaybackProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
    }
}
