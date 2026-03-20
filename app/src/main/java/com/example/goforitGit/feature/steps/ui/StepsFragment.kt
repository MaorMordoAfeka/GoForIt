package com.example.goforitGit.feature.steps.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.goforitGit.R
import com.example.goforitGit.core.data.StepsData.StepRepository
import com.example.goforitGit.feature.leaderboard.ui.LeaderboardActivity
import com.example.goforitGit.feature.map.ui.MapAndRoutesActivity
import com.example.goforitGit.feature.profile.ui.ProfileActivity
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import java.util.Locale

class StepsFragment : Fragment() {

    private val vm: StepViewModel by viewModels()
    private val repo by lazy {
        StepRepository.get(requireActivity().application)
    }

    private fun f3(value: Float): String =
        String.format(Locale.US, "%.3f", value)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.feature_steps_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ---- Observe ViewModel (same code that was in MainActivity) ----

        vm.spm.observe(viewLifecycleOwner) { spm ->
            view.findViewById<TextView>(R.id.spmText).text =
                "SPM: ${spm.toInt()}"
        }

        vm.kmhLD.observe(viewLifecycleOwner) { speed ->
            view.findViewById<TextView>(R.id.KmH).text =
                "Current Km/H: ${if (speed != null) f3(speed * 3.6f) else "0.000"}"
        }

        vm.steps.observe(viewLifecycleOwner) { steps ->
            view.findViewById<TextView>(R.id.count).text = "Steps: $steps"
        }

        vm.mode.observe(viewLifecycleOwner) { mode ->
            view.findViewById<TextView>(R.id.modeText).text =
                "current mode: $mode"
        }

        vm.stepsToday.observe(viewLifecycleOwner) { n ->
            view.findViewById<TextView>(R.id.todayStepsVal).text = n.toString()
        }

        vm.sensorsData.observe(viewLifecycleOwner) {
            view.findViewById<TextView>(R.id.hzText).text =
                "emaHz: ${f3(it.emaHz.toFloat())}"
        }

        // ---- Button handlers ----

        view.findViewById<Button>(R.id.leaderboardBtn).setOnClickListener {
            startActivity(
                LeaderboardActivity.createIntent(
                    context = requireContext(),
                    dayKey = null
                )
            )
        }

        view.findViewById<Button>(R.id.btnOpenProfile).setOnClickListener {
            startActivity(
                Intent(requireContext(), ProfileActivity::class.java)
            )
        }

        view.findViewById<Button>(R.id.mapAndRoutesBtn).setOnClickListener {
            startActivity(
                Intent(requireContext(), MapAndRoutesActivity::class.java)
            )
        }

        view.findViewById<Button>(R.id.queryDurationBtn).setOnClickListener {
            val etInput = view.findViewById<EditText>(R.id.durMinutesInput)
            val tvLastDur = view.findViewById<TextView>(R.id.lastDurStepsVal)
            val tvAvgCadence = view.findViewById<TextView>(R.id.avgCadenceVal)
            val minutes = etInput.text.toString().trim().toIntOrNull()

            if (minutes == null || minutes <= 0) {
                tvLastDur.text = "0"
                tvAvgCadence.text = "0"
                Toast.makeText(requireContext(), "Enter minutes > 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvLastDur.text = repo.stepsInLastMinutes(minutes).toString()
            tvAvgCadence.text = repo.computeAvgStepsPerDuration(minutes.toLong()).toString()
        }
    }
}