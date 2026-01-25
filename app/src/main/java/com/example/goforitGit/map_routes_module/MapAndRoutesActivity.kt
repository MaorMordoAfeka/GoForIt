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
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*


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
    private val building3dLayerId = "building-3d-layer"

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

        // Router init
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

                // 3D camera with tilt (pitch) for building extrusion
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(32.0853, 34.7818))  // Tel Aviv
                    .zoom(15.5)                         // Closer zoom to see 3D buildings
                    .tilt(50.0)                         // Increased tilt for better 3D effect
                    .bearing(15.0)                      // Slight rotation for depth
                    .build()

                // Add 3D buildings layer programmatically (if not in style)
                add3DBuildingsLayer(style)

                // Add route source if not in style
                if (style.getSource(routeSourceId) == null) {
                    style.addSource(GeoJsonSource(routeSourceId))
                }

                // Route layer - check if already defined in style
                if (style.getLayer(routeLayerId) == null) {
                    style.addLayer(
                        LineLayer(routeLayerId, routeSourceId).withProperties(
                            lineColor(Color.parseColor("#e91e63")),  // Pink/magenta route
                            lineWidth(6f),
                            lineOpacity(0.9f),
                            lineJoin(Property.LINE_JOIN_ROUND),
                            lineCap(Property.LINE_CAP_ROUND)
                        )
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

    /**
     * Add 3D building extrusion layer programmatically.
     * This creates colorful 3D buildings based on their height.
     */
// MapAndRoutesActivity.kt
// Call this AFTER the style is loaded (inside map.setStyle { style -> ... })
    private fun add3DBuildingsLayer(style: Style) {
        val sourceId = "local" // must match the source id in your style json
        val layerId = "buildings-3d"

        // Remove previous instance if you hot-reload style or re-enter activity
        style.getLayer(layerId)?.let { style.removeLayer(it) }

        val layer = FillExtrusionLayer(layerId, sourceId).apply {
            // IMPORTANT: your MBTiles schema uses "buildings" (plural), not "building"
            sourceLayer = "buildings"

            // Show only when buildings exist and 3D makes sense
            minZoom = 14f

            // If your tiles have height attributes, you can switch to them later.
            // For now, use a zoom-based constant height so you DEFINITELY see 3D.
            setProperties(
                fillExtrusionColor(Color.parseColor("#d7ccc8")),
                fillExtrusionOpacity(0.88f),
                fillExtrusionBase(literal(0)),
                fillExtrusionHeight(
                    interpolate(
                        linear(), zoom(),
                        stop(14, literal(0)),
                        stop(15, literal(18)),
                        stop(16, literal(30))
                    )
                )
            )
        }

        // Place 3D buildings above base building fill (if exists) but below labels if you add them later.
        val belowLayerId = "streets" // change if your style uses a different line layer id
        if (style.getLayer(belowLayerId) != null) {
            style.addLayerBelow(layer, belowLayerId)
        } else {
            style.addLayer(layer)
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