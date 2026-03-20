package com.example.goforitGit.feature.leaderboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.goforitGit.R
import com.example.goforitGit.feature.leaderboard.model.LeaderboardEntry

class LeaderboardAdapter : RecyclerView.Adapter<LeaderboardAdapter.LeaderboardViewHolder>() {

    private val items = mutableListOf<LeaderboardEntry>()

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
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class LeaderboardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvUid: TextView = itemView.findViewById(R.id.tvUid)
        private val tvPoints: TextView = itemView.findViewById(R.id.tvPoints)
        private val tvSteps: TextView = itemView.findViewById(R.id.tvSteps)
        private val tvBonus: TextView = itemView.findViewById(R.id.tvBonus)
        private val tvFaculty: TextView = itemView.findViewById(R.id.tvFaculty)

        fun bind(item: LeaderboardEntry) {
            tvRank.text = "#${item.rank}"
            tvUid.text = item.uid
            tvPoints.text = "Points: ${item.totalPoints}"
            tvSteps.text = "Steps: ${item.totalSteps}"
            tvBonus.text = "Bonus: ${item.bonusPoints}"
            tvFaculty.text = "Faculty: ${item.faculty.ifBlank { "-" }}"
        }
    }
}