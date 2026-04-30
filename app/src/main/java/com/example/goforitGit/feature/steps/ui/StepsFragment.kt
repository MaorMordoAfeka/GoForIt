package com.example.goforitGit.feature.steps.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.goforitGit.R
import com.example.goforitGit.core.util.StepsUtils.StepCounterZC.MotionMode
import com.example.goforitGit.feature.leaderboard.ui.LeaderboardActivity
import com.example.goforitGit.feature.map.ui.MapAndRoutesActivity
import com.example.goforitGit.feature.profile.ui.ProfileActivity
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import java.util.Locale

class StepsFragment : Fragment() {

    private val vm: StepViewModel by viewModels()

    @DrawableRes
    fun MotionMode.iconRes(): Int = when (this) {
        MotionMode.UNKNOWN        -> R.drawable.ic_mode_unknown
        MotionMode.STATIONARY     -> R.drawable.ic_mode_stationary
        MotionMode.STANDING_STILL -> R.drawable.ic_mode_standing_still
        MotionMode.WALKING        -> R.drawable.ic_mode_walking
        MotionMode.RUNNING        -> R.drawable.ic_mode_running
        MotionMode.CYCLING        -> R.drawable.ic_mode_cycling
        MotionMode.DRIVING        -> R.drawable.ic_mode_driving
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

        vm.spm.observe(viewLifecycleOwner) { spm ->
            view.findViewById<TextView>(R.id.spmText).text = "SPM: ${spm.toInt()}"
        }

        vm.kmhLD.observe(viewLifecycleOwner) { speed ->
            view.findViewById<TextView>(R.id.KmH).text =
                "Km/H: ${if (speed != null) f3(speed * 3.6f) else "0.000"}"
        }

        vm.steps.observe(viewLifecycleOwner) { steps ->
            view.findViewById<TextView>(R.id.count).text = "Steps: $steps"
        }

        vm.mode.observe(viewLifecycleOwner) { mode ->
            view.findViewById<TextView>(R.id.modeText).text = "current mode: $mode"
            view.findViewById<ImageView>(R.id.modeIcon).setImageResource(mode.iconRes())
        }

        vm.stepsToday.observe(viewLifecycleOwner) { n ->
            view.findViewById<TextView>(R.id.todayStepsVal).text = n.toString()
        }

        view.findViewById<Button>(R.id.mapAndRoutesBtn).setOnClickListener {
            startActivity(Intent(requireContext(), MapAndRoutesActivity::class.java))
        }

        view.findViewById<Button>(R.id.leaderboardBtn).setOnClickListener {
            startActivity(
                LeaderboardActivity.createIntent(
                    context = requireContext(),
                    dayKey = null
                )
            )
        }

        view.findViewById<Button>(R.id.btnOpenProfile).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // statisticsBtn intentionally has no functionality yet
    }
}