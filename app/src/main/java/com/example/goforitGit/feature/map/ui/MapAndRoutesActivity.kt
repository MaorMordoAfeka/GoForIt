package com.example.goforitGit.feature.map.ui

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
import com.example.goforitGit.feature.map.data.IsraelBounds
import com.example.goforitGit.feature.map.data.MbTilesServer
import com.example.goforitGit.feature.map.data.OfflineRouter
import com.example.goforitGit.feature.map.data.RoutePrefs
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import com.uber.h3core.H3Core


class MapAndRoutesActivity : AppCompatActivity() {

    private val startSourceId = "start-source"
    private val destSourceId  = "dest-source"
    private val startLayerId  = "start-layer"
    private val destLayerId   = "dest-layer"

    // H3 hexagon overlay
    private val h3SourceId       = "h3-hexagons-source"
    private val h3LayerId        = "h3-hexagons-layer"
    private val h3OutlineLayerId = "h3-hexagons-outline-layer"

    // POI overlay
    private val poiSourceId  = "poi-source"
    private val poiLayerId   = "poi-layer"

    private val emptyFC = """{"type":"FeatureCollection","features":[]}"""

    private fun pointFC(p: LatLng?): String {
        if (p == null) return emptyFC
        return """
        {
          "type":"FeatureCollection",
          "features":[
            {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.longitude},${p.latitude}]}}
          ]
        }
    """.trimIndent()
    }

    private fun ensurePinLayers(style: Style) {
        if (style.getSource(startSourceId) == null) style.addSource(GeoJsonSource(startSourceId, emptyFC))
        if (style.getSource(destSourceId)  == null) style.addSource(GeoJsonSource(destSourceId,  emptyFC))

        if (style.getLayer(startLayerId) == null) {
            style.addLayer(
                CircleLayer(startLayerId, startSourceId).withProperties(
                    circleColor(Color.parseColor("#E53935")),
                    circleRadius(8f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2f),
                    circleOpacity(0.95f)
                )
            )
        }

        if (style.getLayer(destLayerId) == null) {
            style.addLayer(
                CircleLayer(destLayerId, destSourceId).withProperties(
                    circleColor(Color.parseColor("#43A047")),
                    circleRadius(8f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2f),
                    circleOpacity(0.95f)
                )
            )
        }
    }

    private fun updatePins(style: Style) {
        style.getSourceAs<GeoJsonSource>(startSourceId)?.setGeoJson(pointFC(startPoint))
        style.getSourceAs<GeoJsonSource>(destSourceId)?.setGeoJson(pointFC(destPoint))
    }

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

    private lateinit var btnShowH3: MaterialButton
    private lateinit var btnClearH3: MaterialButton
    private lateinit var btnShowPoi: MaterialButton

    private var startPoint: LatLng? = null
    private var destPoint: LatLng? = null

    private val routeSourceId    = "route-source"
    private val routeLayerId     = "route-layer"
    private val building3dLayerId = "building-3d-layer"

