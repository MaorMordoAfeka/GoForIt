package com.example.goforitGit.map_routes_module

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class RoutePrefs(
    val parks: Float,
    val residential: Float,
    val busy: Float,

    /**
     * EXTRA distance budget (km) to add above the direct route.
     * Example: maxKm=1.0 means “allow ~1km extra detour”.
     * In your UI the max is 10KM.
     */
    val maxKm: Float
)

class OfflineRouter(private val ctx: Context) {

    private val exec = Executors.newSingleThreadExecutor()
    private val ready = AtomicBoolean(false)

    private var h3: H3Core? = null
    private var poiRepo: PoiRepository? = null
    private var hopper: GraphHopper? = null

    private val h3Res = 10

    // ---- Budget behavior tuning ----
    /** We try to spend at least this fraction of the user's extra budget (when feasible). */
    private val minUtilization = 0.85

    /** Default overshoot allowed above (direct + extra). Increased for large budgets. */
    private val overshootSlackSmallMeters = 300.0
    private val overshootSlackLargeMeters = 700.0

    /** If we are within this gap from target, we consider it good enough (can early exit). */
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
                // Must match the profile embedded in your pre-built graph.
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

                // 3) Candidates: POI-driven + "far" candidates to actually spend big budgets (e.g., 10km)
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

                Log.d(
                    "OfflineRouter",
                    "routeAsync prefs=$prefs direct=${"%.0f".format(direct.meters)}m " +
                            "extra=${"%.0f".format(extraMeters)}m target=${"%.0f".format(targetMeters)}m " +
                            "min=${"%.0f".format(minMeters)}m k=$wantK candidates=${candidates.size}"
                )

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

                // 6) For large budgets (like 10KM), 3–4 via points are often required
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

