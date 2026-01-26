package com.example.goforitGit.map_routes_module

import android.util.Log
import com.uber.h3core.H3Core
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class H3OpeningScorer(
    private val h3: H3Core,
    private val poiRepo: PoiRepository,
    private val h3Res: Int,
    private val diskRadiusForPoi: Int = 2,
    private val candidateDiskRadius: Int = 2,
    private val openingHalfAngleDeg: Double = 120.0
) {
    companion object {
        private const val TAG = "H3OpeningScorer"
    }

    data class Detour(
        val insertCell: Long,
        val extraMeters: Double,
        val score: Double,
        val newSegment: List<Long>
    )

    fun bestDetourBetween(
        aCell: Long,
        bCell: Long,
        prefs: RoutePrefs,
        remainingMeters: Double
    ): Detour? {

        val a = cellCenter(aCell)
        val b = cellCenter(bCell)

        val directMeters = haversineMeters(a, b)
        val forwardBearing = bearingDeg(a, b)

        val disk: List<Long> = h3.gridDisk(aCell, candidateDiskRadius)
        val candidates = ArrayList<Long>(disk.size)
        for (cand: Long in disk) {
            if (cand != aCell && cand != bCell) candidates.add(cand)
        }

        var best: Detour? = null

        for (cand: Long in candidates) {
            val c = cellCenter(cand)

            val candBearing = bearingDeg(a, c)
            val diff = abs(angleDiffDeg(forwardBearing, candBearing))
            if (diff > openingHalfAngleDeg) continue

            val p1 = safeGridPath(aCell, cand) ?: continue
            val p2 = safeGridPath(cand, bCell) ?: continue
            val seg: List<Long> = p1 + p2.drop(1)

            val segMeters = pathDistanceMeters(seg)
            val extra = segMeters - directMeters
            if (extra <= 0.0) continue
            if (extra > remainingMeters) continue

            val score = poiScoreAroundCell(cand, prefs)

            val detour = Detour(
                insertCell = cand,
                extraMeters = extra,
                score = score,
                newSegment = seg
            )

            // Keep "best" by score/extra ratio
            if (best == null) {
                best = detour
            } else {
                val bestRatio = best.score / (best.extraMeters + 1.0)
                val curRatio = detour.score / (detour.extraMeters + 1.0)
                if (curRatio > bestRatio) best = detour
            }
        }

        return best
    }

    /**
     * IMPROVED POI scoring that uses relative preference weights.
     * This ensures that when user sets parks=high, residential=low, busy=low,
     * cells with parks are strongly preferred over cells with busy areas.
     */
    private fun poiScoreAroundCell(cell: Long, prefs: RoutePrefs): Double {
        val disk: List<Long> = h3.gridDisk(cell, diskRadiusForPoi)
        val diskCells = HashSet<String>(disk.size)
        for (idx: Long in disk) {
            diskCells.add(h3.h3ToString(idx))
        }

        val counts = poiRepo.countCats(diskCells) // [parks, residential, busy]
        val parksCount = counts[0].toDouble()
        val resCount = counts[1].toDouble()
        val busyCount = counts[2].toDouble()

        // Get preference values
        val pVal = prefs.parks.toDouble()
        val rVal = prefs.residential.toDouble()
        val bVal = prefs.busy.toDouble()

        val maxPref = maxOf(pVal, rVal, bVal)
        val total = pVal + rVal + bVal + 0.001

        // Compute weights using relative scoring
        val pWeight = computeCategoryWeight(pVal, maxPref)
        val rWeight = computeCategoryWeight(rVal, maxPref)
        val bWeight = computeCategoryWeight(bVal, maxPref)

        // Use log scaling to normalize POI counts
        val pNorm = logScale(parksCount)
        val rNorm = logScale(resCount)
        val bNorm = logScale(busyCount)

        val score = pNorm * pWeight + rNorm * rWeight + bNorm * bWeight

        return score
    }

    /**
     * Compute weight for a category based on its preference value relative to others.
     */
    private fun computeCategoryWeight(value: Double, maxPref: Double): Double {
        val relativeToMax = if (maxPref > 0.001) value / maxPref else 1.0

        // Base weight from slider value
        val baseWeight = value

        // Bonus if this is the preferred category
        val isPreferred = relativeToMax >= 0.8
        val preferenceBonus = if (isPreferred) 1.5 else 0.0

        // Penalty if this category is significantly lower than max
        val penalty = if (relativeToMax < 0.5 && maxPref > 0.3) {
            -0.5 * (1.0 - relativeToMax)
        } else {
            0.0
        }

        return baseWeight + preferenceBonus + penalty
    }

    /**
     * Log scale to handle wildly different POI counts.
     */
    private fun logScale(count: Double): Double {
        return if (count > 0) ln(1.0 + count) else 0.0
    }

    private fun safeGridPath(a: Long, b: Long): List<Long>? {
        return try { h3.gridPathCells(a, b) } catch (_: Throwable) { null }
    }

    private fun cellCenter(cell: Long): LatLng {
        val ll = h3.cellToLatLng(cell)
        return LatLng(ll.lat, ll.lng)
    }

    private fun pathDistanceMeters(cells: List<Long>): Double {
        if (cells.size < 2) return 0.0
        var sum = 0.0
        var prev = cellCenter(cells[0])
        for (i in 1 until cells.size) {
            val cur = cellCenter(cells[i])
            sum += haversineMeters(prev, cur)
            prev = cur
        }
        return sum
    }

    private fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var d = (b - a + 540.0) % 360.0 - 180.0
        if (d < -180) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) *
                sin(dLon / 2) * sin(dLon / 2)

        return 2.0 * r * atan2(sqrt(s), sqrt(1.0 - s))
    }
}