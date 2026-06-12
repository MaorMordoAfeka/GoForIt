package com.example.goforitGit.feature.leaderboard.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.goforitGit.R
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth

class LeaderboardAdapter(
    private val onItemClick: (LeaderboardEntry) -> Unit = {}
) : RecyclerView.Adapter<LeaderboardAdapter.LeaderboardViewHolder>() {

    private val items = mutableListOf<LeaderboardEntry>()

    private val currentUid: String?
        get() = FirebaseAuth.getInstance().currentUser?.uid

    fun replaceAll(newItems: List<LeaderboardEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendPage(newItems: List<LeaderboardEntry>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LeaderboardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.feature_leaderboard_item, parent, false)
        return LeaderboardViewHolder(view)
    }

    override fun onBindViewHolder(holder: LeaderboardViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            currentUid = currentUid,
            onItemClick = onItemClick
        )
    }

    override fun getItemCount(): Int = items.size

    class LeaderboardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.cardRoot)
        private val viewAccent: View = itemView.findViewById(R.id.viewAccent)
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvUsername: TextView = itemView.findViewById(R.id.tvUid)
        private val tvPoints: TextView = itemView.findViewById(R.id.tvPoints)
        private val tvSteps: TextView = itemView.findViewById(R.id.tvSteps)
        private val tvBonus: TextView = itemView.findViewById(R.id.tvBonus)
        private val tvFaculty: TextView = itemView.findViewById(R.id.tvFaculty)

        fun bind(
            item: LeaderboardEntry,
            currentUid: String?,
            onItemClick: (LeaderboardEntry) -> Unit
        ) {
            val isCurrentUser = currentUid != null && currentUid == item.uid

            tvRank.text = "#${item.rank}"
            tvUsername.text = displayUsername(item, isCurrentUser)

            tvPoints.text = item.totalPoints.toString()
            tvSteps.text = item.totalSteps.toString()
            tvBonus.text = item.bonusPoints.toString()
            tvFaculty.text = item.faculty.ifBlank { "General" }

            val accentColor = when (item.rank) {
                1 -> Color.parseColor("#F2B233")
                2 -> Color.parseColor("#A8B0C4")
                3 -> Color.parseColor("#C9824A")
                else -> Color.parseColor("#883FA8")
            }

            viewAccent.setBackgroundColor(accentColor)
            tvRank.setTextColor(accentColor)

            if (isCurrentUser) {
                cardRoot.setCardBackgroundColor(Color.parseColor("#F7FFF9"))
                cardRoot.strokeColor = Color.parseColor("#4FB36A")
                cardRoot.strokeWidth = dp(itemView, 2)
            } else {
                cardRoot.setCardBackgroundColor(Color.WHITE)
                cardRoot.strokeColor = Color.parseColor("#E7E1F2")
                cardRoot.strokeWidth = dp(itemView, 1)
            }

            cardRoot.setOnClickListener { onItemClick(item) }
            cardRoot.contentDescription = if (isCurrentUser) {
                "Open your public leaderboard profile"
            } else {
                "Open competitor profile"
            }
        }

        private fun displayUsername(item: LeaderboardEntry, isCurrentUser: Boolean): String {
            val username = item.username.ifBlank { "Loading profile…" }
            return if (isCurrentUser) {
                if (item.username.isBlank()) "You" else "You • $username"
            } else {
                username
            }
        }

        private fun dp(view: View, value: Int): Int {
            return (value * view.resources.displayMetrics.density).toInt()
        }
    }
}
