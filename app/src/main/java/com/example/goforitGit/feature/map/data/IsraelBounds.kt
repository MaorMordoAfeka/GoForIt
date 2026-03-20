package com.example.goforitGit.feature.map.data

import org.maplibre.android.geometry.LatLng

/**
 * Simple Israel bounding box.
 * (Not perfect borders yet — later we will replace with an Israel polygon check.)
 */
object IsraelBounds {
    private const val MIN_LAT = 29.0
    private const val MAX_LAT = 33.8
    private const val MIN_LON = 34.0
    private const val MAX_LON = 36.9

    fun contains(p: LatLng): Boolean {
        return p.latitude in MIN_LAT..MAX_LAT && p.longitude in MIN_LON..MAX_LON
    }
}
