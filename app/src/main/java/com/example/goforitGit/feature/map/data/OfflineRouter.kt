package com.example.goforitGit.feature.map.data

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.shapes.GHPoint
import com.uber.h3core.H3Core
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class RoutePrefs(
    val parks: Float,       // 0.0 to 1.0 (or your slider range)
    val residential: Float,
    val busy: Float,
    val maxKm: Float        // EXTRA distance budget in km
) {
    /**
     * Returns true if user has set any meaningful preference (not all sliders equal/zero)
     */
    fun hasPreference(): Boolean {
        val values = listOf(parks, residential, busy)
        val maxVal = values.maxOrNull() ?: 0f
        val minVal = values.minOrNull() ?: 0f
        // If there's at least 0.2 difference between max and min, user has a preference
        return (maxVal - minVal) >= 0.15f || maxVal >= 0.3f
    }
}

class OfflineRouter(private val ctx: Context) {

    companion object {
        private const val TAG = "OfflineRouter"
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val ready = AtomicBoolean(false)

    private var h3: H3Core? = null
    private var poiRepo: PoiRepository? = null
    private var hopper: GraphHopper? = null

    private val h3Res = 10

    // ---- Budget behavior tuning ----
    private val minUtilization = 0.85
    private val overshootSlackSmallMeters = 300.0
    private val overshootSlackLargeMeters = 700.0
    private val acceptGapSmallMeters = 300.0
    private val acceptGapLargeMeters = 600.0

    fun isReady(): Boolean = ready.get()

    fun initAsync(onDone: (Boolean, String) -> Unit) {
        exec.execute {
            try {
                val h3i = H3Core.newSystemInstance()
                val repo = PoiRepository(ctx, h3i, h3Res)
                val graphDir = GhGraphInstaller.installIfNeeded(ctx)

                val gh = GraphHopper()
                gh.setProfiles(Profile("foot").setVehicle("foot").setWeighting("fastest"))
                gh.graphHopperLocation = graphDir.absolutePath
                gh.load()

                h3 = h3i
                poiRepo = repo
                hopper = gh

                ready.set(true)
                onDone(true, "Router ready (GH loaded)")
            } catch (t: Throwable) {
                ready.set(false)
                onDone(false, "Init failed: ${t.message}")
            }
        }
    }

    fun routeAsync(
        start: LatLng,
        dest: LatLng,
        prefs: RoutePrefs,
        onResult: (Boolean, String, List<LatLng>, Double) -> Unit
    ) {
        exec.execute {
            val h3i = h3
            val repo = poiRepo
            val gh = hopper

            if (!ready.get() || h3i == null || repo == null || gh == null) {
                onResult(false, "Router not ready", emptyList(), 0.0)
                return@execute
            }

            try {
                // 1) Baseline (direct) route first
                val direct = routeGh(gh, listOf(start, dest))
                if (!direct.ok) {
                    onResult(false, direct.msg, emptyList(), 0.0)
                    return@execute
                }

                // 2) Extra budget is km => meters
                val extraMeters = (prefs.maxKm.toDouble() * 1000.0).coerceAtLeast(0.0)
                val targetMeters = direct.meters + extraMeters
                val minMeters = direct.meters + extraMeters * minUtilization

                val slackUpMeters = if (extraMeters >= 8000.0) overshootSlackLargeMeters else overshootSlackSmallMeters
                val acceptGapMeters = if (extraMeters >= 8000.0) acceptGapLargeMeters else acceptGapSmallMeters

                Log.d(TAG, "=== ROUTING START ===")
                Log.d(TAG, "Prefs: parks=${prefs.parks}, res=${prefs.residential}, busy=${prefs.busy}, maxKm=${prefs.maxKm}")
                Log.d(TAG, "Direct route: ${"%.0f".format(direct.meters)}m, Extra budget: ${"%.0f".format(extraMeters)}m")
                Log.d(TAG, "Target: ${"%.0f".format(targetMeters)}m, Min acceptable: ${"%.0f".format(minMeters)}m")
                Log.d(TAG, "Has meaningful preference: ${prefs.hasPreference()}")

                // If extra is basically zero, return direct
                if (extraMeters <= 25.0) {
                    onResult(
                        true,
                        "OK ~${"%.2f".format(direct.meters / 1000.0)} km (direct)",
                        direct.points,
                        direct.meters
                    )
                    return@execute
                }

                val wantK = desiredWaypoints(extraMeters)

                // 3) Candidates: POI-driven + "far" candidates
                val candidates = buildWaypointCandidates(
                    h3 = h3i,
                    repo = repo,
                    baseRoute = direct.points,
                    start = start,
                    dest = dest,
                    prefs = prefs,
                    extraWantedMeters = extraMeters,
                    wantK = wantK
                )

                Log.d(TAG, "Generated ${candidates.size} waypoint candidates, want $wantK waypoints")

                // 4) Try 1 waypoint (fast)
                val best1 = pickBestOneWaypoint(
                    gh = gh,
                    h3 = h3i,
                    repo = repo,
                    start = start,
                    dest = dest,
                    candidates = candidates,
                    prefs = prefs,
                    targetMeters = targetMeters,
                    minMeters = minMeters,
                    slackUpMeters = slackUpMeters
                )

                if (best1 != null) {
                    Log.d(TAG, "Best 1-waypoint route: ${"%.0f".format(best1.meters)}m")
                }

                if (best1 != null &&
                    best1.meters <= targetMeters + slackUpMeters &&
                    best1.meters >= minMeters &&
                    abs(best1.meters - targetMeters) <= acceptGapMeters
                ) {
                    onResult(
                        true,
                        "OK ~${"%.2f".format(best1.meters / 1000.0)} km (prefs+budget)",
                        best1.points,
                        best1.meters
                    )
                    return@execute
                }

                // 5) Try 2 waypoints
                val best2 = pickBestTwoWaypoints(
                    gh = gh,
                    h3 = h3i,
                    repo = repo,
                    start = start,
                    dest = dest,
                    candidates = candidates,
                    prefs = prefs,
                    targetMeters = targetMeters,
                    minMeters = minMeters,
                    slackUpMeters = slackUpMeters,
                    seed = best1
                )

                if (best2 != null) {
                    Log.d(TAG, "Best 2-waypoint route: ${"%.0f".format(best2.meters)}m")
                }

                if (best2 != null &&
                    best2.meters <= targetMeters + slackUpMeters &&
                    best2.meters >= minMeters &&
                    abs(best2.meters - targetMeters) <= acceptGapMeters
                ) {
                    onResult(
                        true,
                        "OK ~${"%.2f".format(best2.meters / 1000.0)} km (prefs+budget)",
                        best2.points,
                        best2.meters
                    )
                    return@execute
                }

                // 6) For large budgets, try more waypoints
                val bestK = if (wantK >= 3) {
                    pickBestKWaypointsBeam(
                        gh = gh,
                        h3 = h3i,
                        repo = repo,
                        start = start,
                        dest = dest,
                        candidates = candidates,
                        prefs = prefs,
                        targetMeters = targetMeters,
                        minMeters = minMeters,
                        slackUpMeters = slackUpMeters,
                        k = wantK,
                        seed = best2 ?: best1
                    )
                } else null

                val best = bestK ?: best2 ?: best1 ?: direct

                Log.d(TAG, "=== ROUTING DONE === Final: ${"%.0f".format(best.meters)}m")

                onResult(
                    true,
                    "OK ~${"%.2f".format(best.meters / 1000.0)} km (prefs+budget)",
                    best.points,
                    best.meters
                )

            } catch (t: Throwable) {
                Log.e(TAG, "Routing crashed", t)
                onResult(false, "Crash: ${t.message}", emptyList(), 0.0)
            }
        }
    }

    // ----------------------------
    // GraphHopper routing
    // ----------------------------

    private data class GhRoute(
        val ok: Boolean,
        val msg: String,
        val points: List<LatLng>,
        val meters: Double
    )

    private fun routeGh(hopper: GraphHopper, pts: List<LatLng>): GhRoute {
        return try {
            val req = GHRequest()
            for (p in pts) req.addPoint(GHPoint(p.latitude, p.longitude))
            req.profile = "foot"
            req.locale = Locale.US

            val rsp = hopper.route(req)
            if (rsp.hasErrors()) {
                val err = rsp.errors.firstOrNull()?.message ?: "GraphHopper error"
                GhRoute(false, err, emptyList(), 0.0)
            } else {
                val path = rsp.best
                val pl = path.points
                val out = ArrayList<LatLng>(pl.size())
                for (i in 0 until pl.size()) {
                    val gp = pl.get(i)
                    out.add(LatLng(gp.lat, gp.lon))
                }
                GhRoute(true, "ok", out, path.distance)
            }
        } catch (t: Throwable) {
            GhRoute(false, t.message ?: "route failed", emptyList(), 0.0)
        }
    }

    // ----------------------------
    // Waypoint selection
    // ----------------------------

    private fun desiredWaypoints(extraMeters: Double): Int {
        return when {
            extraMeters < 800 -> 1
            extraMeters < 2000 -> 2
            extraMeters < 5000 -> 3
            extraMeters < 8000 -> 4
            else -> 5
        }
    }

    private fun pickBestOneWaypoint(
        gh: GraphHopper,
        h3: H3Core,
        repo: PoiRepository,
        start: LatLng,
        dest: LatLng,
        candidates: List<LatLng>,
        prefs: RoutePrefs,
        targetMeters: Double,
        minMeters: Double,
        slackUpMeters: Double
    ): GhRoute? {
        var best: GhRoute? = null
        var bestScore = Double.NEGATIVE_INFINITY

        val maxTry = min(35, candidates.size)

        for (idx in 0 until maxTry) {
            val wp = candidates[idx]
            val r = routeGh(gh, listOf(start, wp, dest))
            if (!r.ok) continue
            if (r.meters > targetMeters + slackUpMeters) continue

            val score = computeRouteScore(h3, repo, r, prefs, targetMeters, minMeters)

            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }

        if (best != null) {
            Log.d(TAG, "pickBestOneWaypoint: best score=${"%.2f".format(bestScore)}, meters=${"%.0f".format(best.meters)}")
        }

        return best
    }

    private fun pickBestTwoWaypoints(
        gh: GraphHopper,
        h3: H3Core,
        repo: PoiRepository,
        start: LatLng,
        dest: LatLng,
        candidates: List<LatLng>,
        prefs: RoutePrefs,
        targetMeters: Double,
        minMeters: Double,
        slackUpMeters: Double,
        seed: GhRoute?
    ): GhRoute? {
        val top = candidates.take(18)
        var best: GhRoute? = seed
        var bestScore = if (seed != null) computeRouteScore(h3, repo, seed, prefs, targetMeters, minMeters) else Double.NEGATIVE_INFINITY

        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                val w1 = top[i]
                val w2 = top[j]
                val r = routeGh(gh, listOf(start, w1, w2, dest))
                if (!r.ok) continue
                if (r.meters > targetMeters + slackUpMeters) continue

                val score = computeRouteScore(h3, repo, r, prefs, targetMeters, minMeters)

                if (score > bestScore) {
                    bestScore = score
                    best = r
                }
            }
        }

