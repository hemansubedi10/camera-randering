package com.aiprocamera.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Pro Gold
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private var targetX = 0f
    private var targetY = 0f
    private val ringRadius = 36f * resources.displayMetrics.density
    private val rect = RectF()

    init {
        visibility = GONE
    }

    fun showAt(x: Float, y: Float) {
        targetX = x
        targetY = y
        rect.set(x - ringRadius, y - ringRadius, x + ringRadius, y + ringRadius)

        alpha = 1f
        scaleX = 1.35f
        scaleY = 1.35f
        visibility = VISIBLE

        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .withEndAction {
                animate()
                    .alpha(0f)
                    .setStartDelay(800)
                    .setDuration(400)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            visibility = GONE
                        }
                    })
                    .start()
            }
            .start()

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility == VISIBLE) {
            canvas.drawCircle(targetX, targetY, ringRadius, paint)
            // Center crosshair tick
            val tick = 6f * resources.displayMetrics.density
            canvas.drawLine(targetX - tick, targetY, targetX + tick, targetY, paint)
            canvas.drawLine(targetX, targetY - tick, targetX, targetY + tick, paint)
        }
    }
}