                onResult(
                    true,
                    "OK ~${"%.2f".format(best.meters / 1000.0)} km (prefs+budget)",
                    best.points,
                    best.meters
                )
            } catch (t: Throwable) {
                onResult(false, "Crash: ${t.message}", emptyList(), 0.0)
            }
        }
    }

    // ----------------------------
    // Core routing + selection
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

    private fun desiredWaypoints(extraMeters: Double): Int = when {
        extraMeters < 1000.0 -> 1
        extraMeters < 4000.0 -> 2
        extraMeters < 8000.0 -> 3
        else -> 4 // e.g. 10km
    }

    /**
     * For big extra budgets, distance proximity must dominate (otherwise POIs will "win" and routes stay short).
     */
    private fun distanceFirst(prefs: RoutePrefs): Boolean = prefs.maxKm >= 5f

    /**
     * Utilization-first picker with switchable priority:
     *  - Hard cap: meters <= (target + slackUpMeters)
     *  - Prefer routes that reach minMeters
     *  - If distanceFirst=true: choose closest-to-target first; POI breaks ties
     *  - Else: choose max POI first; closeness breaks ties
     *  - If nothing reaches minMeters: choose longest-under-cap; POI breaks ties
     */
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
        val capMeters = targetMeters + slackUpMeters
        val df = distanceFirst(prefs)
        val maxTry = min(32, candidates.size)

        var bestFeasible: GhRoute? = null
        var bestFeasiblePoi = -1e18
        var bestFeasibleGap = 1e18

        var bestFallback: GhRoute? = null
        var bestFallbackMeters = -1e18
        var bestFallbackPoi = -1e18

        for (idx in 0 until maxTry) {
            val wp = candidates[idx]
            val r = routeGh(gh, listOf(start, wp, dest))
            if (!r.ok) continue
            if (r.meters > capMeters) continue

            val poi = scorePolylineWithH3(h3, repo, r.points, prefs)
            val gap = abs(r.meters - targetMeters)

            if (r.meters >= minMeters) {
                if (df) {
                    if (gap < bestFeasibleGap || (gap == bestFeasibleGap && poi > bestFeasiblePoi)) {
                        bestFeasibleGap = gap
                        bestFeasiblePoi = poi
                        bestFeasible = r
                    }
                } else {
                    if (poi > bestFeasiblePoi || (poi == bestFeasiblePoi && gap < bestFeasibleGap)) {
                        bestFeasiblePoi = poi
                        bestFeasibleGap = gap
                        bestFeasible = r
                    }
                }
            } else {
                if (r.meters > bestFallbackMeters || (r.meters == bestFallbackMeters && poi > bestFallbackPoi)) {
                    bestFallbackMeters = r.meters
                    bestFallbackPoi = poi
                    bestFallback = r
                }
            }
        }

        return bestFeasible ?: bestFallback
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
        val capMeters = targetMeters + slackUpMeters
        val df = distanceFirst(prefs)
        val top = candidates.take(16)

        var bestFeasible: GhRoute? = null
        var bestFeasiblePoi = -1e18
        var bestFeasibleGap = 1e18

        var bestFallback: GhRoute? = null
        var bestFallbackMeters = -1e18
        var bestFallbackPoi = -1e18

        fun consider(r: GhRoute) {
            if (!r.ok) return
            if (r.meters > capMeters) return

            val poi = scorePolylineWithH3(h3, repo, r.points, prefs)
            val gap = abs(r.meters - targetMeters)

            if (r.meters >= minMeters) {
                if (df) {
                    if (gap < bestFeasibleGap || (gap == bestFeasibleGap && poi > bestFeasiblePoi)) {
                        bestFeasibleGap = gap
                        bestFeasiblePoi = poi
                        bestFeasible = r
                    }
                } else {
                    if (poi > bestFeasiblePoi || (poi == bestFeasiblePoi && gap < bestFeasibleGap)) {
                        bestFeasiblePoi = poi
                        bestFeasibleGap = gap
                        bestFeasible = r
                    }
                }
            } else {
                if (r.meters > bestFallbackMeters || (r.meters == bestFallbackMeters && poi > bestFallbackPoi)) {
                    bestFallbackMeters = r.meters
                    bestFallbackPoi = poi
                    bestFallback = r
                }
            }
        }

        if (seed != null) consider(seed)

        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                val r = routeGh(gh, listOf(start, top[i], top[j], dest))
                consider(r)
            }
        }

        return bestFeasible ?: bestFallback
    }

    /**
     * Beam-search over K waypoints (K=3..4 for large budgets). This avoids combinatorial explosion.
     *
     * We keep a small beam of best partial solutions, expanding by appending one more waypoint.
     * For walking + 10km extra, this is the piece that enables consuming most of the budget.
     */
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
        val capMeters = targetMeters + slackUpMeters
        val df = distanceFirst(prefs)

        val topN = if (k >= 4) 12 else 14
        val top = candidates.take(topN)
        if (top.isEmpty()) return seed

        val beamWidth = if (k >= 4) 10 else 12

        data class BeamState(
            val mask: Long,
            val vias: List<LatLng>,
            val route: GhRoute,
            val poi: Double,
            val gap: Double
        )

        fun scoreAndMake(mask: Long, vias: List<LatLng>): BeamState? {
            val pts = ArrayList<LatLng>(vias.size + 2)
            pts.add(start)
            pts.addAll(vias)
            pts.add(dest)

            val r = routeGh(gh, pts)
            if (!r.ok) return null
            if (r.meters > capMeters) return null

            val poi = scorePolylineWithH3(h3, repo, r.points, prefs)
            val gap = abs(r.meters - targetMeters)
            return BeamState(mask, vias, r, poi, gap)
        }

        // Ranking:
        // feasible first; then:
        // - distanceFirst: smallest gap wins; poi breaks ties
        // - POI-first: highest poi wins; gap breaks ties
        fun better(a: BeamState, b: BeamState): Boolean {
            val aFeas = a.route.meters >= minMeters
            val bFeas = b.route.meters >= minMeters
            if (aFeas != bFeas) return aFeas

            return if (df) {
                if (a.gap != b.gap) a.gap < b.gap else a.poi > b.poi
            } else {
                if (a.poi != b.poi) a.poi > b.poi else a.gap < b.gap
            }
        }

        // Best overall (including fallback if no feasible exists)
        var best: BeamState? = null

        // Seed into best (so we never get worse)
        if (seed != null && seed.ok && seed.meters <= capMeters) {
            val seedPoi = scorePolylineWithH3(h3, repo, seed.points, prefs)
            val seedGap = abs(seed.meters - targetMeters)
            best = BeamState(0L, emptyList(), seed, seedPoi, seedGap)
        }

        // Beam starts with empty vias (no route computed yet); we will compute states at depth=1..k
        var beam = emptyList<BeamState>()

        // Initialize depth=1 from all single-waypoint choices
        run {
            val next = ArrayList<BeamState>(top.size)
            for (i in top.indices) {
                val st = scoreAndMake(mask = 1L shl i, vias = listOf(top[i])) ?: continue
                next.add(st)
                if (best == null || better(st, best!!)) best = st
            }
            next.sortWith { x, y -> if (better(x, y)) -1 else 1 }
            beam = next.take(beamWidth)
        }

        // Expand to depth 2..k
        var depth = 2
        while (depth <= k && beam.isNotEmpty()) {
            val next = ArrayList<BeamState>(beamWidth * top.size)

            for (st in beam) {
                for (i in top.indices) {
                    val bit = 1L shl i
                    if ((st.mask and bit) != 0L) continue

                    val vias = ArrayList<LatLng>(st.vias.size + 1)
                    vias.addAll(st.vias)
                    vias.add(top[i])

                    val child = scoreAndMake(mask = st.mask or bit, vias = vias) ?: continue
                    next.add(child)
                    if (best == null || better(child, best!!)) best = child
                }
            }

            next.sortWith { x, y -> if (better(x, y)) -1 else 1 }
            beam = next.take(beamWidth)
            depth++
        }

        // If we found any feasible (>=minMeters), best already reflects the chosen priority.
        // If not, best will naturally be the closest we found under the cap (or seed).
        return best?.route ?: seed
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

        // For big budgets (like 10km), add "far" candidates to make it feasible to consume budget.
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

    /**
     * Proposes off-corridor detour waypoints based on your POI+H3 preference scoring.
     */
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

    /**
     * Generates points in a ring around a center to help consume big budgets.
     * These points are not POI-driven, but they enable multi-via routes to "stretch".
     */
    private fun buildFarRingCandidates(center: LatLng, extraWantedMeters: Double): List<LatLng> {
        // Choose radius based on extra budget. For 10km, a ~3.5–5km ring works well.
        val r = extraWantedMeters * 0.35
        val radiusMeters = clamp(r, 1200.0, 5200.0)

        val res = ArrayList<LatLng>(24)

        // 12 bearings around the circle
        for (deg in 0 until 360 step 30) {
            val p = moveByMeters(center, radiusMeters, deg.toDouble())
            if (IsraelBounds.contains(p)) res.add(p)
        }

        // Add a smaller inner ring too (more variety)
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
        // Simple local approximation is enough for short distances (<= ~5km)
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
    // Route scoring (POI/H3)
    // ----------------------------

    /**
     * Score a route by counting POIs near the route in H3, with slider-based boosts.
     * Buffers the sampled route cells with gridDisk(radius=1).
     */
    private fun scorePolylineWithH3(
        h3: H3Core,
        repo: PoiRepository,
        pts: List<LatLng>,
        prefs: RoutePrefs
    ): Double {
        if (pts.isEmpty()) return 0.0

        val parksBoost = 0.5 + prefs.parks.toDouble() * 1.5
        val resBoost = 0.5 + prefs.residential.toDouble() * 1.5
        val busyBoost = 0.5 + prefs.busy.toDouble() * 1.5

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

        val counts = repo.countCats(cells)

        Log.d(
            "OfflineRouter",
            "POI counts parks=${counts[0]} res=${counts[1]} busy=${counts[2]} " +
                    "boosts p=$parksBoost r=$resBoost b=$busyBoost " +
                    "cells=${cells.size} pts=${pts.size}"
        )

        return counts[0] * parksBoost + counts[1] * resBoost + counts[2] * busyBoost
    }
}
