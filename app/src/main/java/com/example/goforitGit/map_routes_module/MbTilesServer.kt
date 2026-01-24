package com.example.goforitGit.map_routes_module

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File

class MbTilesServer(
    private val context: Context,
    private val mbtilesAssetName: String = "israel.mbtiles",
    port: Int = 8080
) : NanoHTTPD(port) {

    private var db: SQLiteDatabase? = null
    private var flipY: Boolean = true

    fun startServerSafely(): Boolean {
        return try {
            val file = copyAssetToFiles(mbtilesAssetName)
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            flipY = detectFlipY(db)
            Log.d("MbTilesServer", "Started. flipY=$flipY file=${file.absolutePath}")
            start(SOCKET_READ_TIMEOUT, false)
            true
        } catch (e: Exception) {
            Log.e("MbTilesServer", "FAILED to start server", e)
            false
        }
    }

    fun stopServerSafely() {
        try { db?.close() } catch (_: Exception) {}
        db = null
        try { stop() } catch (_: Exception) {}
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health") {
            val txt = "ok flipY=$flipY"
            return newFixedLengthResponse(Response.Status.OK, "text/plain", txt)
        }

        // /tiles/{z}/{x}/{y}.pbf
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "tiles") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        val z = parts[1].toIntOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad z")
        val x = parts[2].toIntOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad x")
        val yStr = parts[3].removeSuffix(".pbf").removeSuffix(".mvt")
        val y = yStr.toIntOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad y")

        val database = db ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "db not ready")

        val tileRow = if (flipY) ((1 shl z) - 1 - y) else y

        val cursor = database.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tileRow.toString())
        )

        cursor.use {
            if (!it.moveToFirst()) {
                Log.d("MbTilesServer", "MISS z=$z x=$x y=$y row=$tileRow flipY=$flipY")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
            }

            val blob = it.getBlob(0)
            Log.d("MbTilesServer", "HIT  z=$z x=$x y=$y row=$tileRow bytes=${blob.size} flipY=$flipY")

            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "application/x-protobuf",
                ByteArrayInputStream(blob),
                blob.size.toLong()
            )

            if (isGzip(blob)) resp.addHeader("Content-Encoding", "gzip")
            return resp
        }
    }

    private fun detectFlipY(database: SQLiteDatabase?): Boolean {
        if (database == null) return true
        return try {
            val c = database.rawQuery("SELECT value FROM metadata WHERE name='scheme' LIMIT 1", null)
            c.use {
                if (!it.moveToFirst()) return true
                val scheme = it.getString(0)?.lowercase()?.trim() ?: return true
                scheme != "xyz"
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    }

    private fun copyAssetToFiles(name: String): File {
        val outFile = File(context.filesDir, name)
        if (outFile.exists() && outFile.length() > 0) return outFile

        context.assets.open(name).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }
}
