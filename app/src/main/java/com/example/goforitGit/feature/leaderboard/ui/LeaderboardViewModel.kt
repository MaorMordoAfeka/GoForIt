package com.example.goforitGit.feature.leaderboard.ui

import androidx.lifecycle.ViewModel
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry

/**
 * Retains the leaderboard screen state while Android recreates
 * [LeaderboardActivity], for example after a screen rotation.
 *
 * Network requests remain owned by the Activity, but all successfully loaded
 * UI data and the user's current selections live here instead of in Activity
 * fields that would otherwise be reset during a configuration change.
 */
class LeaderboardViewModel : ViewModel() {

    var initialized: Boolean = false
    var hasLoadedData: Boolean = false

    var dayKey: String = ""
    var currentPageIndex: Int = 0
    var hasNextPage: Boolean = false
    var sortDescending: Boolean = false
    var selectedFaculty: String? = null

    var currentPageEntriesAsc: List<LeaderboardEntry> = emptyList()

    var pendingScrollUid: String? = null
    var pendingScrollRank: Int? = null

    /** Invalidates profile-enrichment work started for an older page or date. */
    var profileLoadGeneration: Int = 0

    /**
     * True once public-profile enrichment for the currently displayed rows has
     * finished (successfully or not).
     *
     * The row placeholders depend on this: "Loading profile…" is only honest
     * while a lookup is actually in flight. Once enrichment is done, a still
     * blank username means the player never set one, so the UI must fall back
     * to a stable label instead of pretending it is still loading.
     */
    var profilesResolved: Boolean = false

    // QA state is retained too, preventing a rotation from restarting or
    // reporting the same automated acceptance run twice.
    var qaMode: Boolean = false
    var qaExpectEmpty: Boolean = false
    var qaRunId: String = ""
    var qaStartedAtElapsedMs: Long = 0L
    var qaResultReported: Boolean = false
    var qaInitialLoadComplete: Boolean = false
    var qaInitialLoadSucceeded: Boolean = false
    var qaAwaitingProfileEnrichment: Boolean = false
}