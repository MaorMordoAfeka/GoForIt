package com.example.goforitGit.map_routes_module

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object GraphDataInstaller {
    private const val ZIP_ASSET = "gh/israel_foot_graph_1.zip"
    private const val TARGET_DIR = "gh_graph"

    fun graphDir(ctx: Context): File = File(ctx.filesDir, TARGET_DIR)

    fun ensureInstalled(ctx: Context) {
        val dir = graphDir(ctx)
        val marker = File(dir, "graph.lock")
        if (marker.exists()) return

        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        ctx.assets.open(ZIP_ASSET).use { input ->
            ZipInputStream(input).use { zis ->
                val buffer = ByteArray(64 * 1024)
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(dir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        marker.writeText("ok")
    }
}
