package com.example.goforitGit.map_routes_module

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object PoiDbInstaller {
    private const val TAG = "PoiDbInstaller"
    private const val LOCAL_DB_NAME = "poi_h3.db"

    fun installIfNeeded(ctx: Context): File {
        val dbFile = ctx.getDatabasePath(LOCAL_DB_NAME)
        if (dbFile.exists() && dbFile.length() > 0) return dbFile

        dbFile.parentFile?.mkdirs()

        val exts = listOf(".db", ".sqlite", ".sqlite3")

        // 1) root asset "poi_h3.db" (optional)
        val rootAsset = try {
            (ctx.assets.list("") ?: emptyArray()).firstOrNull { it == "poi_h3.db" }
        } catch (_: Throwable) { null }

        // 2) else pick first DB-like file in assets/poi/
        val poiAsset = if (rootAsset == null) {
            val files = try { ctx.assets.list("poi") ?: emptyArray() } catch (_: Throwable) { emptyArray() }
            val first = files.firstOrNull { f -> exts.any { e -> f.endsWith(e, ignoreCase = true) } }
            first?.let { "poi/$it" }
        } else {
            "poi_h3.db"
        }

        if (poiAsset != null) {
            try {
                Log.d(TAG, "Copying POI asset: $poiAsset -> ${dbFile.path}")
                ctx.assets.open(poiAsset).use { input ->
                    FileOutputStream(dbFile).use { output -> input.copyTo(output) }
                }
                return dbFile
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy POI asset, will create empty fallback.", t)
            }
        } else {
            Log.w(TAG, "No POI sqlite found in assets/poi/. Creating empty fallback DB.")
        }

        // 3) fallback empty DB so app still runs
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS poi (
                h3  TEXT NOT NULL,
                cat INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_h3 ON poi(h3);")
        db.close()

        return dbFile
    }
}
