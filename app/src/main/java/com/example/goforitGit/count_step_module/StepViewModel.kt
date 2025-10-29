package com.example.goforitGit.count_step_module

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData




class StepViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StepRepository.get(app)

    val sensorsData: LiveData<StepBus.SensorSnapshot> = repo.sensorsLD
    val steps: LiveData<Int> = repo.stepsLD
    val mode: LiveData<StepCounterZC.MotionMode> = repo.modeLD
    val cadenceSpm: LiveData<Int> = repo.cadenceSpmLD

    // One-shot pulls for cards or “API panel”
    val stepsToday: LiveData<Int> = liveData { emit(repo.stepsToday()) }
}
