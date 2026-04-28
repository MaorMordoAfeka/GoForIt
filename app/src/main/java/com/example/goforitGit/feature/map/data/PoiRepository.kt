package com.example.goforitGit.feature.map.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.uber.h3core.H3Core
import java.util.Locale

class PoiRepository(
    ctx: Context,
    private val h3: H3Core,
    private val h3Res: Int
) {

    companion object { private const val TAG = "PoiRepository" }

    private enum class Mode { H3_TABLE, LATLON_TABLE, EMPTY }

    private val db: SQLiteDatabase? = try {
        val file = PoiDbInstaller.installIfNeeded(ctx)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to open POI DB.", t)
        null
    }

    // In-memory index (only used when DB is lat/lon based)
    private val memIndex = HashMap<String, IntArray>()

    private var mode: Mode = Mode.EMPTY

    private var tableName: String = ""
    private var colH3: String = ""
    private var colCat: String = ""

    private var colLat: String = ""
    private var colLon: String = ""
    private var colType: String = ""

    // Debug counters
    private var debugParksFound = 0
    private var debugResFound = 0
    private var debugBusyFound = 0
    private var debugUnknown = 0

    init {
        initSchemaAndMaybeBuildIndex()
        logDatabaseStats()
    }

    /**
     * Returns counts per category: [parks, residential, busy]
     */
    fun countCats(h3Cells: Set<String>): IntArray {
        if (h3Cells.isEmpty()) return intArrayOf(0, 0, 0)
        val out = intArrayOf(0, 0, 0)

        when (mode) {
            Mode.H3_TABLE -> {
                val d = db ?: return out
                val placeholders = h3Cells.joinToString(",") { "?" }
                val sql = """
                    SELECT $colCat, COUNT(*) AS cnt
                    FROM $tableName
                    WHERE $colH3 IN ($placeholders)
                    GROUP BY $colCat
                """.trimIndent()

                try {
                    d.rawQuery(sql, h3Cells.toTypedArray()).use { c ->
                        while (c.moveToNext()) {
                            val catVal = c.getString(0) ?: continue
                            val cnt = c.getInt(1)

                            val bucket = catVal.toIntOrNull() ?: (bucketOf(catVal) ?: continue)
                            if (bucket in 0..2) out[bucket] += cnt
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "H3_TABLE countCats failed.", t)
                }
                return out
            }

            Mode.LATLON_TABLE -> {
                for (cell in h3Cells) {
                    val v = memIndex[cell] ?: continue
                    out[0] += v[0]
                    out[1] += v[1]
                    out[2] += v[2]
                }
                return out
            }

            Mode.EMPTY -> return out
        }
    }

    /**
     * A single POI with its location and category bucket (0=parks, 1=residential, 2=busy).
     */
    data class PoiPoint(val lat: Double, val lon: Double, val bucket: Int)

    /**
     * Returns all POIs that fall inside any of the given H3 cells.
     * Used to show on the map why the route was chosen.
     */
    fun getPoisInCells(h3Cells: Set<String>): List<PoiPoint> {
        if (h3Cells.isEmpty()) return emptyList()
        val out = ArrayList<PoiPoint>()

        // For RESIDENTIAL only: expand the search area by 2 hex rings around
        // each route cell. This gives many more purple dots on the visible
        // route without affecting parks (green) or busy (yellow), which are
        // still restricted to the exact route cells.
        val residentialExpansionRings = 2
        val expandedResidentialCells = HashSet<String>(h3Cells.size * 8)
        for (cellStr in h3Cells) {
            try {
                val cellId = h3.stringToH3(cellStr)
                val disk = h3.gridDisk(cellId, residentialExpansionRings)
                for (idx in disk) {
                    expandedResidentialCells.add(h3.h3ToString(idx))
                }
            } catch (_: Throwable) {
                // Fallback: keep the original cell if anything goes wrong
                expandedResidentialCells.add(cellStr)
            }
        }

        when (mode) {
            Mode.H3_TABLE -> {
                val d = db ?: return out
                // Query with the expanded set so residential POIs from a wider area can be considered
                val queryCells = expandedResidentialCells
                val placeholders = queryCells.joinToString(",") { "?" }
                val sql = "SELECT $colH3, $colCat FROM $tableName WHERE $colH3 IN ($placeholders)"
                try {
                    d.rawQuery(sql, queryCells.toTypedArray()).use { c ->
                        while (c.moveToNext()) {
                            val cellStr = c.getString(0) ?: continue
                            val catVal  = c.getString(1) ?: continue
                            val bucket  = catVal.toIntOrNull() ?: (bucketOf(catVal) ?: continue)

                            // Filter: parks (0) and busy (2) must be in the original route cells.
                            // Residential (1) can come from the expanded set.
                            if (bucket != 1 && cellStr !in h3Cells) continue

                            val cellId = h3.stringToH3(cellStr)
                            val ll     = h3.cellToLatLng(cellId)
                            out.add(PoiPoint(ll.lat, ll.lng, bucket))
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "getPoisInCells H3_TABLE failed.", t)
                }
            }

            Mode.LATLON_TABLE -> {
                val d = db ?: return out
                val sql = "SELECT $colLat, $colLon, $colType FROM $tableName"
                try {
                    d.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) {
                            val lat  = c.getDouble(0)
                            val lon  = c.getDouble(1)
                            val type = c.getString(2) ?: continue
                            val bucket = bucketOf(type) ?: continue

                            val cellStr = h3.h3ToString(h3.latLngToCell(lat, lon, h3Res))

                            // Residential: include if in the expanded set.
                            // Other categories: include only if in the original route cells.
                            val include = if (bucket == 1) {
                                cellStr in expandedResidentialCells
                            } else {
                                cellStr in h3Cells
                            }

                            if (include) {
                                out.add(PoiPoint(lat, lon, bucket))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "getPoisInCells LATLON_TABLE failed.", t)
                }
            }

            Mode.EMPTY -> { /* nothing */ }
        }

        return out
    }

    private fun initSchemaAndMaybeBuildIndex() {
        val d = db
        if (d == null) {
            mode = Mode.EMPTY
            return
        }

        val tables = listUserTables(d)
        if (tables.isEmpty()) {
            mode = Mode.EMPTY
            return
        }

        // 1) Prefer an H3-ready table (h3 + cat/category)
        for (t in tables) {
            val cols = tableColumns(d, t)
            val h3Col = cols.firstOrNull { it.equals("h3", true) || it.contains("h3", true) }
            val catCol = cols.firstOrNull { it.equals("cat", true) || it.equals("category", true) }
            if (h3Col != null && catCol != null) {
                mode = Mode.H3_TABLE
                tableName = t
                colH3 = h3Col
                colCat = catCol
                Log.d(TAG, "Using H3 table: $tableName ($colH3, $colCat)")
                return
            }
        }

        // 2) Otherwise, find lat/lon table + type column and build in-memory H3 index
        for (t in tables) {
            val cols = tableColumns(d, t)

            val lat = cols.firstOrNull { it.equals("lat", true) || it.equals("latitude", true) }
            val lon = cols.firstOrNull { it.equals("lon", true) || it.equals("lng", true) || it.equals("longitude", true) }
            if (lat == null || lon == null) continue

            val type = cols.firstOrNull {
                it.equals("type", true) ||
                        it.equals("category", true) ||
                        it.equals("amenity", true) ||
                        it.equals("class", true) ||
                        it.equals("kind", true) ||
                        it.equals("tag", true) ||
                        it.equals("tags", true) ||
                        it.equals("leisure", true) ||
                        it.equals("landuse", true) ||
                        it.equals("natural", true) ||
                        it.equals("fclass", true)
            } ?: continue

            mode = Mode.LATLON_TABLE
            tableName = t
            colLat = lat
            colLon = lon
            colType = type

            Log.d(TAG, "Building in-memory H3 index from: $tableName ($colLat,$colLon,$colType) res=$h3Res")
            buildMemIndex()
            Log.d(TAG, "MemIndex ready: ${memIndex.size} H3 cells")
            return
        }

        mode = Mode.EMPTY
        Log.w(TAG, "No usable POI schema found (no h3+cat and no lat/lon+type).")
    }

    private fun buildMemIndex() {
        val d = db ?: return
        val sql = "SELECT $colLat, $colLon, $colType FROM $tableName"

        var rows = 0
        var kept = 0

        val uniqueTypes = HashSet<String>()

        try {
            d.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    rows++

                    val lat = c.getDouble(0)
                    val lon = c.getDouble(1)
                    val type = c.getString(2)

                    if (uniqueTypes.size < 100 && type != null) {
                        uniqueTypes.add(type)
                    }

                    val bucket = bucketOf(type)
                    if (bucket == null) {
                        debugUnknown++
                        continue
                    }

                    when (bucket) {
                        0 -> debugParksFound++
                        1 -> debugResFound++
                        2 -> debugBusyFound++
                    }

                    val cell = h3.latLngToCell(lat, lon, h3Res)
                    val key = h3.h3ToString(cell)

                    val arr = memIndex.getOrPut(key) { intArrayOf(0, 0, 0) }
                    arr[bucket]++
                    kept++
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "buildMemIndex failed.", t)
        }

        Log.d(TAG, "POI rows=$rows kept=$kept (mapped into 3 buckets)")
        Log.d(TAG, "POI breakdown: parks=$debugParksFound, residential=$debugResFound, busy=$debugBusyFound, unknown=$debugUnknown")
        Log.d(TAG, "Sample types in DB: ${uniqueTypes.take(50)}")
    }

    /**
     * IMPROVED bucket classification with many more OSM tags
     */
    private fun bucketOf(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.lowercase(Locale.US).trim()

        // ============ BUCKET 0: Parks / Green Spaces / Nature ============
        val parkExact = setOf(
            "park", "parks", "garden", "gardens", "forest", "wood", "woods",
            "beach", "beaches", "nature", "nature_reserve", "national_park",
            "recreation_ground", "grass", "meadow", "heath", "scrub",
            "wetland", "orchard", "vineyard", "allotments", "village_green",
            "dog_park", "playground", "pitch", "sports_centre", "stadium",
            "track", "swimming_pool", "water_park", "marina", "slipway",
            "picnic_site", "camp_site", "caravan_site", "wilderness_hut",
            "greenfield", "farmland", "farmyard", "cemetery", "grave_yard"
        )
        if (s in parkExact) return 0

        val parkContains = listOf(
            "park", "beach", "forest", "nature", "garden", "wood", "green",
            "grass", "meadow", "recreation", "leisure", "playground",
            "sports", "stadium", "pitch", "swimming", "pool", "picnic",
            "camp", "reserve", "botanical", "zoo", "aquarium", "trail",
            "promenade", "waterfront", "riverside", "lakeside", "seaside"
        )
        for (keyword in parkContains) {
            if (s.contains(keyword)) return 0
        }

        if (s.startsWith("leisure=") || s.startsWith("landuse=") || s.startsWith("natural=")) {
            val value = s.substringAfter("=")
            if (value in parkExact || parkContains.any { value.contains(it) }) return 0
        }

        // ============ BUCKET 1: Residential ============
        val residentialExact = setOf(
            // Building types
            "residential", "apartments", "apartment", "house", "houses",
            "detached", "semi-detached", "terrace", "terrace_house", "dormitory",
            "hostel", "hotel", "motel", "guest_house", "housing", "dwelling",
            "bungalow", "cabin", "chalet", "static_caravan", "houseboat",
            "flats", "flat", "condominium", "condo", "duplex", "studio",
            "maisonette", "villa", "manor", "retirement_home", "care_home",
            "sheltered_housing", "social_housing", "public_housing",
            // Land use / area types
            "neighbourhood", "neighborhood", "suburb", "village",
            "hamlet", "town", "city", "quarter", "block", "urban",
            "housing_estate", "estate", "complex", "compound",
            "garages", "garage", "allotment", "allotments",
            // Street / infrastructure context
            "living_street", "pedestrian", "footway", "path",
            "cycleway", "service", "unclassified",
            // Hebrew/Israeli OSM common tags
            "shikun", "shchuna", "kiryat", "ramat", "givat", "tel",
            "building", "buildings", "structure"
        )
        if (s in residentialExact) return 1

        val residentialContains = listOf(
            "residential", "apartment", "housing", "neighbourhood", "neighborhood",
            "dwelling", "home", "suburb", "village", "hamlet",
            "estate", "quarter", "bungalow", "villa", "flat", "condo",
            "living_street", "shikun", "shchuna", "kiryat", "ramat",
            "building", "house", "hostel", "dormitory", "shelter",
            "urban", "block", "complex", "compound"
        )
        for (keyword in residentialContains) {
            if (s.contains(keyword)) return 1
        }

        // ============ BUCKET 2: Busy / Commercial ============
        val busyExact = setOf(
            "shop", "shops", "store", "stores", "supermarket", "mall",
            "shopping_centre", "shopping_center", "retail", "commercial",
            "restaurant", "restaurants", "cafe", "cafes", "coffee",
            "bar", "bars", "pub", "pubs", "nightclub", "club",
            "fast_food", "food_court", "bakery", "butcher", "deli",
            "market", "marketplace", "bazaar", "convenience", "kiosk",
            "pharmacy", "chemist", "bank", "atm", "bureau_de_change",
            "cinema", "theatre", "theater", "museum", "gallery",
            "library", "community_centre", "social_facility",
            "office", "offices", "coworking", "business"
        )
        if (s in busyExact) return 2

        val busyContains = listOf(
            "shop", "store", "market", "mall", "retail", "commercial",
            "restaurant", "cafe", "coffee", "bar", "pub", "club",
            "food", "bakery", "pharmacy", "bank", "cinema", "theatre",
            "museum", "gallery", "office", "business", "supermarket"
        )
        for (keyword in busyContains) {
            if (s.contains(keyword)) return 2
        }

        return null
    }

    private fun logDatabaseStats() {
        val d = db ?: return

        try {
            val countCursor = d.rawQuery("SELECT COUNT(*) FROM $tableName", null)
            countCursor.use {
                if (it.moveToFirst()) {
                    Log.d(TAG, "Total POI rows in $tableName: ${it.getInt(0)}")
                }
            }

            if (colType.isNotEmpty()) {
                val typesCursor = d.rawQuery(
                    "SELECT DISTINCT $colType, COUNT(*) as cnt FROM $tableName GROUP BY $colType ORDER BY cnt DESC LIMIT 30",
                    null
                )
                typesCursor.use { c ->
                    val types = ArrayList<String>()
                    while (c.moveToNext()) {
                        val type = c.getString(0) ?: "null"
                        val count = c.getInt(1)
                        types.add("$type($count)")
                    }
                    Log.d(TAG, "Top POI types: $types")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "logDatabaseStats failed", t)
        }
    }

    private fun listUserTables(db: SQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        val sql = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'"
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(nameIdx))
        }
        return out
    }
}