package com.example.goforitGit.core.util.StepsUtils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Loads the college GeoJSON polygon and answers point-in-polygon queries.
 *
 * Expected asset path:
 * app/src/main/assets/college_polygon.json
 *
 * GeoJSON coordinate order is:
 * [longitude, latitude]
 */
class CollegeZoneChecker(context: Context) {

    companion object {
        private const val TAG = "CollegeZoneChecker"
        private const val ASSET_NAME = "college_polygon.json"
        private const val EPS = 1e-9
    }

    private data class Vertex(
        val lat: Double,
        val lon: Double
    )

    private val appContext = context.applicationContext

    private val polygon: List<Vertex> by lazy {
        loadPolygon()
    }

    fun contains(lat: Double, lon: Double): Boolean {
        if (polygon.size < 3) return false

        if (isPointOnBoundary(lat, lon, polygon)) {
            return true
        }

        var inside = false
        var j = polygon.lastIndex

        for (i in polygon.indices) {
            val yi = polygon[i].lat
            val xi = polygon[i].lon
            val yj = polygon[j].lat
            val xj = polygon[j].lon

            val intersects = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / ((yj - yi).let { if (abs(it) < EPS) EPS else it }) + xi)

            if (intersects) {
                inside = !inside
            }

            j = i
        }

        return inside
    }

    private fun loadPolygon(): List<Vertex> {
        return try {
            val jsonText = appContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val features = root.getJSONArray("features")
            if (features.length() == 0) return emptyList()

            val feature = features.getJSONObject(0)
            val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            if (coordinates.length() == 0) return emptyList()

            val ring = coordinates.getJSONArray(0)
            buildList {
                for (i in 0 until ring.length()) {
                    val pair = ring.getJSONArray(i)
                    val lon = pair.getDouble(0)
                    val lat = pair.getDouble(1)
                    add(Vertex(lat = lat, lon = lon))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load $ASSET_NAME", t)
            emptyList()
        }
    }

    private fun isPointOnBoundary(lat: Double, lon: Double, polygon: List<Vertex>): Boolean {
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (pointOnSegment(lat, lon, a, b)) {
                return true
            }
        }
        return false
    }

    private fun pointOnSegment(lat: Double, lon: Double, a: Vertex, b: Vertex): Boolean {
        val cross = (lon - a.lon) * (b.lat - a.lat) - (lat - a.lat) * (b.lon - a.lon)
        if (abs(cross) > EPS) return false

        val withinLon = lon >= min(a.lon, b.lon) - EPS && lon <= max(a.lon, b.lon) + EPS
        val withinLat = lat >= min(a.lat, b.lat) - EPS && lat <= max(a.lat, b.lat) + EPS
        return withinLon && withinLat
    }
}