package com.aiprocamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showLeveler: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showHistogram: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var rollAngle: Float = 0f
        set(value) {
            field = value
            if (showLeveler) invalidate()
        }

    var pitchAngle: Float = 0f
        set(value) {
            field = value
            if (showLeveler) invalidate()
        }

    private var histogramBins = IntArray(32)

    fun updateHistogram(bins: IntArray) {
        histogramBins = bins
        if (showHistogram) invalidate()
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // 25% white
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    private val levelerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val histoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80111111")
        style = Paint.Style.FILL
    }

    private val histoBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFD700") // Pro Gold
        style = Paint.Style.FILL
    }

    private val histoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // 1. Rule of Thirds Grid + Golden Ratio intersections
        if (showGrid) {
            val col1 = w / 3f
            val col2 = col1 * 2f
            val row1 = h / 3f
            val row2 = row1 * 2f

            canvas.drawLine(col1, 0f, col1, h, gridPaint)
            canvas.drawLine(col2, 0f, col2, h, gridPaint)
            canvas.drawLine(0f, row1, w, row1, gridPaint)
            canvas.drawLine(0f, row2, w, row2, gridPaint)
        }

        // 2. Pro Dual-Axis Horizon Spirit Leveler
        if (showLeveler) {
            val cx = w / 2f
            val cy = h / 2f
            val lineLength = 48f * resources.displayMetrics.density

            val isLevel = kotlin.math.abs(rollAngle) < 1.2f && kotlin.math.abs(pitchAngle) < 1.5f
            levelerPaint.color = if (isLevel) Color.parseColor("#00E676") else Color.parseColor("#80FFFFFF") // Green when level

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-rollAngle)

            // Dynamic horizon bar
            canvas.drawLine(-lineLength, 0f, -lineLength * 0.3f, 0f, levelerPaint)
            canvas.drawLine(lineLength * 0.3f, 0f, lineLength, 0f, levelerPaint)
            canvas.drawCircle(0f, 0f, 3.5f * resources.displayMetrics.density, levelerPaint)

            canvas.restore()
        }

        // 3. Real-Time Mini RGB / Luminance Histogram (Top right under Pro bar)
        if (showHistogram && histogramBins.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val histW = 90f * density
            val histH = 42f * density
            val left = w - histW - (16f * density)
            val top = 88f * density
            val rect = RectF(left, top, left + histW, top + histH)

            canvas.drawRoundRect(rect, 6f * density, 6f * density, histoBgPaint)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, histoStrokePaint)

            val maxVal = (histogramBins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            val numBins = histogramBins.size
            val barW = histW / numBins.toFloat()

            for (i in 0 until numBins) {
                val binH = (histogramBins[i].toFloat() / maxVal) * (histH - 4f)
                val bx = left + i * barW
                val by = (top + histH - 2f) - binH
                canvas.drawRect(bx, by, bx + barW - 0.5f, top + histH - 2f, histoBarPaint)
            }
        }
    }
}
