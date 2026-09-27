package com.homedungeon.ar.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.homedungeon.core.RayDistancePoint

/**
 * 屏幕层激光测距点阵 HUD：在屏幕上直观绘制各采样点的红外十字准星与对地实际米数
 */
class DistanceMatrixOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private var points: List<RayDistancePoint> = emptyList()

    fun updatePoints(newPoints: List<RayDistancePoint>) {
        points = newPoints
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (pt in points) {
            val px = pt.screenXNorm * w
            val py = pt.screenYNorm * h

            // 绘制小型十字瞄准线
            val crossSize = 12f
            canvas.drawLine(px - crossSize, py, px + crossSize, py, pointPaint)
            canvas.drawLine(px, py - crossSize, px, py + crossSize, pointPaint)

            // 打印测距文本
            val distText = if (pt.distanceMeters > 0f) {
                "${"%.2f".format(pt.distanceMeters)}m"
            } else {
                "--"
            }
            canvas.drawText(distText, px + 14f, py + 10f, textPaint)
        }
    }
}
