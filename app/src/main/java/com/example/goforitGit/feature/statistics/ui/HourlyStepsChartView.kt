package com.example.goforitGit.feature.statistics.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.goforitGit.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Bar chart for today's steps split into 4-hour windows.
 *
 * X axis:
 * - 0–4, 4–8, 8–12, 12–16, 16–20, 20–24
 *
 * Y axis:
 * - Automatically chooses a readable "nice" interval based on the real values.
 * - Example for 52,027 steps:
 *   0 / 20k / 40k / 60k
 *
 * This avoids drawing hundreds of 100-step tick labels on top of each other.
 */
class HourlyStepsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()

    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private val barPurple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_purple)
    }

    private val barMint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_mint_strong)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = dp(1f)
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = dp(10f)
        textAlign = Paint.Align.RIGHT
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
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

        val leftPad = dp(48f)
        val rightPad = dp(8f)
        val topPad = dp(10f)
        val bottomPad = dp(24f)

        val plotLeft = leftPad
        val plotRight = width - rightPad
        val plotTop = topPad
        val plotBottom = height - bottomPad

        val plotHeight = plotBottom - plotTop
        val plotWidth = plotRight - plotLeft

        if (plotHeight <= 0f || plotWidth <= 0f) return

        val maxValue = values.maxOrNull()?.coerceAtLeast(0) ?: 0
        val axis = calculateAxis(maxValue)

        drawGrid(
            canvas = canvas,
            plotLeft = plotLeft,
            plotRight = plotRight,
            plotBottom = plotBottom,
            plotHeight = plotHeight,
            maxY = axis.maxY,
            interval = axis.interval
        )

        drawBars(
            canvas = canvas,
            plotLeft = plotLeft,
            plotBottom = plotBottom,
            plotHeight = plotHeight,
            plotWidth = plotWidth,
            maxY = axis.maxY
        )
    }

    private fun drawGrid(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        plotHeight: Float,
        maxY: Int,
        interval: Int
    ) {
        var value = 0

        while (value <= maxY) {
            val y = plotBottom - (value.toFloat() / maxY.toFloat()) * plotHeight

            canvas.drawLine(
                plotLeft,
                y,
                plotRight,
                y,
                gridPaint
            )

            canvas.drawText(
                formatAxisValue(value),
                plotLeft - dp(6f),
                y + dp(3.5f),
                axisLabelPaint
            )

            value += interval
        }
    }

    private fun drawBars(
        canvas: Canvas,
        plotLeft: Float,
        plotBottom: Float,
        plotHeight: Float,
        plotWidth: Float,
        maxY: Int
    ) {
        val count = values.size
        if (count == 0) return

        val slotWidth = plotWidth / count
        val barWidth = slotWidth * 0.55f

        for (index in values.indices) {
            val value = values[index].coerceAtLeast(0)
            val barHeight = (value.toFloat() / maxY.toFloat()) * plotHeight

            val centerX = plotLeft + slotWidth * index + slotWidth / 2f
            val barLeft = centerX - barWidth / 2f
            val barRight = centerX + barWidth / 2f
            val barTop = plotBottom - barHeight

            if (barHeight > 0f) {
                val rect = RectF(
                    barLeft,
                    barTop,
                    barRight,
                    plotBottom
                )

                val barPaint = if (index % 2 == 0) {
                    barPurple
                } else {
                    barMint
                }

                canvas.drawRoundRect(
                    rect,
                    dp(5f),
                    dp(5f),
                    barPaint
                )
            }

            val label = labels.getOrNull(index).orEmpty()

            canvas.drawText(
                label,
                centerX,
                plotBottom + dp(16f),
                xLabelPaint
            )
        }
    }

    /**
     * Produces a readable axis with roughly 3–5 labels.
     *
     * Examples:
     * - max 52,027 -> interval 20,000 -> maxY 60,000
     * - max 8,420  -> interval 2,000  -> maxY 10,000
     * - max 900    -> interval 250    -> maxY 1,000
     */
    private fun calculateAxis(maxValue: Int): AxisScale {
        if (maxValue <= 0) {
            return AxisScale(
                maxY = DEFAULT_MAX_Y,
                interval = DEFAULT_INTERVAL
            )
        }

        val targetIntervals = 4
        val roughInterval = maxValue.toDouble() / targetIntervals.toDouble()
        val interval = niceCeiling(roughInterval).toInt().coerceAtLeast(1)

        val maxY = (
                ceil(maxValue.toDouble() / interval.toDouble()).toInt() * interval
                ).coerceAtLeast(interval)

        return AxisScale(
            maxY = maxY,
            interval = interval
        )
    }

    /**
     * Rounds upward to a chart-friendly interval:
     * 1, 2, 2.5, 5, or 10 × a power of ten.
     */
    private fun niceCeiling(value: Double): Double {
        if (value <= 0.0) return 1.0

        val exponent = floor(log10(value))
        val power = 10.0.pow(exponent)
        val fraction = value / power

        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }

        return niceFraction * power
    }

    private fun formatAxisValue(value: Int): String {
        return when {
            value >= 1_000_000 -> {
                String.format(Locale.US, "%.1fM", value / 1_000_000f)
                    .replace(".0M", "M")
            }

            value >= 1_000 -> {
                String.format(Locale.US, "%.0fk", value / 1_000f)
            }

            else -> value.toString()
        }
    }

    private data class AxisScale(
        val maxY: Int,
        val interval: Int
    )

    companion object {
        private const val DEFAULT_MAX_Y = 200
        private const val DEFAULT_INTERVAL = 50
    }
}