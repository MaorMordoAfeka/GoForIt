package com.example.goforitGit.feature.leaderboard.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
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

    private lateinit var cardList: View
    private lateinit var layoutPageButtons: View

    private lateinit var btnBack: ImageButton

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

    private lateinit var cardPodium: View
    private lateinit var cardPodiumFirst: MaterialCardView
    private lateinit var cardPodiumSecond: MaterialCardView
    private lateinit var cardPodiumThird: MaterialCardView

    private val adapter = LeaderboardAdapter(
        onItemClick = { entry -> openCompetitorProfile(entry) }
    )

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

    /** Prevents old async username/photo loads from overwriting a newer page/day. */
    private var profileLoadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.feature_leaderboard_activity)

        bindViews()

        dayKey = intent.getStringExtra(EXTRA_DAY_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: defaultLeaderboardDayKey()

        setupRecycler()
        setupButtons()
        updateControls()
        renderCurrentPage(scrollToTop = true)

        loadPageByIndex(0, showTopLoader = true)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)

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

        cardPodium = findViewById(R.id.cardPodium)
        cardPodiumFirst = findViewById(R.id.cardPodiumFirst)
        cardPodiumSecond = findViewById(R.id.cardPodiumSecond)
        cardPodiumThird = findViewById(R.id.cardPodiumThird)

        cardList = findViewById(R.id.cardList)
        layoutPageButtons = findViewById(R.id.layoutPageButtons)
    }

    private fun setupRecycler() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.isNestedScrollingEnabled = false
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }

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
            renderCurrentPage(scrollToTop = true)
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
        profileLoadGeneration++
        loadPageByIndex(0, showTopLoader = true)
    }

    private fun goToToday() {
        val today = todayKey()
        if (dayKey == today) return

        dayKey = today
        currentPageIndex = 0
        pendingScrollUid = null
        pendingScrollRank = null
        profileLoadGeneration++
        loadPageByIndex(0, showTopLoader = true)
    }

    private fun refreshCurrentPage() {
        profileLoadGeneration++
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

        val requestedDayKey = dayKey
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

                renderCurrentPage(scrollToTop = true)
                handlePendingScrollIfNeeded()
                enrichCurrentPageWithPublicProfiles(
                    entriesSnapshot = currentPageEntriesAsc,
                    expectedDayKey = requestedDayKey,
                    expectedPageIndex = pageIndex
                )
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

    private fun enrichCurrentPageWithPublicProfiles(
        entriesSnapshot: List<LeaderboardEntry>,
        expectedDayKey: String,
        expectedPageIndex: Int
    ) {
        val targets = entriesSnapshot
            .filter { it.uid.isNotBlank() }
            .filter { it.username.isBlank() || it.profileImageUrl.isBlank() || it.faculty.isBlank() }
            .distinctBy { it.uid }

        if (targets.isEmpty()) return

        val generation = ++profileLoadGeneration

        lifecycleScope.launch {
            val profilesByUid = targets
                .map { entry ->
                    async {
                        FirebaseServerApi.getPublicUserProfileResult(entry.uid).getOrNull()
                    }
                }
                .awaitAll()
                .filterNotNull()
                .associateBy { it.uid }

            if (generation != profileLoadGeneration) return@launch
            if (dayKey != expectedDayKey || currentPageIndex != expectedPageIndex) return@launch
            if (profilesByUid.isEmpty()) return@launch

            currentPageEntriesAsc = currentPageEntriesAsc.map { entry ->
                val profile = profilesByUid[entry.uid]
                if (profile == null) {
                    entry
                } else {
                    entry.copy(
                        username = profile.username.ifBlank { entry.username },
                        profileImageUrl = profile.profileImageUrl.ifBlank { entry.profileImageUrl },
                        faculty = profile.faculty.ifBlank { entry.faculty }
                    )
                }
            }

            renderCurrentPage(scrollToTop = false)
            updateControls()
        }
    }

    private fun renderCurrentPage(scrollToTop: Boolean) {
        val podiumEntries = podiumEntriesForCurrentPage()
        renderPodium(podiumEntries)

        val displayItems = recyclerEntriesForCurrentPage()

        val hasPodium = podiumEntries.isNotEmpty()
        val hasListItems = displayItems.isNotEmpty()

        adapter.replaceAll(displayItems)

        if (hasListItems) {
            cardList.visibility = View.VISIBLE
            if (scrollToTop) recyclerView.scrollToPosition(0)
        } else {
            cardList.visibility = View.GONE
        }

        layoutPageButtons.visibility =
            if (hasListItems || currentPageIndex > 0 || hasNextPage) View.VISIBLE else View.GONE

        tvEmpty.visibility =
            if (!hasPodium && !hasListItems) View.VISIBLE else View.GONE

        tvDayKey.text = "Day: $dayKey"
        tvPageInfo.text = buildPageInfo(currentPageEntriesAsc)
        btnSort.text = if (sortDescending) "Sort ↑" else "Sort ↓"
    }

    private fun podiumEntriesForCurrentPage(): List<LeaderboardEntry> {
        if (currentPageIndex != 0) return emptyList()
        return currentPageEntriesAsc.filter { it.rank in 1..3 }.sortedBy { it.rank }
    }

    private fun recyclerEntriesForCurrentPage(): List<LeaderboardEntry> {
        val withoutPodium = if (currentPageIndex == 0) {
            currentPageEntriesAsc.filter { it.rank > 3 }
        } else {
            currentPageEntriesAsc
        }

        return if (sortDescending) {
            withoutPodium.sortedByDescending { it.rank }
        } else {
            withoutPodium.sortedBy { it.rank }
        }
    }

    private fun renderPodium(entries: List<LeaderboardEntry>) {
        if (entries.isEmpty()) {
            cardPodium.visibility = View.GONE
            return
        }

        cardPodium.visibility = View.VISIBLE

        bindPodiumCard(
            card = cardPodiumFirst,
            entry = entries.firstOrNull { it.rank == 1 },
            badge = "🥇",
            accentColor = "#4FB36A",
            fallbackRank = "#1"
        )

        bindPodiumCard(
            card = cardPodiumSecond,
            entry = entries.firstOrNull { it.rank == 2 },
            badge = "🥈",
            accentColor = "#6F788E",
            fallbackRank = "#2"
        )

        bindPodiumCard(
            card = cardPodiumThird,
            entry = entries.firstOrNull { it.rank == 3 },
            badge = "🥉",
            accentColor = "#C9824A",
            fallbackRank = "#3"
        )
    }

    private fun bindPodiumCard(
        card: MaterialCardView,
        entry: LeaderboardEntry?,
        badge: String,
        accentColor: String,
        fallbackRank: String
    ) {
        if (entry == null) {
            card.visibility = View.INVISIBLE
            card.setOnClickListener(null)
            return
        }

        card.visibility = View.VISIBLE
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { openCompetitorProfile(entry) }

        val rankId = when (card.id) {
            R.id.cardPodiumFirst -> R.id.tvPodiumRank1
            R.id.cardPodiumSecond -> R.id.tvPodiumRank2
            else -> R.id.tvPodiumRank3
        }
        val badgeId = when (card.id) {
            R.id.cardPodiumFirst -> R.id.tvPodiumBadge1
            R.id.cardPodiumSecond -> R.id.tvPodiumBadge2
            else -> R.id.tvPodiumBadge3
        }
        val usernameId = when (card.id) {
            R.id.cardPodiumFirst -> R.id.tvPodiumUid1
            R.id.cardPodiumSecond -> R.id.tvPodiumUid2
            else -> R.id.tvPodiumUid3
        }
        val pointsId = when (card.id) {
            R.id.cardPodiumFirst -> R.id.tvPodiumPoints1
            R.id.cardPodiumSecond -> R.id.tvPodiumPoints2
            else -> R.id.tvPodiumPoints3
        }
        val facultyId = when (card.id) {
            R.id.cardPodiumFirst -> R.id.tvPodiumFaculty1
            R.id.cardPodiumSecond -> R.id.tvPodiumFaculty2
            else -> R.id.tvPodiumFaculty3
        }

        card.findViewById<TextView>(badgeId).text = badge
        card.findViewById<TextView>(rankId).apply {
            text = if (entry.rank > 0) "#${entry.rank}" else fallbackRank
            setTextColor(Color.parseColor(accentColor))
        }
        card.findViewById<TextView>(usernameId).text = podiumLabel(entry)
        card.findViewById<TextView>(pointsId).text = "${entry.totalPoints} pts"
        card.findViewById<TextView>(facultyId).text = entry.faculty.ifBlank { "General" }
    }

    private fun podiumLabel(entry: LeaderboardEntry): String {
        val isYou = auth.currentUser?.uid == entry.uid
        val username = entry.username.ifBlank { "Loading profile…" }

        return if (isYou) {
            if (entry.username.isBlank()) "You" else "You \n $username"
        } else {
            username
        }
    }

    private fun buildPageInfo(itemsAsc: List<LeaderboardEntry>): String {
        val pageNumber = currentPageIndex + 1

        if (itemsAsc.isEmpty()) {
            return "Page $pageNumber"
        }

        val minRank = itemsAsc.minOf { it.rank }
        val maxRank = itemsAsc.maxOf { it.rank }
        return "\nPage $pageNumber ranking range:\n\n" +
                "Min rank: #$minRank  -  Max rank: #$maxRank"
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

        btnSort.visibility = if (recyclerEntriesForCurrentPage().isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMyRank() {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            toast("No signed-in user found")
            return
        }

        val podiumHit = podiumEntriesForCurrentPage().firstOrNull { it.uid == uid }
        if (podiumHit != null) {
            openCompetitorProfile(podiumHit)
            return
        }

        val displayItems = recyclerEntriesForCurrentPage()
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

        val podiumHit = podiumEntriesForCurrentPage().firstOrNull { it.uid == uid }
        if (podiumHit != null) {
            toast("Jumped to your podium rank #${podiumHit.rank}")
            pendingScrollUid = null
            pendingScrollRank = null
            return
        }

        val displayItems = recyclerEntriesForCurrentPage()
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

    private fun openCompetitorProfile(entry: LeaderboardEntry) {
        startActivity(
            CompetitorProfileActivity.createIntent(
                context = this,
                entry = entry,
                dayKey = dayKey
            )
        )
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

        val faculty = firstNonBlank(
            getString("faculty"),
            getString("department")
        )

        val username = firstNonBlank(
            getString("username"),
            getString("displayName"),
            getString("name")
        )

        val profileImageUrl = firstNonBlank(
            getString("profileImageUrl"),
            getString("photoUrl"),
            getString("profilePhotoUrl")
        )

        return LeaderboardEntry(
            uid = uid,
            rank = rank,
            totalPoints = totalPoints,
            totalSteps = totalSteps,
            bonusPoints = bonusPoints,
            faculty = faculty,
            username = username,
            profileImageUrl = profileImageUrl
        )
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }
}
