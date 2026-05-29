package com.example.goforitGit.feature.steps.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.R
import com.example.goforitGit.core.data.FirebaseData.FirebaseServerApi
import com.example.goforitGit.feature.leaderboard.ui.LeaderboardActivity
import com.example.goforitGit.feature.map.ui.MapAndRoutesActivity
import com.example.goforitGit.feature.profile.ui.ProfileActivity
import com.example.goforitGit.feature.statistics.ui.StatisticsActivity
import com.example.goforitGit.feature.steps.viewmodel.StepViewModel
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class StepsFragment : Fragment() {

    private val vm: StepViewModel by viewModels()

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

        view.findViewById<MaterialCardView>(R.id.cardHomeProfileImage).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        view.findViewById<Button>(R.id.statisticsBtn).setOnClickListener {
            startActivity(Intent(requireContext(), StatisticsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload so a photo changed in ProfileActivity is reflected on return.
        loadProfileAvatar()
    }

    private fun loadProfileAvatar() {
        val imageView = view?.findViewById<ImageView>(R.id.ivHomeProfileImage) ?: return
        if (FirebaseServerApi.currentUser() == null) {
            setDefaultAvatar(imageView)
            return
        }

        lifecycleScope.launch {
            FirebaseServerApi.getMyProfileResult()
                .onSuccess { profile ->
                    val url = profile.profileImageUrl
                    if (url.isBlank()) {
                        setDefaultAvatar(imageView)
                    } else {
                        val bitmap = withContext(Dispatchers.IO) { fetchBitmap(url) }
                        if (bitmap != null) setBitmapAvatar(imageView, bitmap)
                        else setDefaultAvatar(imageView)
                    }
                }
                .onFailure { setDefaultAvatar(imageView) }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            doInput = true
        }
        connection.connect()
        if (connection.responseCode in 200..299) {
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } else null
    } catch (_: Exception) {
        null
    }

    private fun setBitmapAvatar(imageView: ImageView, bitmap: Bitmap) {
        imageView.clearColorFilter()
        ImageViewCompat.setImageTintList(imageView, null)
        imageView.setPadding(0, 0, 0, 0)
        imageView.setImageBitmap(bitmap)
    }

    private fun setDefaultAvatar(imageView: ImageView) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        imageView.setPadding(pad, pad, pad, pad)
        imageView.setImageResource(R.drawable.ic_person)
        ImageViewCompat.setImageTintList(imageView, null)
        imageView.setColorFilter(Color.parseColor("#7C39A0"))
    }
}