    private var mapStyle: Style? = null
    private lateinit var offlineRouter: OfflineRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.feature_map_activity)

        etStart      = findViewById(R.id.etStart)
        etDest       = findViewById(R.id.etDest)
        sParks       = findViewById(R.id.sParks)
        sResidential = findViewById(R.id.sResidential)
        sBusy        = findViewById(R.id.sBusy)
        sDistanceKm  = findViewById(R.id.sDistanceKm)
        btnPlan      = findViewById(R.id.btnPlan)
        tvHint       = findViewById(R.id.tvHint)
        btnShowH3    = findViewById(R.id.btnShowH3)
        btnClearH3   = findViewById(R.id.btnClearH3)
        btnShowPoi   = findViewById(R.id.btnShowPoi)

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
                BottomSheetBehavior.STATE_EXPANDED      -> BottomSheetBehavior.STATE_COLLAPSED
                BottomSheetBehavior.STATE_HALF_EXPANDED -> BottomSheetBehavior.STATE_COLLAPSED
                else -> BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }

        tileServer = MbTilesServer(this)
        val ok = tileServer.startServerSafely()
        if (!ok) tvHint.text = "Tile server failed (Logcat: MbTilesServer)."

        offlineRouter = OfflineRouter(this)
        offlineRouter.initAsync { success, msg ->
            runOnUiThread { tvHint.text = msg }
        }

        // --- H3 button listeners ---
        btnShowH3.setOnClickListener {
            val style = mapStyle ?: run { tvHint.text = "Map not ready yet."; return@setOnClickListener }
            val cells = offlineRouter.lastRouteCells
            if (cells.isEmpty()) { tvHint.text = "Plan a route first, then show hexagons."; return@setOnClickListener }
            showH3Hexagons(style, cells)
            tvHint.text = "Showing ${cells.size} H3 hexagons on route."
        }

        btnClearH3.setOnClickListener {
            val style = mapStyle ?: return@setOnClickListener
            style.getSourceAs<GeoJsonSource>(h3SourceId)?.setGeoJson(emptyFC)
            style.getSourceAs<GeoJsonSource>(poiSourceId)?.setGeoJson(emptyFC)
            tvHint.text = "Hexagons and POIs cleared."
        }

        // --- POI button listener ---
        btnShowPoi.setOnClickListener {
            val style = mapStyle ?: run { tvHint.text = "Map not ready yet."; return@setOnClickListener }
            val cells = offlineRouter.lastRouteCells
            if (cells.isEmpty()) { tvHint.text = "Plan a route first, then show POIs."; return@setOnClickListener }
            val repo = offlineRouter.getPoiRepository()
            val h3   = offlineRouter.getH3()
            if (repo == null || h3 == null) { tvHint.text = "Router not ready yet."; return@setOnClickListener }
            showPois(style, cells, repo, h3)
        }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri("asset://style_vector_localhost.json")) { style ->
                mapStyle = style

                ensurePinLayers(style)
                updatePins(style)
                ensureH3Layers(style)
                ensurePoiLayer(style)

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(32.0853, 34.7818))
                    .zoom(15.5)
                    .tilt(50.0)
                    .bearing(15.0)
                    .build()

                add3DBuildingsLayer(style)

                if (style.getSource(routeSourceId) == null) {
                    style.addSource(GeoJsonSource(routeSourceId))
                }

                if (style.getLayer(routeLayerId) == null) {
                    style.addLayer(
                        LineLayer(routeLayerId, routeSourceId).withProperties(
                            lineColor(Color.parseColor("#202491")),
                            lineWidth(6f),
                            lineOpacity(0.9f),
                            lineJoin(Property.LINE_JOIN_ROUND),
                            lineCap(Property.LINE_CAP_ROUND)
                        )
                    )
                }

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
                    mapStyle?.let { updatePins(it) }
                    sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    true
                }

                btnPlan.setOnClickListener {
                    val styleNow = mapStyle ?: run { tvHint.text = "Map style not ready yet."; return@setOnClickListener }

                    if (!offlineRouter.isReady()) {
                        tvHint.text = "Router not ready yet (still loading POIs/H3)."
                        return@setOnClickListener
                    }

                    startPoint = parseLatLng(etStart.text?.toString()) ?: startPoint
                    destPoint  = parseLatLng(etDest.text?.toString())  ?: destPoint

                    updatePins(styleNow)

                    val a = startPoint
                    val b = destPoint
                    if (a == null || b == null) { tvHint.text = "Please set Start and Destination."; return@setOnClickListener }

                    if (!IsraelBounds.contains(a) || !IsraelBounds.contains(b)) {
                        tvHint.text = "Start/Destination must be inside Israel bounds."
                        clearRoute(styleNow)
                        return@setOnClickListener
                    }

                    val prefs = RoutePrefs(
                        parks       = sParks.value,
                        residential = sResidential.value,
                        busy        = sBusy.value,
                        maxKm       = sDistanceKm.value
                    )

                    tvHint.text = "Routing (H3 + POI detours)..."

                    // Clear old overlays when planning a new route
                    styleNow.getSourceAs<GeoJsonSource>(h3SourceId)?.setGeoJson(emptyFC)
                    styleNow.getSourceAs<GeoJsonSource>(poiSourceId)?.setGeoJson(emptyFC)

                    offlineRouter.routeAsync(a, b, prefs) { ok2, msg2, points, _ ->
                        runOnUiThread {
                            tvHint.text = msg2
                            if (!ok2) { clearRoute(styleNow); return@runOnUiThread }
                            styleNow.getSourceAs<GeoJsonSource>(routeSourceId)?.setGeoJson(lineGeoJson(points))
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // H3 hexagon helpers
    // -----------------------------------------------------------------------

    private fun ensureH3Layers(style: Style) {
        if (style.getSource(h3SourceId) == null) {
            style.addSource(GeoJsonSource(h3SourceId, emptyFC))
        }
        if (style.getLayer(h3LayerId) == null) {
            style.addLayer(
                FillLayer(h3LayerId, h3SourceId).withProperties(
                    fillColor(Color.parseColor("#3F51B5")),
                    fillOpacity(0.25f)
                )
            )
        }
        if (style.getLayer(h3OutlineLayerId) == null) {
            style.addLayer(
                LineLayer(h3OutlineLayerId, h3SourceId).withProperties(
                    lineColor(Color.parseColor("#3F51B5")),
                    lineWidth(1.5f),
                    lineOpacity(0.75f)
                )
            )
        }
    }

    private fun showH3Hexagons(style: Style, cells: List<Long>) {
        Thread {
            val geoJson = try {
                val h3Core = H3Core.newSystemInstance()
                val features = cells.mapNotNull { cell ->
                    try {
                        val boundary = h3Core.cellToBoundary(cell)
                        if (boundary.isEmpty()) return@mapNotNull null
                        val coords = boundary.joinToString(",") { v -> "[${v.lng},${v.lat}]" }
                        val first = boundary.first()
                        """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[${coords},[${first.lng},${first.lat}]]]}}"""
                    } catch (_: Throwable) { null }
                }
                """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
            } catch (t: Throwable) {
                runOnUiThread { tvHint.text = "H3 init failed: ${t.message}" }
                return@Thread
            }
            runOnUiThread { style.getSourceAs<GeoJsonSource>(h3SourceId)?.setGeoJson(geoJson) }
        }.start()
    }

    // -----------------------------------------------------------------------
    // POI helpers
    // -----------------------------------------------------------------------

    private fun ensurePoiLayer(style: Style) {
        if (style.getSource(poiSourceId) == null) {
            style.addSource(GeoJsonSource(poiSourceId, emptyFC))
        }
        // One circle layer; colour is stored as a GeoJSON property "color"
        if (style.getLayer(poiLayerId) == null) {
            style.addLayer(
                CircleLayer(poiLayerId, poiSourceId).withProperties(
                    circleColor(get("color")),
                    circleRadius(6f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(1.5f),
                    circleOpacity(0.9f)
                )
            )
        }
    }

    /**
     * Loads POIs for the route cells on a background thread and draws them as
     * colour-coded dots:
     *   Green  = Parks / nature  (preference: Parks slider)
     *   Blue   = Residential     (preference: Residential slider)
     *   Orange = Busy / shops    (preference: Busy slider)
     */
    private fun showPois(
        style: Style,
        cells: List<Long>,
        repo: com.example.goforitGit.feature.map.data.PoiRepository,
        h3Core: H3Core
    ) {
        tvHint.text = "Loading POIs…"
        Thread {
            val cellStrings = cells.map { h3Core.h3ToString(it) }.toSet()
            val pois = repo.getPoisInCells(cellStrings)

            val features = pois.map { poi ->
                val color = when (poi.bucket) {
                    0    -> "#4CAF50"   // green  — parks/beaches
                    1    -> "#9C27B0"   // purple — residential
                    else -> "#FFEB3B"   // yellow — busy/shops
                }
                """{"type":"Feature","properties":{"color":"$color"},"geometry":{"type":"Point","coordinates":[${poi.lon},${poi.lat}]}}"""
            }

            val geoJson = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

            runOnUiThread {
                style.getSourceAs<GeoJsonSource>(poiSourceId)?.setGeoJson(geoJson)
                if (pois.isEmpty()) {
                    tvHint.text = "No POIs found along this route."
                } else {
                    val parks = pois.count { it.bucket == 0 }
                    val res   = pois.count { it.bucket == 1 }
                    val busy  = pois.count { it.bucket == 2 }
                    tvHint.text = "POIs: ${parks} parks (green), ${res} residential (purple), ${busy} busy (yellow)"
                }
            }
        }.start()
    }

    // -----------------------------------------------------------------------

    private fun add3DBuildingsLayer(style: Style) {
        val sourceId = "local"
        val layerId  = "buildings-3d"

        style.getLayer(layerId)?.let { style.removeLayer(it) }

        val layer = FillExtrusionLayer(layerId, sourceId).apply {
            sourceLayer = "buildings"
            minZoom = 14f
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

        val belowLayerId = "streets"
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

    override fun onStart()    { super.onStart();    mapView.onStart()  }
    override fun onResume()   { super.onResume();   mapView.onResume() }
    override fun onPause()    { mapView.onPause();  super.onPause()    }
    override fun onStop()     { super.onStop();     mapView.onStop()   }

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