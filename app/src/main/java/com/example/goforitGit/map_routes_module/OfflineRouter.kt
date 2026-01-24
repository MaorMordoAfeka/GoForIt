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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RoutePrefs(
    val parks: Float,
    val residential: Float,
    val busy: Float,
    val maxKm: Float
)

class OfflineRouter(private val ctx: Context) {

    private val exec = Executors.newSingleThreadExecutor()
    private val ready = AtomicBoolean(false)

    private var h3: H3Core? = null
    private var poiRepo: PoiRepository? = null
    private var hopper: GraphHopper? = null

    private val h3Res = 10

    fun isReady(): Boolean = ready.get()

    fun initAsync(onDone: (Boolean, String) -> Unit) {
        exec.execute {
            try {
                val h3i = H3Core.newSystemInstance()
                val repo = PoiRepository(ctx, h3i, h3Res)
                val graphDir = GhGraphInstaller.installIfNeeded(ctx)

                val gh = GraphHopper()
                // IMPORTANT: declare the profile(s) used at runtime.
                // Must match the profile name embedded in the pre-built graph.
                gh.setProfiles(Profile("foot").setVehicle("foot").setWeighting("fastest"))
                gh.graphHopperLocation = graphDir.absolutePath
                gh.load()

                h3 = h3i
                poiRepo = repo
                hopper = gh

                ready.set(true)
                onDone(true, "Router ready ✅ (GH loaded)")
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
                // 1) Always compute the baseline (direct) route first
                val direct = routeGh(gh, listOf(start, dest))
                if (!direct.ok) {
                    onResult(false, direct.msg, emptyList(), 0.0)
                    return@execute
                }

                // 2) maxKm is now "EXTRA distance budget" in km
                val extraMeters = (prefs.maxKm.toDouble() * 1000.0).coerceAtLeast(0.0)
                val targetMeters = direct.meters + extraMeters

                // If extra budget is basically zero, return the direct route
                val zeroTol = 25.0 // meters
                if (extraMeters <= zeroTol) {
                    onResult(
                        true,
                        "OK ~${"%.2f".format(direct.meters / 1000.0)} km (direct)",
                        direct.points,
                        direct.meters
                    )
                    return@execute
                }

                // We want to add up to extraMeters worth of detour (budget)
                val extraWanted = extraMeters

                // Generate DETOUR waypoints using H3+POI preference scoring
                val candidates = buildWaypointCandidatesSmart(
                    h3 = h3i,
                    repo = repo,
                    base = direct.points,
                    prefs = prefs,
                    extraWantedMeters = extraWanted
                )

                Log.d(
                    "OfflineRouter",
                    "routeAsync prefs=$prefs direct=${"%.0f".format(direct.meters)}m " +
                            "extra=${"%.0f".format(extraMeters)}m target=${"%.0f".format(targetMeters)}m " +
                            "candidates=${candidates.size}"
                )

                // 3) Try 1 waypoint
                val best1 = pickBestOneWaypoint(
                    gh, h3i, repo,
                    start, dest,
                    candidates, prefs,
                    targetMeters
                )

                // If we got close enough to target, accept
                if (best1 != null && kotlin.math.abs(best1.meters - targetMeters) <= 300.0) {
                    onResult(
                        true,
                        "OK ~${"%.2f".format(best1.meters / 1000.0)} km (prefs+budget)",
                        best1.points,
                        best1.meters
                    )
                    return@execute
                }

                // 4) Try 2 waypoints
                val best2 = pickBestTwoWaypoints(
                    gh, h3i, repo,
                    start, dest,
                    candidates, prefs,
                    targetMeters,
                    best1
                )

                val best = best2 ?: best1 ?: direct

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

    /**
     * Proposes off-corridor detour waypoints based on your POI+H3 preference scoring.
     * This is what makes the sliders actually change the chosen route.
     */
    private fun buildWaypointCandidatesSmart(
        h3: H3Core,
        repo: PoiRepository,
        base: List<LatLng>,
        prefs: RoutePrefs,
        extraWantedMeters: Double
    ): List<LatLng> {
        if (base.size < 6) return emptyList()

        val maxDetours = when {
            extraWantedMeters < 600 -> 4
            extraWantedMeters < 1200 -> 6
            extraWantedMeters < 2400 -> 10
            else -> 14
        }

        val scorer = H3OpeningScorer(h3, repo, h3Res)

        data class ScoredDetour(val score: Double, val cell: Long)

        val segStep = max(10, base.size / 18)
        val perDetourBudget = max(300.0, extraWantedMeters / maxDetours.toDouble())

        val detours = ArrayList<ScoredDetour>()
        var i = 0
        while (i < base.size - 2) {
            val j = min(base.size - 1, i + segStep)
            val a = base[i]
            val b = base[j]

            val aCell: Long = h3.latLngToCell(a.latitude, a.longitude, h3Res)
            val bCell: Long = h3.latLngToCell(b.latitude, b.longitude, h3Res)

            val det = scorer.bestDetourBetween(aCell, bCell, prefs, perDetourBudget)
            if (det != null) detours.add(ScoredDetour(det.score, det.insertCell))

            i += segStep
        }

        detours.sortByDescending { it.score }

        val out = ArrayList<LatLng>(maxDetours + 16)
        val usedCells = HashSet<Long>()

        for (d in detours) {
            if (out.size >= maxDetours) break
            if (!usedCells.add(d.cell)) continue

            val ll = h3.cellToLatLng(d.cell)
            out.add(LatLng(ll.lat, ll.lng))
        }

        // Fallback: add a few on-route candidates too (helps in sparse POI areas).
        val fallbackStep = max(12, base.size / 25)
        var k = fallbackStep
        while (out.size < maxDetours + 10 && k < base.size - fallbackStep) {
            out.add(base[k])
            k += fallbackStep
        }

        return out
    }

    private fun pickBestOneWaypoint(
        gh: GraphHopper,
        h3: H3Core,
        repo: PoiRepository,
        start: LatLng,
        dest: LatLng,
        candidates: List<LatLng>,
        prefs: RoutePrefs,
        targetMeters: Double
    ): GhRoute? {
        var best: GhRoute? = null
        var bestScore = -1e18

        val maxTry = min(28, candidates.size)

        for (idx in 0 until maxTry) {
            val wp = candidates[idx]
            val r = routeGh(gh, listOf(start, wp, dest))
            if (!r.ok) continue
            if (r.meters > targetMeters + 900.0) continue

            val poi = scorePolylineWithH3(h3, repo, r.points, prefs)
            val closeness = -abs(r.meters - targetMeters) / 110.0
            val totalScore = poi + closeness

            if (totalScore > bestScore) {
                bestScore = totalScore
                best = r
            }
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
        seed: GhRoute?
    ): GhRoute? {
        val top = candidates.take(14)
        var best: GhRoute? = seed
        var bestScore = if (seed != null) scorePolylineWithH3(h3, repo, seed.points, prefs) else -1e18

        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                val w1 = top[i]
                val w2 = top[j]
                val r = routeGh(gh, listOf(start, w1, w2, dest))
                if (!r.ok) continue
                if (r.meters > targetMeters + 900.0) continue

                val poi = scorePolylineWithH3(h3, repo, r.points, prefs)
                val closeness = -abs(r.meters - targetMeters) / 110.0
                val totalScore = poi + closeness

                if (totalScore > bestScore) {
                    bestScore = totalScore
                    best = r
                }
            }
        }
        return best
    }

    /**
     * Score a route by counting POIs near the route in H3, with slider-based boosts.
     * Key improvement: buffer the sampled route cells with gridDisk(radius=1).
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


        return counts[0] * parksBoost + counts[1] * resBoost + counts[2] * busyBoost
    }
}
