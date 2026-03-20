package com.example.goforitGit.feature.leaderboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.goforitGit.R
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.LocalDate
import java.time.ZoneId

class LeaderboardActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DAY_KEY = "extra_day_key"
        private const val PAGE_SIZE = 20

        fun createIntent(
            context: Context,
            dayKey: String? = null
        ): Intent {
            return Intent(context, LeaderboardActivity::class.java).apply {
                if (!dayKey.isNullOrBlank()) {
                    putExtra(EXTRA_DAY_KEY, dayKey)
                }
            }
        }
    }

    private lateinit var tvDayKey: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvPageInfo: TextView
    private lateinit var progressTop: ProgressBar
    private lateinit var progressBottom: ProgressBar
    private lateinit var recyclerView: RecyclerView

    private lateinit var btnPrevDay: Button
    private lateinit var btnToday: Button
    private lateinit var btnNextDay: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnMyRank: Button
    private lateinit var btnFirstPage: Button
    private lateinit var btnSort: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button

    private val adapter = LeaderboardAdapter()
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private var dayKey: String = ""
    private var isLoading = false
    private var currentPageIndex = 0
    private var hasNextPage = false
    private var sortDescending = false

    private var currentPageEntriesAsc: List<LeaderboardEntry> = emptyList()

    private var pendingScrollUid: String? = null
    private var pendingScrollRank: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.feature_leaderboard_activity)

        bindViews()

        dayKey = intent.getStringExtra(EXTRA_DAY_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: defaultLeaderboardDayKey()

        setupRecycler()
        setupButtons()
        updateControls()
        renderCurrentPage()

        loadPageByIndex(0, showTopLoader = true)
    }

    private fun bindViews() {
        tvDayKey = findViewById(R.id.tvDayKey)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        progressTop = findViewById(R.id.progressTop)
        progressBottom = findViewById(R.id.progressBottom)
        recyclerView = findViewById(R.id.recyclerLeaderboard)

        btnPrevDay = findViewById(R.id.btnPrevDay)
        btnToday = findViewById(R.id.btnToday)
        btnNextDay = findViewById(R.id.btnNextDay)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnMyRank = findViewById(R.id.btnMyRank)
        btnFirstPage = findViewById(R.id.btnFirstPage)
        btnSort = findViewById(R.id.btnSort)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
    }

    private fun setupRecycler() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnPrevDay.setOnClickListener { changeDayBy(-1) }
        btnToday.setOnClickListener { goToToday() }
        btnNextDay.setOnClickListener { changeDayBy(1) }

        btnRefresh.setOnClickListener { refreshCurrentPage() }

        btnMyRank.setOnClickListener { showMyRank() }

        btnFirstPage.setOnClickListener {
            if (currentPageIndex > 0) {
                loadPageByIndex(0, showTopLoader = true)
            }
        }

        btnSort.setOnClickListener {
            sortDescending = !sortDescending
            renderCurrentPage()
            updateControls()
        }

        btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                loadPageByIndex(currentPageIndex - 1, showTopLoader = false)
            }
        }

        btnNextPage.setOnClickListener {
            if (hasNextPage) {
                loadPageByIndex(currentPageIndex + 1, showTopLoader = false)
            }
        }
    }

    private fun defaultLeaderboardDayKey(minusDays: Int = 0): String {
        return LocalDate.now(ZoneId.systemDefault())
            .minusDays(minusDays.toLong())
            .toString()
    }

    private fun todayKey(): String {
        return LocalDate.now(ZoneId.systemDefault()).toString()
    }

    private fun changeDayBy(days: Long) {
        val current = LocalDate.parse(dayKey)
        val next = current.plusDays(days)
        val today = LocalDate.parse(todayKey())

        if (next.isAfter(today)) return

        dayKey = next.toString()
        currentPageIndex = 0
        pendingScrollUid = null
        pendingScrollRank = null
        loadPageByIndex(0, showTopLoader = true)
    }

    private fun goToToday() {
        val today = todayKey()
        if (dayKey == today) return

        dayKey = today
        currentPageIndex = 0
        pendingScrollUid = null
        pendingScrollRank = null
        loadPageByIndex(0, showTopLoader = true)
    }

    private fun refreshCurrentPage() {
        loadPageByIndex(currentPageIndex, showTopLoader = true)
    }

    private fun leaderboardQuery(): Query {
        return firestore.collection("leaderboards_daily")
            .document(dayKey)
            .collection("entries")
            .orderBy("rank", Query.Direction.ASCENDING)
    }

    private fun loadPageByIndex(
        pageIndex: Int,
        showTopLoader: Boolean
    ) {
        if (isLoading || pageIndex < 0) return

        isLoading = true
        progressTop.visibility = if (showTopLoader) View.VISIBLE else View.GONE
        progressBottom.visibility = if (showTopLoader) View.GONE else View.VISIBLE
        updateControls()

        val startRank = pageIndex * PAGE_SIZE + 1L

        var query = leaderboardQuery()
        if (pageIndex > 0) {
            query = query.startAt(startRank)
        }

        query.limit(PAGE_SIZE.toLong() + 1L)
            .get()
            .addOnSuccessListener { snapshot ->
                val docsWithPeek = snapshot.documents
                hasNextPage = docsWithPeek.size > PAGE_SIZE

                val pageDocs = docsWithPeek.take(PAGE_SIZE)
                currentPageEntriesAsc = pageDocs.mapNotNull { it.toLeaderboardEntryOrNull() }
                currentPageIndex = pageIndex

                renderCurrentPage()
                handlePendingScrollIfNeeded()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Failed to load leaderboard: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnCompleteListener {
                isLoading = false
                progressTop.visibility = View.GONE
                progressBottom.visibility = View.GONE
                updateControls()
            }
    }

    private fun renderCurrentPage() {
        val displayItems = if (sortDescending) {
            currentPageEntriesAsc.sortedByDescending { it.rank }
        } else {
            currentPageEntriesAsc
        }

        adapter.replaceAll(displayItems)
        recyclerView.scrollToPosition(0)

        tvEmpty.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
        tvDayKey.text = "Day: $dayKey"
        tvPageInfo.text = buildPageInfo(currentPageEntriesAsc)
        btnSort.text = if (sortDescending) "Sort ↑" else "Sort ↓"
    }

    private fun buildPageInfo(itemsAsc: List<LeaderboardEntry>): String {
        val pageNumber = currentPageIndex + 1

        if (itemsAsc.isEmpty()) {
            return "Page $pageNumber"
        }

        val minRank = itemsAsc.minOf { it.rank }
        val maxRank = itemsAsc.maxOf { it.rank }
        return "Page $pageNumber • #$minRank - #$maxRank"
    }

    private fun updateControls() {
        tvDayKey.text = "Day: $dayKey"
        btnSort.text = if (sortDescending) "Sort ↑" else "Sort ↓"

        val today = LocalDate.parse(todayKey())
        val selectedDay = LocalDate.parse(dayKey)

        btnPrevDay.isEnabled = !isLoading
        btnToday.isEnabled = !isLoading && selectedDay != today
        btnNextDay.isEnabled = !isLoading && selectedDay.isBefore(today)

        btnRefresh.isEnabled = !isLoading
        btnMyRank.isEnabled = !isLoading
        btnFirstPage.isEnabled = !isLoading && currentPageIndex > 0
        btnSort.isEnabled = !isLoading && currentPageEntriesAsc.isNotEmpty()

        btnPrevPage.isEnabled = !isLoading && currentPageIndex > 0
        btnNextPage.isEnabled = !isLoading && hasNextPage
    }

    private fun showMyRank() {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            toast("No signed-in user found")
            return
        }

        val displayItems = if (sortDescending) {
            currentPageEntriesAsc.sortedByDescending { it.rank }
        } else {
            currentPageEntriesAsc
        }

        val localIndex = displayItems.indexOfFirst { it.uid == uid }
        if (localIndex >= 0) {
            recyclerView.scrollToPosition(localIndex)
            toast("Your rank is #${displayItems[localIndex].rank}")
            return
        }

        val entriesRef = firestore.collection("leaderboards_daily")
            .document(dayKey)
            .collection("entries")

        entriesRef.document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    jumpToRankDocument(doc)
                } else {
                    entriesRef.whereEqualTo("uid", uid)
                        .limit(1)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            val match = querySnapshot.documents.firstOrNull()
                            if (match != null) {
                                jumpToRankDocument(match)
                            } else {
                                toast("You are not ranked for $dayKey")
                            }
                        }
                        .addOnFailureListener { e ->
                            toast("Failed to load your rank: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                toast("Failed to load your rank: ${e.message}")
            }
    }

    private fun jumpToRankDocument(doc: DocumentSnapshot) {
        val uid = auth.currentUser?.uid
        val rank = doc.getLong("rank")?.toInt()

        if (uid.isNullOrBlank() || rank == null || rank <= 0) {
            toast("Your rank is not available yet")
            return
        }

        val targetPageIndex = (rank - 1) / PAGE_SIZE

        pendingScrollUid = uid
        pendingScrollRank = rank

        loadPageByIndex(
            pageIndex = targetPageIndex,
            showTopLoader = true
        )
    }

    private fun handlePendingScrollIfNeeded() {
        val uid = pendingScrollUid ?: return
        val rank = pendingScrollRank

        val displayItems = if (sortDescending) {
            currentPageEntriesAsc.sortedByDescending { it.rank }
        } else {
            currentPageEntriesAsc
        }

        val index = displayItems.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            recyclerView.scrollToPosition(index)
            if (rank != null) {
                toast("Jumped to your rank #$rank")
            }
        } else {
            toast("Your page was loaded, but your row was not found on it")
        }

        pendingScrollUid = null
        pendingScrollRank = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun DocumentSnapshot.toLeaderboardEntryOrNull(): LeaderboardEntry? {
        val uid = getString("uid") ?: id
        val rank = getLong("rank")?.toInt() ?: return null
        val totalPoints = getLong("totalPoints")?.toInt() ?: 0
        val totalSteps = getLong("totalSteps")?.toInt() ?: 0
        val bonusPoints = getLong("bonusPoints")?.toInt() ?: 0
        val faculty = getString("faculty").orEmpty()

        return LeaderboardEntry(
            uid = uid,
            rank = rank,
            totalPoints = totalPoints,
            totalSteps = totalSteps,
            bonusPoints = bonusPoints,
            faculty = faculty
        )
    }
}