        return best
    }

    private fun pickBestKWaypointsBeam(
        gh: GraphHopper,
        h3: H3Core,
        repo: PoiRepository,
        start: LatLng,
        dest: LatLng,
        candidates: List<LatLng>,
        prefs: RoutePrefs,
        targetMeters: Double,
        minMeters: Double,
        slackUpMeters: Double,
        k: Int,
        seed: GhRoute?
    ): GhRoute? {
        data class BeamState(
            val waypoints: List<LatLng>,
            val route: GhRoute,
            val score: Double
        )

        val beamWidth = 6
        var beam = ArrayList<BeamState>()

        // Initialize beam with 1-waypoint routes
        val topCandidates = candidates.take(20)
        for (wp in topCandidates) {
            val r = routeGh(gh, listOf(start, wp, dest))
            if (!r.ok) continue
            if (r.meters > targetMeters + slackUpMeters) continue
            val score = computeRouteScore(h3, repo, r, prefs, targetMeters, minMeters)
            beam.add(BeamState(listOf(wp), r, score))
        }

        beam.sortByDescending { it.score }
        beam = ArrayList(beam.take(beamWidth))

        var best: GhRoute? = seed
        var bestScore = if (seed != null) computeRouteScore(h3, repo, seed, prefs, targetMeters, minMeters) else Double.NEGATIVE_INFINITY

        // Expand beam up to k waypoints
        var depth = 1
        while (depth < k && beam.isNotEmpty()) {
            val next = ArrayList<BeamState>()

            for (state in beam) {
                // Update best if this state is good
                if (state.route.meters >= minMeters && state.score > bestScore) {
                    bestScore = state.score
                    best = state.route
                }

                // Try adding another waypoint
                for (wp in topCandidates) {
                    if (wp in state.waypoints) continue

                    val newWaypoints = state.waypoints + wp
                    val pts = listOf(start) + newWaypoints + listOf(dest)
                    val r = routeGh(gh, pts)
                    if (!r.ok) continue
                    if (r.meters > targetMeters + slackUpMeters) continue

                    val score = computeRouteScore(h3, repo, r, prefs, targetMeters, minMeters)
                    next.add(BeamState(newWaypoints, r, score))
                }
            }

            next.sortByDescending { it.score }
            beam = ArrayList(next.take(beamWidth))
            depth++
        }

        // Final check of beam states
        for (state in beam) {
            if (state.route.meters >= minMeters && state.score > bestScore) {
                bestScore = state.score
                best = state.route
            }
        }

        return best
    }

    // ----------------------------
    // Candidate generation
    // ----------------------------

    private fun buildWaypointCandidates(
        h3: H3Core,
        repo: PoiRepository,
        baseRoute: List<LatLng>,
        start: LatLng,
        dest: LatLng,
        prefs: RoutePrefs,
        extraWantedMeters: Double,
        wantK: Int
    ): List<LatLng> {
        val out = ArrayList<LatLng>(64)

        // POI-driven candidates around the route
        out.addAll(
            buildPoiDrivenCandidatesSmart(
                h3 = h3,
                repo = repo,
                base = baseRoute,
                prefs = prefs,
                extraWantedMeters = extraWantedMeters
            )
        )

        // For big budgets, add "far" candidates
        if (wantK >= 3 || extraWantedMeters >= 5000.0) {
            out.addAll(
                buildFarRingCandidates(
                    center = midpointOnRoute(baseRoute, start, dest),
                    extraWantedMeters = extraWantedMeters
                )
            )
        }

        // Light fallback: on-route points
        if (baseRoute.isNotEmpty()) {
            val step = max(14, baseRoute.size / 30)
            var i = step
            while (out.size < 70 && i < baseRoute.size - step) {
                out.add(baseRoute[i])
                i += step
            }
        }

        // Remove duplicates and out-of-bounds points
        val uniq = ArrayList<LatLng>(out.size)
        val keySet = HashSet<String>(out.size)
        for (p in out) {
            if (!IsraelBounds.contains(p)) continue
            val key = "%.5f,%.5f".format(Locale.US, p.latitude, p.longitude)
            if (keySet.add(key)) uniq.add(p)
        }

        return uniq
    }

    private fun buildPoiDrivenCandidatesSmart(
        h3: H3Core,
        repo: PoiRepository,
        base: List<LatLng>,
        prefs: RoutePrefs,
        extraWantedMeters: Double
    ): List<LatLng> {
        if (base.size < 6) return emptyList()

        val maxDetours = when {
            extraWantedMeters < 600 -> 5
            extraWantedMeters < 1200 -> 8
            extraWantedMeters < 2400 -> 12
            extraWantedMeters < 8000 -> 18
            else -> 24
        }

        val scorer = H3OpeningScorer(h3, repo, h3Res)

        data class ScoredDetour(val score: Double, val cell: Long)

        val segStep = max(10, base.size / 18)
        val perDetourBudget = max(350.0, extraWantedMeters / maxDetours.toDouble())

        val detours = ArrayList<ScoredDetour>()
        var i = 0
        while (i < base.size - 2) {
            val j = min(base.size - 1, i + segStep)
            val a = base[i]
            val b = base[j]

            val aCell = h3.latLngToCell(a.latitude, a.longitude, h3Res)
            val bCell = h3.latLngToCell(b.latitude, b.longitude, h3Res)

            val det = scorer.bestDetourBetween(aCell, bCell, prefs, perDetourBudget)
            if (det != null) detours.add(ScoredDetour(det.score, det.insertCell))

            i += segStep
        }

        detours.sortByDescending { it.score }

        val out = ArrayList<LatLng>(maxDetours)
        val usedCells = HashSet<Long>()

        for (d in detours) {
            if (out.size >= maxDetours) break
            if (!usedCells.add(d.cell)) continue
            val ll = h3.cellToLatLng(d.cell)
            out.add(LatLng(ll.lat, ll.lng))
        }

        return out
    }

    private fun buildFarRingCandidates(center: LatLng, extraWantedMeters: Double): List<LatLng> {
        val r = extraWantedMeters * 0.35
        val radiusMeters = clamp(r, 1200.0, 5200.0)

        val res = ArrayList<LatLng>(24)

        for (deg in 0 until 360 step 30) {
            val p = moveByMeters(center, radiusMeters, deg.toDouble())
            if (IsraelBounds.contains(p)) res.add(p)
        }

        val inner = clamp(radiusMeters * 0.6, 900.0, 3200.0)
        for (deg in 15 until 360 step 45) {
            val p = moveByMeters(center, inner, deg.toDouble())
            if (IsraelBounds.contains(p)) res.add(p)
        }

        return res
    }

    private fun midpointOnRoute(base: List<LatLng>, start: LatLng, dest: LatLng): LatLng {
        if (base.isNotEmpty()) return base[base.size / 2]
        return LatLng(
            (start.latitude + dest.latitude) / 2.0,
            (start.longitude + dest.longitude) / 2.0
        )
    }

    private fun moveByMeters(origin: LatLng, meters: Double, bearingDeg: Double): LatLng {
        val latRad = origin.latitude * PI / 180.0
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * max(0.2, cos(latRad)))

        val br = bearingDeg * PI / 180.0
        val lat2 = origin.latitude + dLat * cos(br)
        val lon2 = origin.longitude + dLon * sin(br)
        return LatLng(lat2, lon2)
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))

    // ----------------------------
    // IMPROVED Route scoring (POI/H3)
    // ----------------------------

    /**
     * Compute a combined score for a route based on:
     * 1. POI preference matching (parks/residential/busy)
     * 2. Distance budget utilization (closer to target = better)
     *
     * KEY IMPROVEMENTS:
     * - Uses RELATIVE preference scoring (highest slider wins, others are penalized)
     * - Normalizes POI counts using log scale to handle imbalanced data
     * - Clearer separation between "I want parks" vs "I want everything equally"
     */
    private fun computeRouteScore(
        h3: H3Core,
        repo: PoiRepository,
        route: GhRoute,
        prefs: RoutePrefs,
        targetMeters: Double,
        minMeters: Double
    ): Double {
        val poiScore = scorePolylineWithH3Improved(h3, repo, route.points, prefs)

        // Distance score: reward routes that use the budget well
        // Penalize being too short (under-utilizing budget) or too long
        val distanceScore = when {
            route.meters < minMeters -> {
                // Under minimum - penalize based on how far under
                val shortfall = (minMeters - route.meters) / 1000.0
                -shortfall * 2.0
            }
            route.meters <= targetMeters -> {
                // In the sweet spot - reward being close to target
                val utilization = (route.meters - (targetMeters - (targetMeters - minMeters))) / (targetMeters - minMeters + 1)
                utilization * 5.0
            }
            else -> {
                // Over target - small penalty
                val overshoot = (route.meters - targetMeters) / 1000.0
                -overshoot * 0.5
            }
        }

        val totalScore = poiScore + distanceScore

        Log.d(TAG, "Route score: poi=${"%.2f".format(poiScore)}, dist=${"%.2f".format(distanceScore)}, " +
                "total=${"%.2f".format(totalScore)}, meters=${"%.0f".format(route.meters)}")

        return totalScore
    }

    /**
     * IMPROVED POI scoring that actually responds to slider preferences.
     *
     * Key changes:
     * 1. Computes RELATIVE weights - highest slider gets bonus, lowest gets penalty
     * 2. Uses log scaling to normalize wildly different POI counts
     * 3. When user has clear preference (one slider high, others low), that category dominates
     */
    private fun scorePolylineWithH3Improved(
        h3: H3Core,
        repo: PoiRepository,
        pts: List<LatLng>,
        prefs: RoutePrefs
    ): Double {
        if (pts.isEmpty()) return 0.0

        // Sample points along the route and collect H3 cells
        val step = max(6, pts.size / 70)
        val diskRadius = 1

        val cells = HashSet<String>()
        var i = 0
        while (i < pts.size) {
            val p = pts[i]
            val c = h3.latLngToCell(p.latitude, p.longitude, h3Res)
            for (d in h3.gridDisk(c, diskRadius)) {
                cells.add(h3.h3ToString(d))
            }
            i += step
        }

        // Get raw POI counts: [parks, residential, busy]
        val counts = repo.countCats(cells)
        val parksCount = counts[0].toDouble()
        val resCount = counts[1].toDouble()
        val busyCount = counts[2].toDouble()

        // Convert slider values to weights using RELATIVE scoring
        // The idea: if parks=0.8, res=0.2, busy=0.2, then parks should dominate
        val pVal = prefs.parks.toDouble()
        val rVal = prefs.residential.toDouble()
        val bVal = prefs.busy.toDouble()

        val total = pVal + rVal + bVal + 0.001 // avoid div by zero
        val maxPref = maxOf(pVal, rVal, bVal)

        // Compute weights:
        // - If a category is the max (or close to it), it gets a strong positive weight
        // - If a category is much lower than max, it can get negative weight (penalty)
        // - If all are equal, all get mild positive weights

        val pWeight = computeCategoryWeight(pVal, maxPref, total)
        val rWeight = computeCategoryWeight(rVal, maxPref, total)
        val bWeight = computeCategoryWeight(bVal, maxPref, total)

        // Apply log scaling to counts to handle imbalanced data
        // This prevents 500 cafés from drowning out 10 parks
        val pNorm = logScale(parksCount)
        val rNorm = logScale(resCount)
        val bNorm = logScale(busyCount)

        val score = pNorm * pWeight + rNorm * rWeight + bNorm * bWeight

        Log.d(TAG, "POI scoring: counts=[parks=${"%.0f".format(parksCount)}, res=${"%.0f".format(resCount)}, busy=${"%.0f".format(busyCount)}]")
        Log.d(TAG, "POI weights: [p=${"%.2f".format(pWeight)}, r=${"%.2f".format(rWeight)}, b=${"%.2f".format(bWeight)}]")
        Log.d(TAG, "POI normalized: [p=${"%.2f".format(pNorm)}, r=${"%.2f".format(rNorm)}, b=${"%.2f".format(bNorm)}]")
        Log.d(TAG, "POI final score: ${"%.2f".format(score)}")

        return score
    }

    /**
     * Compute weight for a category based on its preference value relative to others.
     *
     * @param value This category's slider value
     * @param maxPref The maximum slider value among all categories
     * @param total Sum of all slider values
     */
    private fun computeCategoryWeight(value: Double, maxPref: Double, total: Double): Double {
        // How close is this value to the max?
        val relativeToMax = if (maxPref > 0.001) value / maxPref else 1.0

        // Base weight from the slider value itself (0 to 1 range)
        val baseWeight = value

        // Bonus if this is the preferred category (or close to it)
        val isPreferred = relativeToMax >= 0.8
        val preferenceBonus = if (isPreferred) 1.5 else 0.0

        // Penalty if this category is significantly lower than the max
        // This makes routes AVOID busy areas when user prefers parks
        val penalty = if (relativeToMax < 0.5 && maxPref > 0.3) {
            // User clearly prefers something else
            -0.5 * (1.0 - relativeToMax)
        } else {
            0.0
        }

        // Final weight: base (0-1) + bonus (0 or 1.5) + penalty (0 to -0.5)
        // Range roughly: -0.5 to 2.5
        return baseWeight + preferenceBonus + penalty
    }

    /**
     * Log scale normalization to handle wildly different POI counts.
     * ln(1 + count) compresses large numbers while preserving relative differences.
     */
    private fun logScale(count: Double): Double {
        return if (count > 0) ln(1.0 + count) else 0.0
    }
}