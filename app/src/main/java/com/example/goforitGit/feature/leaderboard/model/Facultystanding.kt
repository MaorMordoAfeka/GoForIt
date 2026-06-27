package com.example.goforitGit.feature.leaderboard.model

/**
 * One row of the per-day faculty ranking.
 *
 * Produced server-side by aggregating the individual leaderboard entries for a
 * day into one row per faculty (see writeFacultyStandingsForDay in the Cloud
 * Functions). Stored at:
 *   leaderboards_daily/{dayKey}/faculties/{facultyId}
 */
data class FacultyStanding(
    val faculty: String = "",
    val rank: Int = 0,
    val totalPoints: Int = 0,
    val totalSteps: Int = 0,
    val bonusPoints: Int = 0,
    val memberCount: Int = 0,
    val averagePoints: Int = 0
)