// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弹幕的开关与设置图标，按参考图自制：一个圆角气泡框里收着「弹」字，右下角挂一枚角标。
 * 开关的角标是勾（关掉时换成斜杠），设置的角标是六边形齿轮，所以两个图标同源又一眼能分开。
 * 全部用矢量绘制，任意尺寸都清晰，也不需要额外的图片资源。
 */
@Composable
internal fun DanmakuGlyph(
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    settings: Boolean = false,
) {
    Canvas(modifier) {
        val side = size.minDimension
        val hair = side * 0.07f
        val frameLeft = side * 0.05f
        val frameSide = side * 0.72f

        drawRoundRect(
            color = tint,
            topLeft = Offset(frameLeft, frameLeft),
            size = Size(frameSide, frameSide),
            cornerRadius = CornerRadius(side * 0.14f, side * 0.14f),
            style = Stroke(width = hair),
        )
        // 字形交给系统字体渲染：比手写路径更准确，在 3x 屏幕上也不糊。
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                textSize = side * 0.42f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val centerX = frameLeft + frameSide / 2
            val centerY = frameLeft + frameSide / 2 - (paint.ascent() + paint.descent()) / 2
            canvas.nativeCanvas.drawText("弹", centerX, centerY, paint)
        }

        val badge = Offset(side * 0.795f, side * 0.795f)
        if (settings) {
            // 六边形加六个齿、中心留一个点：小尺寸下也能一眼认出是齿轮状的设置角标。
            val hexRadius = side * 0.15f
            val hex = Path()
            for (index in 0..5) {
                val angle = Math.toRadians((-90 + index * 60).toDouble())
                val x = badge.x + (hexRadius * cos(angle)).toFloat()
                val y = badge.y + (hexRadius * sin(angle)).toFloat()
                if (index == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
            }
            hex.close()
            drawPath(hex, color = tint, style = Stroke(width = hair * 0.85f))
            repeat(6) { index ->
                val angle = Math.toRadians((-90 + index * 60).toDouble())
                val from = Offset(badge.x + (hexRadius * cos(angle)).toFloat(), badge.y + (hexRadius * sin(angle)).toFloat())
                val to = Offset(badge.x + (side * 0.185f * cos(angle)).toFloat(), badge.y + (side * 0.185f * sin(angle)).toFloat())
                drawLine(color = tint, start = from, end = to, strokeWidth = hair * 0.85f, cap = StrokeCap.Round)
            }
            drawCircle(color = tint, radius = side * 0.045f, center = badge)
        } else {
            drawCircle(
                color = tint,
                radius = side * 0.17f,
                center = badge,
                style = if (enabled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = hair),
            )
            if (enabled) {
                // 勾用底色留白，圆形填充之上才不会糊成一团。
                val check = Path().apply {
                    moveTo(badge.x - side * 0.10f, badge.y + side * 0.005f)
                    lineTo(badge.x - side * 0.03f, badge.y + side * 0.075f)
                    lineTo(badge.x + side * 0.10f, badge.y - side * 0.065f)
                }
                drawPath(check, color = Color.White, style = Stroke(width = hair * 1.05f, cap = StrokeCap.Round))
            } else {
                drawLine(
                    color = tint,
                    start = Offset(badge.x - side * 0.11f, badge.y + side * 0.11f),
                    end = Offset(badge.x + side * 0.11f, badge.y - side * 0.11f),
                    strokeWidth = hair,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
