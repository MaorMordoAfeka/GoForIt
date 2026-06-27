package com.example.goforitGit.feature.statistics.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.example.goforitGit.R
import com.example.goforitGit.core.data.StepsData.DailyStepsStore
import com.example.goforitGit.core.data.StepsData.StepRepository
import com.example.goforitGit.core.util.FourHourBuckets.FourHourBucketsSinceBoot
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC.MotionMode
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import com.example.goforitGit.navigation.DrawerNavigator
import com.google.android.material.appbar.MaterialToolbar
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only statistics screen.
 *
 *  - The hourly chart + "most active time" come from the four-hour buckets,
 *    which hold today's per-window step counts.
 *  - "Best day this week" / "Personal best" come from DailyStepsStore, which
 *    records one total per calendar day (built up over time).
 *  - Total steps / cadence / pace / mode come from the same StepRepository the
 *    home screen uses, so they stay in sync.
 */
class StatisticsActivity : AppCompatActivity() {

    private val vm: StepViewModel by viewModels()
    private val repo by lazy { StepRepository.get(application) }
    private val buckets by lazy { FourHourBucketsSinceBoot(applicationContext) }
    private val dailyStore by lazy { DailyStepsStore(applicationContext) }

    private val avgCadenceWindowMinutes = 60L
    private val zone = ZoneId.of("Asia/Jerusalem")

    private val bucketLabels = listOf("0-4", "4-8", "8-12", "12-16", "16-20", "20-24")

    private lateinit var chart: HourlyStepsChartView
    private lateinit var mostActiveView: TextView
    private lateinit var bestWeekSteps: TextView
    private lateinit var bestWeekDate: TextView
    private lateinit var personalBestSteps: TextView
    private lateinit var personalBestDate: TextView
    private lateinit var totalStepsView: TextView
    private lateinit var avgCadenceView: TextView

    @DrawableRes
    private fun MotionMode.iconRes(): Int = when (this) {
        MotionMode.UNKNOWN        -> R.drawable.ic_mode_unknown
        MotionMode.STATIONARY     -> R.drawable.ic_mode_stationary
        MotionMode.STANDING_STILL -> R.drawable.ic_mode_standing_still
        MotionMode.WALKING        -> R.drawable.ic_mode_walking
        MotionMode.RUNNING        -> R.drawable.ic_mode_running
        MotionMode.CYCLING        -> R.drawable.ic_mode_cycling
        MotionMode.DRIVING        -> R.drawable.ic_mode_driving
    }

    private fun formatCount(value: Int): String =
        String.format(Locale.US, "%,d", value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.feature_statistics_activity)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            DrawerNavigator.open(this)
        }

        chart = findViewById(R.id.hourlyChart)
        mostActiveView = findViewById(R.id.statMostActiveTime)
        bestWeekSteps = findViewById(R.id.statBestWeekSteps)
        bestWeekDate = findViewById(R.id.statBestWeekDate)
        personalBestSteps = findViewById(R.id.statPersonalBestSteps)
        personalBestDate = findViewById(R.id.statPersonalBestDate)
        totalStepsView = findViewById(R.id.statTotalSteps)
        avgCadenceView = findViewById(R.id.statAvgCadence)

        val paceView = findViewById<TextView>(R.id.statPace)

        vm.steps.observe(this) { total ->
            totalStepsView.text = formatCount(total)
            avgCadenceView.text =
                repo.computeAvgStepsPerDuration(avgCadenceWindowMinutes).toString()
            // Step total moved -> today's buckets/day-history may have changed.
            refreshDailyStats()
        }

        vm.kmhLD.observe(this) { speedMps ->
            val kmh = if (speedMps != null) speedMps * 3.6f else 0f
            paceView.text = String.format(Locale.US, "%.1f", kmh)
        }

        refreshDailyStats()
    }

    private fun refreshDailyStats() {
        val todayKey = buckets.getTodayKey()
        val values = buckets.getBucketsForDay(todayKey) ?: List(6) { 0 }

        chart.setData(values, bucketLabels)
        mostActiveView.text = mostActiveLabel(values)

        // Record today's running total so day-level history fills in over time.
        dailyStore.record(todayKey, values.sum())

        val week = dailyStore.bestDayInLast(7)
        if (week != null) {
            bestWeekSteps.text = formatCount(week.steps)
            bestWeekDate.text = formatDay(week.date)
        } else {
            bestWeekSteps.text = "0"
            bestWeekDate.text = "—"
        }

        val best = dailyStore.personalBest()
        if (best != null) {
            personalBestSteps.text = formatCount(best.steps)
            personalBestDate.text = formatDay(best.date)
        } else {
            personalBestSteps.text = "0"
            personalBestDate.text = "—"
        }
    }

    private fun mostActiveLabel(values: List<Int>): String {
        val maxValue = values.maxOrNull() ?: 0
        if (maxValue <= 0) return "Most active: no steps yet today"
        val idx = values.indexOf(maxValue)
        val start = idx * 4
        val end = start + 4
        return String.format(
            Locale.US,
            "Most active: %02d:00–%02d:00 (%s steps)",
            start, end, formatCount(maxValue)
        )
    }

    private fun formatDay(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
    }
}
