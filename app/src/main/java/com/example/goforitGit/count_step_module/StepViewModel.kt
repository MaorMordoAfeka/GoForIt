package com.example.goforitGit.count_step_module

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData

class StepViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StepRepository.get(app)
    val kmhLD: LiveData<Float?> = repo.kmhLD
    val sensorsData: LiveData<StepBus.SensorsSnapshot> = repo.sensorsLD
    val steps: LiveData<Int> = repo.stepsLD
    val mode: LiveData<StepCounterZC.MotionMode> = repo.modeLD
    val stepsToday: LiveData<Int> = repo.stepsTodayLD
    val spm: LiveData<Float> = repo.spmLD
}
