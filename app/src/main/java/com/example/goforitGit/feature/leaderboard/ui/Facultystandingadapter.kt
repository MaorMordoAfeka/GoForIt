package com.example.goforitGit.feature.leaderboard.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.goforitGit.R
import com.example.goforitGit.feature.leaderboard.model.FacultyStanding
import com.google.android.material.card.MaterialCardView

/**
 * Renders the per-day faculty ranking. Optionally highlights the row that
 * matches the signed-in user's own faculty.
 */
class FacultyStandingAdapter(
    private val onItemClick: (FacultyStanding) -> Unit = {}
) : RecyclerView.Adapter<FacultyStandingAdapter.FacultyViewHolder>() {

    private val items = mutableListOf<FacultyStanding>()
    private var highlightFaculty: String? = null

    fun replaceAll(newItems: List<FacultyStanding>, highlight: String?) {
        items.clear()
        items.addAll(newItems)
        highlightFaculty = highlight?.trim()?.takeIf { it.isNotBlank() }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FacultyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.feature_faculty_standing_item, parent, false)
        return FacultyViewHolder(view)
    }

    override fun onBindViewHolder(holder: FacultyViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            highlightFaculty = highlightFaculty,
            onItemClick = onItemClick
        )
    }

    override fun getItemCount(): Int = items.size

    class FacultyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.cardFacultyRoot)
        private val viewAccent: View = itemView.findViewById(R.id.viewFacultyAccent)
        private val tvRank: TextView = itemView.findViewById(R.id.tvFacultyRank)
        private val tvName: TextView = itemView.findViewById(R.id.tvFacultyName)
        private val tvMembers: TextView = itemView.findViewById(R.id.tvFacultyMembers)
        private val tvPoints: TextView = itemView.findViewById(R.id.tvFacultyPoints)
        private val tvAvg: TextView = itemView.findViewById(R.id.tvFacultyAvg)
        private val tvSteps: TextView = itemView.findViewById(R.id.tvFacultySteps)
        private val tvYouTag: TextView = itemView.findViewById(R.id.tvFacultyYouTag)

        fun bind(
            item: FacultyStanding,
            highlightFaculty: String?,
            onItemClick: (FacultyStanding) -> Unit
        ) {
            val facultyName = item.faculty.ifBlank { "General" }

            tvRank.text = "#${item.rank}"
            tvName.text = facultyName
            tvMembers.text = membersLabel(item.memberCount)
            tvPoints.text = item.totalPoints.toString()
            tvAvg.text = item.averagePoints.toString()
            tvSteps.text = item.totalSteps.toString()

            val accentColor = when (item.rank) {
                1 -> Color.parseColor("#F2B233")
                2 -> Color.parseColor("#A8B0C4")
                3 -> Color.parseColor("#C9824A")
                else -> Color.parseColor("#883FA8")
            }
            viewAccent.setBackgroundColor(accentColor)
            tvRank.setTextColor(accentColor)

            val isMine = highlightFaculty != null &&
                    highlightFaculty.equals(facultyName, ignoreCase = true)

            if (isMine) {
                cardRoot.setCardBackgroundColor(Color.parseColor("#F7FFF9"))
                cardRoot.strokeColor = Color.parseColor("#4FB36A")
                cardRoot.strokeWidth = dp(itemView, 2)
                tvYouTag.visibility = View.VISIBLE
            } else {
                cardRoot.setCardBackgroundColor(Color.WHITE)
                cardRoot.strokeColor = Color.parseColor("#E7E1F2")
                cardRoot.strokeWidth = dp(itemView, 1)
                tvYouTag.visibility = View.GONE
            }

            cardRoot.setOnClickListener { onItemClick(item) }
        }

        private fun membersLabel(count: Int): String {
            return if (count == 1) "1 member" else "$count members"
        }

        private fun dp(view: View, value: Int): Int {
            return (value * view.resources.displayMetrics.density).toInt()
        }
    }
}