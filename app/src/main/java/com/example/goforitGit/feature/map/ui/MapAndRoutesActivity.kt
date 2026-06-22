package com.example.goforitGit.feature.map.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.view.Gravity
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.goforitGit.R
import com.example.goforitGit.navigation.DrawerNavigator
import com.example.goforitGit.feature.map.data.GeocoderApi
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
import java.util.Locale


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

    private lateinit var etStart: MaterialAutoCompleteTextView
    private lateinit var etDest: MaterialAutoCompleteTextView
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

    // --- Place-name autocomplete (Photon) state ---
    private val searchHandler = Handler(Looper.getMainLooper())
    private var startSearchRunnable: Runnable? = null
    private var destSearchRunnable: Runnable? = null
    private lateinit var startAdapter: ArrayAdapter<String>
    private lateinit var destAdapter: ArrayAdapter<String>
    private var startResults: List<GeocoderApi.Suggestion> = emptyList()
    private var destResults: List<GeocoderApi.Suggestion> = emptyList()
    private var suppressStartSearch = false
    private var suppressDestSearch = false

    private val searchDebounceMs = 300L

    private val CURRENT_LOCATION_LABEL = "📍 Use current location"

    /**
     * Adapter whose dropdown shows exactly the items we put in via
     * clear()/addAll() — no client-side prefix filtering, since the
     * suggestions already come pre-filtered from the geocoder. This is
     * what lets Hebrew suggestions show for Hebrew (or English) typing.
     */
    private class NoFilterAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1) {
        private val passThrough = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults =
                FilterResults().apply { count = 1 }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                notifyDataSetChanged()
        }
        override fun getFilter(): Filter = passThrough
    }

    private val routeSourceId    = "route-source"
    private val routeLayerId     = "route-layer"
    private val building3dLayerId = "building-3d-layer"

    private var mapStyle: Style? = null
    private var maplibreMap: MapLibreMap? = null
    private lateinit var offlineRouter: OfflineRouter

    // --- "You are here" walking figure ---
    private lateinit var figureContainer: View
    private lateinit var walkingFigure: ImageView
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var userLocationCallback: LocationCallback
    private var userLocation: LatLng? = null
    private var locationUpdatesActive = false
    private var figureAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.feature_map_activity)

        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            setNavigationOnClickListener {
                DrawerNavigator.open(this@MapAndRoutesActivity)
            }
            bringToFront()
        }

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

        setupAutocomplete()
        setupUserLocationFigure()

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

        val fabMyLocation = findViewById<FloatingActionButton>(R.id.fabMyLocation)
        fabMyLocation.setOnClickListener {
            val loc = userLocation
            if (loc == null) {
                tvHint.text = "Still locating you… make sure location is on."
                startLocationUpdates()
                return@setOnClickListener
            }
            maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16.0))
        }

        // Keep both FABs drawn on top of the map/overlays.
        fabMenu.bringToFront()
        fabMyLocation.bringToFront()

        // Keep the full map-control stack hidden when the route sheet is expanded.
        // This includes: Compass → Route options → My location.
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val hidden = newState == BottomSheetBehavior.STATE_EXPANDED

                fabMyLocation.visibility = if (hidden) View.GONE else View.VISIBLE
                fabMenu.visibility = if (hidden) View.GONE else View.VISIBLE
                maplibreMap?.uiSettings?.setCompassEnabled(!hidden)
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })


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
            maplibreMap = map
            // Keep the walking figure pinned to its GPS point as the map moves.
            map.addOnCameraMoveListener { updateFigurePosition() }
            // Keep all map controls together on the right:
            // Compass → Route options → My location.
            map.uiSettings.setCompassEnabled(true)
            map.uiSettings.compassGravity = Gravity.BOTTOM or Gravity.END
            map.uiSettings.setCompassMargins(
                0,
                0,
                dp(16f).toInt(),
                dp(338f).toInt()
            )
            ContextCompat.getDrawable(this, R.drawable.ic_map_compass)?.let {
                map.uiSettings.setCompassImage(it)
            }

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
                        setFieldTextSilently(etStart, "${latLng.latitude},${latLng.longitude}", isStart = true)
                        setFieldTextSilently(etDest, "", isStart = false)
                        tvHint.text = "Start set. Tap again to set Destination."
                        clearRoute(style)
                    } else {
                        destPoint = latLng
                        setFieldTextSilently(etDest, "${latLng.latitude},${latLng.longitude}", isStart = false)
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

    // -----------------------------------------------------------------------
    // Place-name autocomplete (Photon, online). Routing stays offline.
    // -----------------------------------------------------------------------

    private fun setupAutocomplete() {
        startAdapter = NoFilterAdapter(this)
        destAdapter = NoFilterAdapter(this)
        etStart.setAdapter(startAdapter)
        etDest.setAdapter(destAdapter)

        // Let the Start dropdown open even before the user has typed (so the
        // "current location" shortcut can appear on focus).
        etStart.threshold = 1

        etStart.addTextChangedListener(makeSearchWatcher(isStart = true))
        etDest.addTextChangedListener(makeSearchWatcher(isStart = false))

        etStart.setOnItemClickListener { _, _, position, _ ->
            if (startAdapter.getItem(position) == CURRENT_LOCATION_LABEL) {
                chooseCurrentLocationAsStart()
            } else {
                startResults.getOrNull(position)?.let { onSuggestionChosen(it, isStart = true) }
            }
        }
        etDest.setOnItemClickListener { _, _, position, _ ->
            destResults.getOrNull(position)?.let { onSuggestionChosen(it, isStart = false) }
        }

        // Offer "current location" as the first thing the user sees in Start,
        // without preventing them from typing an address instead.
        etStart.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etStart.text.isNullOrBlank()) showCurrentLocationOption()
        }
        etStart.setOnClickListener {
            if (etStart.text.isNullOrBlank()) showCurrentLocationOption()
        }
    }

    private fun showCurrentLocationOption() {
        startResults = emptyList()
        startAdapter.clear()
        startAdapter.add(CURRENT_LOCATION_LABEL)
        startAdapter.notifyDataSetChanged()
        // Post so the dropdown opens after the focus/layout pass settles —
        // calling showDropDown() synchronously during onFocusChange is ignored.
        etStart.post {
            if (etStart.hasFocus() && startAdapter.count > 0) {
                etStart.showDropDown()
            }
        }
    }

    private fun chooseCurrentLocationAsStart() {
        val loc = userLocation
        if (loc == null) {
            tvHint.text = "Still locating you… make sure location is on."
            startLocationUpdates()
            return
        }
        startPoint = loc
        setFieldTextSilently(etStart, "Current location", isStart = true)
        mapStyle?.let { updatePins(it) }
        maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16.0))

        val coords = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
        tvHint.text = if (IsraelBounds.contains(loc)) {
            "Start set to your location ($coords). Set a destination, then Plan Route."
        } else {
            "Your location ($coords) is outside Israel — offline routing only works inside Israel."
        }
    }

    private fun makeSearchWatcher(isStart: Boolean) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (isStart && suppressStartSearch) return
            if (!isStart && suppressDestSearch) return
            scheduleSearch(s?.toString().orEmpty(), isStart)
        }
    }

    private fun scheduleSearch(query: String, isStart: Boolean) {
        (if (isStart) startSearchRunnable else destSearchRunnable)
            ?.let { searchHandler.removeCallbacks(it) }

        val q = query.trim()
        // Empty Start field -> re-offer the current-location shortcut.
        if (q.isEmpty()) {
            if (isStart) showCurrentLocationOption()
            return
        }
        // Too short, or already a "lat,lon" pair -> nothing to geocode.
        if (q.length < 2 || parseLatLng(q) != null) return

        val runnable = Runnable { runSearch(q, isStart) }
        if (isStart) startSearchRunnable = runnable else destSearchRunnable = runnable
        searchHandler.postDelayed(runnable, searchDebounceMs)
    }

    private fun runSearch(query: String, isStart: Boolean) {
        lifecycleScope.launch {
            val results = GeocoderApi.search(query)
            val field = if (isStart) etStart else etDest
            val adapter = if (isStart) startAdapter else destAdapter

            if (isStart) startResults = results else destResults = results

            adapter.clear()
            adapter.addAll(results.map { it.label })
            adapter.notifyDataSetChanged()

            if (results.isNotEmpty() && field.hasFocus()) {
                field.showDropDown()
            }
        }
    }

    private fun onSuggestionChosen(s: GeocoderApi.Suggestion, isStart: Boolean) {
        val point = LatLng(s.lat, s.lon)
        if (isStart) {
            startPoint = point
            setFieldTextSilently(etStart, s.label, isStart = true)
        } else {
            destPoint = point
            setFieldTextSilently(etDest, s.label, isStart = false)
        }
        mapStyle?.let { updatePins(it) }
        maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15.0))
        tvHint.text = if (isStart) "Start set: ${s.label}" else "Destination set: ${s.label}"
    }

    private fun setFieldTextSilently(
        field: MaterialAutoCompleteTextView,
        text: String,
        isStart: Boolean
    ) {
        if (isStart) suppressStartSearch = true else suppressDestSearch = true
        field.setText(text, false) // false = don't run the adapter filter
        field.dismissDropDown()
        field.setSelection(text.length)
        if (isStart) suppressStartSearch = false else suppressDestSearch = false
    }

    // -----------------------------------------------------------------------
    // "You are here" walking figure (live GPS, animated overlay)
    // -----------------------------------------------------------------------

    private fun setupUserLocationFigure() {
        figureContainer = findViewById(R.id.walkingFigureContainer)
        walkingFigure = findViewById(R.id.walkingFigure)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        userLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                userLocation = LatLng(loc.latitude, loc.longitude)
                updateFigurePosition()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesActive || !hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(1_500L)
            .build()
        fusedLocation.requestLocationUpdates(request, userLocationCallback, Looper.getMainLooper())
        locationUpdatesActive = true
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        fusedLocation.removeLocationUpdates(userLocationCallback)
        locationUpdatesActive = false
    }

    /** Place the figure so its feet dot sits on the GPS point; hide if off-screen. */
    private fun updateFigurePosition() {
        val map = maplibreMap ?: return hideFigure()
        val loc = userLocation ?: return hideFigure()

        val point = map.projection.toScreenLocation(loc)
        val w = figureContainer.width.takeIf { it > 0 }?.toFloat() ?: dp(60f)
        val h = figureContainer.height.takeIf { it > 0 }?.toFloat() ?: dp(78f)

        val onScreen = point.x in 0f..mapView.width.toFloat() &&
                point.y in 0f..mapView.height.toFloat()
        if (!onScreen) {
            hideFigure()
            return
        }

        figureContainer.x = point.x - w / 2f
        figureContainer.y = point.y - h        // anchor bottom (feet dot) at the point
        if (figureContainer.visibility != View.VISIBLE) {
            figureContainer.visibility = View.VISIBLE
        }
    }

    private fun hideFigure() {
        if (::figureContainer.isInitialized && figureContainer.visibility != View.GONE) {
            figureContainer.visibility = View.GONE
        }
    }

    private fun startFigureAnimation() {
        if (figureAnimator != null) return
        val bob = ObjectAnimator.ofFloat(walkingFigure, View.TRANSLATION_Y, 0f, -dp(5f)).apply {
            duration = 420L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val tilt = ObjectAnimator.ofFloat(walkingFigure, View.ROTATION, -6f, 6f).apply {
            duration = 420L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        figureAnimator = AnimatorSet().apply {
            playTogether(bob, tilt)
            start()
        }
    }

    private fun stopFigureAnimation() {
        figureAnimator?.cancel()
        figureAnimator = null
        if (::walkingFigure.isInitialized) {
            walkingFigure.translationY = 0f
            walkingFigure.rotation = 0f
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

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

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        startLocationUpdates()
        startFigureAnimation()
    }

    override fun onPause() {
        stopLocationUpdates()
        stopFigureAnimation()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop()     { super.onStop();     mapView.onStop()   }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        stopFigureAnimation()
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