package com.example.goforitGit.map_routes_module

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.goforitGit.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.graphics.Color
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.Property


class MapAndRoutesActivity : AppCompatActivity() {

    private lateinit var sheetBehavior: BottomSheetBehavior<View>
    private lateinit var mapView: MapView
    private lateinit var tileServer: MbTilesServer

    private lateinit var etStart: TextInputEditText
    private lateinit var etDest: TextInputEditText
    private lateinit var sParks: Slider
    private lateinit var sResidential: Slider
    private lateinit var sBusy: Slider
    private lateinit var sDistanceKm: Slider
    private lateinit var btnPlan: MaterialButton
    private lateinit var tvHint: TextView

    private var startPoint: LatLng? = null
    private var destPoint: LatLng? = null

    private val routeSourceId = "route-source"
    private val routeLayerId = "route-layer"

    private var mapStyle: Style? = null
    private lateinit var offlineRouter: OfflineRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map_and_routes)

        // UI
        etStart = findViewById(R.id.etStart)
        etDest = findViewById(R.id.etDest)
        sParks = findViewById(R.id.sParks)
        sResidential = findViewById(R.id.sResidential)
        sBusy = findViewById(R.id.sBusy)
        sDistanceKm = findViewById(R.id.sDistanceKm)
        btnPlan = findViewById(R.id.btnPlan)
        tvHint = findViewById(R.id.tvHint)

        val sheet = findViewById<View>(R.id.routeSheet)
        sheetBehavior = BottomSheetBehavior.from(sheet).apply {
            isFitToContents = false
            halfExpandedRatio = 0.45f
            state = BottomSheetBehavior.STATE_COLLAPSED
            isHideable = false
        }

        val fabMenu = findViewById<FloatingActionButton>(R.id.fabMenu)
        fabMenu.setOnClickListener {
            sheetBehavior.state = when (sheetBehavior.state) {
                BottomSheetBehavior.STATE_EXPANDED -> BottomSheetBehavior.STATE_COLLAPSED
                BottomSheetBehavior.STATE_HALF_EXPANDED -> BottomSheetBehavior.STATE_COLLAPSED
                else -> BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }

        // Tiles server (offline MBTiles)
        tileServer = MbTilesServer(this)
        val ok = tileServer.startServerSafely()
        if (!ok) tvHint.text = "Tile server failed (Logcat: MbTilesServer)."

        // H3 router init (NO GraphHopper)
        offlineRouter = OfflineRouter(this)
        offlineRouter.initAsync { success, msg ->
            runOnUiThread { tvHint.text = msg }
        }

        // Map
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri("asset://style_vector_localhost.json")) { style ->
                mapStyle = style

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(32.0853, 34.7818))
                    .zoom(12.5)
                    .build()

                if (style.getSource(routeSourceId) == null) {
                    style.addSource(GeoJsonSource(routeSourceId))
                }
                if (style.getLayer(routeLayerId) == null) {
                    style.addLayer(
                        LineLayer(routeLayerId, routeSourceId).withProperties(
                            lineColor(Color.RED),
                            lineWidth(4f),
                            lineOpacity(0.9f),
                            lineJoin(Property.LINE_JOIN_ROUND),
                            lineCap(Property.LINE_CAP_ROUND)
                        )
                    )
                } else {
                    // If the layer already exists (e.g., from the style JSON), force it to red anyway
                    style.getLayerAs<LineLayer>(routeLayerId)?.setProperties(
                        lineColor(Color.RED),
                        lineWidth(4f),
                        lineOpacity(0.9f),
                        lineJoin(Property.LINE_JOIN_ROUND),
                        lineCap(Property.LINE_CAP_ROUND)
                    )
                }



                // Tap to set Start then Destination
                map.addOnMapClickListener { latLng ->
                    if (startPoint == null || destPoint != null) {
                        startPoint = latLng
                        destPoint = null
                        etStart.setText("${latLng.latitude},${latLng.longitude}")
                        etDest.setText("")
                        tvHint.text = "Start set. Tap again to set Destination."
                        clearRoute(style)
                        } else {
                        destPoint = latLng
                        etDest.setText("${latLng.latitude},${latLng.longitude}")
                        tvHint.text = "Destination set. Press Plan Route."
                    }

                    sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    true
                }

                btnPlan.setOnClickListener {
                    val styleNow = mapStyle ?: run {
                        tvHint.text = "Map style not ready yet."
                        return@setOnClickListener
                    }

                    if (!offlineRouter.isReady()) {
                        tvHint.text = "Router not ready yet (still loading POIs/H3)."
                        return@setOnClickListener
                    }

                    // allow manual lat,lon edit
                    startPoint = parseLatLng(etStart.text?.toString()) ?: startPoint
                    destPoint = parseLatLng(etDest.text?.toString()) ?: destPoint

                    val a = startPoint
                    val b = destPoint
                    if (a == null || b == null) {
                        tvHint.text = "Please set Start and Destination."
                        return@setOnClickListener
                    }

                    if (!IsraelBounds.contains(a) || !IsraelBounds.contains(b)) {
                        tvHint.text = "Start/Destination must be inside Israel bounds."
                        clearRoute(styleNow)
                        return@setOnClickListener
                    }

                    val prefs = RoutePrefs(
                        parks = sParks.value,
                        residential = sResidential.value,
                        busy = sBusy.value,
                        maxKm = sDistanceKm.value
                    )

                    tvHint.text = "Routing (H3 + POI detours)..."

                    offlineRouter.routeAsync(a, b, prefs) { ok2, msg2, points, _ ->
                        runOnUiThread {
                            tvHint.text = msg2
                            if (!ok2) {
                                clearRoute(styleNow)
                                return@runOnUiThread
                            }
                            styleNow.getSourceAs<GeoJsonSource>(routeSourceId)
                                ?.setGeoJson(lineGeoJson(points))
                        }
                    }
                }
            }
        }
    }

    private fun parseLatLng(s: String?): LatLng? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return LatLng(lat, lon)
    }

    private fun lineGeoJson(points: List<LatLng>): String {
        val coords = points.joinToString(",") { p -> "[${p.longitude},${p.latitude}]" }
        return """{
          "type":"FeatureCollection",
          "features":[
            {"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[ $coords ]}}
          ]
        }"""
    }

    private fun clearRoute(style: Style) {
        style.getSourceAs<GeoJsonSource>(routeSourceId)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }

    override fun onDestroy() {
        mapView.onDestroy()
        tileServer.stopServerSafely()
        super.onDestroy()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}

/**
 * Simple bounding box check (good enough to prevent routing outside).
 * If you already have IsraelBounds in another file, KEEP yours and delete this one.
 */

