package com.example.goforitGit.count_step_module

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData




class StepRepository private constructor(app: Application) {
    private val store = StepHistoryStore(app)

    // Live, lifecycle-aware streams for UI
    val stepsLD: LiveData<Int> =
        StepBus.steps.asLiveData()        // from service -> bus -> repo  :contentReference[oaicite:4]{index=4}
    val modeLD: LiveData<StepCounterZC.MotionMode> =
        StepBus.mode.asLiveData()         // same idea                     :contentReference[oaicite:5]{index=5}

    // Derived values that don’t need constant recompute
    val cadenceSpmLD: LiveData<Int> = liveData {
        // Recompute only when someone is observing; trigger on step changes
        StepBus.steps.collect {
            emit(computeAvgSpm())
        }
    }

    val sensorsLD: LiveData<StepBus.SensorSnapshot> = StepBus.sensors.asLiveData()

    fun computeAvgSpm(): Int {
        val times = store.loadStepTimestamps()
        if (times.size < 4) return 0
        val now = System.currentTimeMillis()
        val windowMs = 120_000L
        val win = times.filter { it >= now - windowMs }
        if (win.size < 4) return 0
        val spanMs = (win.last() - win.first()).coerceAtLeast(5_000L)
        return (((win.size - 1) * 60_000f) / spanMs).coerceIn(0f, 240f).toInt()
    }

    fun stepsToday(): Int {
        val times = store.loadStepTimestamps()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        return times.count { it >= start }
    }

    companion object {
        @Volatile private var INSTANCE: StepRepository? = null
        fun get(app: Application): StepRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(app).also { INSTANCE = it }
            }
    }
}