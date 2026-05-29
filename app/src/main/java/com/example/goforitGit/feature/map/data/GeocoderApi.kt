package com.example.goforitGit.feature.map.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online place-name autocomplete via Komoot's Photon geocoder.
 *
 * The offline POI database has no names, so name search has to go online.
 * Routing itself stays fully offline — this is only used to turn a typed
 * place name into coordinates.
 *
 * Results are biased to Israel (bbox + lat/lon focus). Photon returns names
 * in the local language by default, so Israeli places come back in Hebrew
 * where OSM has Hebrew names, with English where tagged.
 */
object GeocoderApi {

    private const val TAG = "GeocoderApi"
    private const val ENDPOINT = "https://photon.komoot.io/api"

    // Israel bounding box: minLon,minLat,maxLon,maxLat
    private const val ISRAEL_BBOX = "34.2,29.4,35.95,33.4"
    private const val FOCUS_LAT = 31.8
    private const val FOCUS_LON = 35.0

    data class Suggestion(val label: String, val lat: Double, val lon: Double)

    suspend fun search(query: String, limit: Int = 8): List<Suggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "$ENDPOINT?q=${URLEncoder.encode(q, "UTF-8")}" +
                            "&limit=$limit" +
                            "&lat=$FOCUS_LAT&lon=$FOCUS_LON" +
                            "&bbox=$ISRAEL_BBOX"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "GoForIt-Android")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Photon HTTP ${conn.responseCode} for \"$q\"")
                    return@withContext emptyList()
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                parse(body)
            } catch (t: Throwable) {
                Log.w(TAG, "Photon search failed for \"$q\": ${t.message}")
                emptyList()
            }
        }
    }

    private fun parse(body: String): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val features = JSONObject(body).optJSONArray("features") ?: return out

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val label = buildLabel(feature.optJSONObject("properties"))
            if (label.isBlank()) continue

            out.add(Suggestion(label, lat, lon))
        }
        return out
    }

    private fun buildLabel(props: JSONObject?): String {
        if (props == null) return ""

        val name = props.optString("name").trim()
        val street = props.optString("street").trim()
        val houseNumber = props.optString("housenumber").trim()
        val city = props.optString("city").trim()
            .ifBlank { props.optString("town").trim() }
            .ifBlank { props.optString("village").trim() }

        val streetPart = when {
            street.isNotBlank() && houseNumber.isNotBlank() -> "$street $houseNumber"
            street.isNotBlank() -> street
            else -> ""
        }

        return listOf(name, streetPart, city)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }
}
