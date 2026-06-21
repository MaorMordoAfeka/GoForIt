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
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Simple bar chart for the day's steps split into 4-hour windows.
 *
 * X axis  = time-of-day window (labels supplied by caller, e.g. "0-4").
 * Y axis  = steps. Auto-scales to the data and chooses a **"nice" tick step**
 *           (1 / 2 / 2.5 / 5 × 10ⁿ) so the gridline density stays around
 *           [TARGET_TICKS] (~6) regardless of magnitude — a few hundred steps
 *           draws a label every 50–200, while 30,000 steps draws a label every
 *           5,000. Minimum top of [MIN_MAX_Y] keeps an idle day legible.
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

        // Pick a "nice" tick step (1/2/2.5/5 × 10ⁿ) so the chart shows roughly
        // TARGET_TICKS labels no matter how high the step count climbs.
        val rawStep = max(maxValue.toDouble(), MIN_MAX_Y.toDouble()) / TARGET_TICKS
        val step = niceStep(rawStep).coerceAtLeast(1)
        var maxY = max(MIN_MAX_Y, ceil(maxValue.toDouble() / step).toInt() * step)
        if (maxY == maxValue) maxY += step  // one interval of headroom above the tallest bar

        // Gridlines + labels at every `step`.
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
            grid += step
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
        /** Aim for this many y-axis labels — adjusts as the value range grows. */
        private const val TARGET_TICKS = 6

        /** Minimum top of the y-axis so an idle day still draws a sensible scale. */
        private const val MIN_MAX_Y = 200

        /**
         * Round [raw] up to the next "nice" round number: 1, 2, 2.5, 5 × 10ⁿ.
         * Keeps tick labels human-readable across every order of magnitude.
         */
        private fun niceStep(raw: Double): Int {
            if (raw <= 0.0) return 1
            val magnitude = 10.0.pow(floor(log10(raw)))
            val normalized = raw / magnitude
            val niceNormalized = when {
                normalized <= 1.0 -> 1.0
                normalized <= 2.0 -> 2.0
                normalized <= 2.5 -> 2.5
                normalized <= 5.0 -> 5.0
                else              -> 10.0
            }
            return (niceNormalized * magnitude).toInt().coerceAtLeast(1)
        }
    }
}
