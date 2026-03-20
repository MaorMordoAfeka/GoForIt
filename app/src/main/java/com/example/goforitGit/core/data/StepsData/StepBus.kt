package com.example.goforitGit.core.data.StepsData

import com.example.goforitGit.core.util.StepsUtils.StepCounterZC
import kotlinx.coroutines.flow.MutableStateFlow

// In Kotlin, objects allow you to define a class and create an instance of it in a single step.
// This is useful when you need either a reusable singleton instance or a one-time object.
object StepBus {
    data class SensorsSnapshot(val emaHz: Double)
    val steps: MutableStateFlow<Int> = MutableStateFlow(0)
    val spm: MutableStateFlow<Float> = MutableStateFlow(0f)
    val mode: MutableStateFlow<StepCounterZC.MotionMode> = MutableStateFlow(StepCounterZC.MotionMode.UNKNOWN)
    val sensors: MutableStateFlow<SensorsSnapshot> = MutableStateFlow(SensorsSnapshot(0.0))
    val speedMps: MutableStateFlow<Float?> = MutableStateFlow(null)
}