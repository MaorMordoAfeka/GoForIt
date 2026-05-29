package com.example.goforitGit.feature.statistics.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Simple bar chart for the day's steps split into 4-hour windows.
 *
 * X axis  = time-of-day window (labels supplied by caller, e.g. "0-4").
 * Y axis  = steps. Auto-scales to the data, with a labelled gridline every
 *           [Y_INTERVAL] (100), and a minimum top of [MIN_MAX_Y] so there is
 *           always a 100 mark in the middle.
 *
 * Dependency-free (plain Canvas) so it matches the app's hand-built UI.
 */
class HourlyStepsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C39A0")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8D2E6")
        strokeWidth = dp(1f)
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B8197")
        textSize = dp(10f)
        textAlign = Paint.Align.RIGHT
    }
    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C5F7A")
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    fun setData(values: List<Int>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val leftPad = dp(40f)   // room for y labels like "10,000"
        val rightPad = dp(8f)
        val topPad = dp(10f)
        val bottomPad = dp(22f) // room for x labels

        val plotLeft = leftPad
        val plotRight = width - rightPad
        val plotTop = topPad
        val plotBottom = height - bottomPad
        val plotHeight = plotBottom - plotTop
        val plotWidth = plotRight - plotLeft
        if (plotHeight <= 0 || plotWidth <= 0) return

        val maxValue = values.maxOrNull() ?: 0
        // Auto-scale up to the next 100, with a floor so there's always a 100
        // mark in the middle, plus one interval of headroom above the tallest bar.
        var maxY = max(MIN_MAX_Y, ceil(maxValue.toDouble() / Y_INTERVAL).toInt() * Y_INTERVAL)
        if (maxY == maxValue) maxY += Y_INTERVAL

        // Gridlines + labels every 100.
        var grid = 0
        while (grid <= maxY) {
            val y = plotBottom - (grid.toFloat() / maxY) * plotHeight
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(
                String.format(Locale.US, "%,d", grid),
                plotLeft - dp(4f),
                y + dp(3.5f),
                axisLabelPaint
            )
            grid += Y_INTERVAL
        }

        // Bars.
        val n = values.size
        val slot = plotWidth / n
        val barWidth = slot * 0.55f
        for (i in 0 until n) {
            val v = values[i].coerceAtLeast(0)
            val barHeight = (v.toFloat() / maxY) * plotHeight
            val cx = plotLeft + slot * i + slot / 2f
            val barLeft = cx - barWidth / 2f
            val barRight = cx + barWidth / 2f
            val barTop = plotBottom - barHeight

            if (barHeight > 0f) {
                val rect = RectF(barLeft, barTop, barRight, plotBottom)
                val r = dp(4f)
                canvas.drawRoundRect(rect, r, r, barPaint)
            }

            val label = labels.getOrNull(i) ?: ""
            canvas.drawText(label, cx, plotBottom + dp(15f), xLabelPaint)
        }
    }

    companion object {
        private const val Y_INTERVAL = 100
        private const val MIN_MAX_Y = 200
    }
}
