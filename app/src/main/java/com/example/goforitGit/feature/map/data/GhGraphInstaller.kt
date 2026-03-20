package com.example.goforitGit.feature.map.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object  GhGraphInstaller {
    private const val TAG = "GhGraphInstaller"
    private const val ASSET_ZIP = "gh/israel_foot_graph_1.zip"

    fun installIfNeeded(ctx: Context): File {
        val outRoot = File(ctx.filesDir, "gh_graph")

        // Force delete and re-extract
        if (outRoot.exists()) {
            outRoot.deleteRecursively()
        }

        outRoot.mkdirs()

        val marker = File(outRoot, ".installed")

        if (marker.exists() && outRoot.exists()) {
            return resolveGraphDir(outRoot)
        }

        outRoot.mkdirs()

        Log.d(TAG, "Unzipping $ASSET_ZIP -> ${outRoot.absolutePath}")
        ZipInputStream(ctx.assets.open(ASSET_ZIP)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(outRoot, entry.name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = zis.read(buf)
                            if (read <= 0) break
                            fos.write(buf, 0, read)
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        marker.writeText("ok")
        return resolveGraphDir(outRoot)
    }

    private fun resolveGraphDir(root: File): File {
        if (looksLikeGraphFolder(root)) return root
        val kids = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (kids.size == 1 && looksLikeGraphFolder(kids[0])) return kids[0]
        return root
    }

    private fun looksLikeGraphFolder(dir: File): Boolean {
        val names = setOf("edges", "nodes", "geometry", "location_index", "string_index")
        val files = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        if (files.intersect(names).isNotEmpty()) return true
        if (File(dir, "graphhopper.properties").exists()) return true
        return false
    }
}
