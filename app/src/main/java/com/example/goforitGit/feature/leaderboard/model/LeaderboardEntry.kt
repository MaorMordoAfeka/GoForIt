package com.example.goforitGit.feature.leaderboard.model

data class LeaderboardEntry(
    val uid: String = "",
    val rank: Int = 0,
    val totalPoints: Int = 0,
    val totalSteps: Int = 0,
    val bonusPoints: Int = 0,
    val faculty: String = ""
)