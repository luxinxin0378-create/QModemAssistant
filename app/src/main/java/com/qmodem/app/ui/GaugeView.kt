package com.qmodem.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 弧形信号仪表：支持设置 [min,max] 与当前值、颜色。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var minValue = -140.0
    private var maxValue = -44.0
    private var value: Double? = null
    private var progressColor = 0xFF10B981.toInt()
    private val trackColor = 0x33808080

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f
    }

    private val rect = RectF()

    // 弧线从 150° 到 390°（即 -210°→30°），扫过 240°
    private val startAngle = 150f
    private val sweepAngle = 240f

    fun setValue(min: Double, max: Double, v: Double?, color: Int) {
        minValue = min
        maxValue = max
        value = v
        progressColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = w * 0.09f
        val pad = stroke / 2f + w * 0.04f

        // 底部留出开口空间
        rect.set(pad, pad, w - pad, h - pad * 0.2f)

        trackPaint.strokeWidth = stroke
        valuePaint.strokeWidth = stroke
        tickPaint.color = trackColor

        canvas.drawArc(rect, startAngle, sweepAngle, false, trackPaint.apply {
            color = trackColor
        })

        val v = value ?: return
        if (maxValue <= minValue) return
        var pct = ((v - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
        if (pct.isNaN()) pct = 0.0
        canvas.drawArc(rect, startAngle, (sweepAngle * pct).toFloat(), false, valuePaint.apply {
            color = progressColor
        })

        // 刻度点
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rOut = rect.width() / 2f
        for (i in 0..4) {
            val ang = Math.toRadians((startAngle + sweepAngle * i / 4.0))
            val x1 = (cx + (rOut - stroke * 0.55f) * Math.cos(ang)).toFloat()
            val y1 = (cy + (rect.height() / 2f - stroke * 0.55f) * Math.sin(ang)).toFloat()
            val x2 = (cx + (rOut - stroke * 0.15f) * Math.cos(ang)).toFloat()
            val y2 = (cy + (rect.height() / 2f - stroke * 0.15f) * Math.sin(ang)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }
}
