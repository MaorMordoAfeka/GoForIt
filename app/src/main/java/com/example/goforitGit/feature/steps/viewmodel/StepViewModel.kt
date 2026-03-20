package com.example.goforitGit.feature.steps.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.goforitGit.core.data.StepsData.StepRepository
import com.example.goforitGit.core.data.StepsData.StepBus
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC

class StepViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StepRepository.Companion.get(app)
    val kmhLD: LiveData<Float?> = repo.kmhLD
    val sensorsData: LiveData<StepBus.SensorsSnapshot> = repo.sensorsLD
    val steps: LiveData<Int> = repo.stepsLD
    val mode: LiveData<StepCounterZC.MotionMode> = repo.modeLD
    val stepsToday: LiveData<Int> = repo.stepsTodayLD
    val spm: LiveData<Float> = repo.spmLD
}