package com.deepseek.harness.tools

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveTools(private val workspace: File) {

    fun zipFiles(sources: List<String>, destination: String): ToolOutcome {
        if (sources.isEmpty()) return ToolOutcome.Err("sources parameter is required")
        val destPath = PathGuard.normalize(destination)
        if (PathGuard.isProtected(destPath)) return ToolOutcome.Err("Refusing to write protected path: $destPath")
        val dest = PathGuard.toFile(workspace, destPath)
        val files = sources.map { PathGuard.toFile(workspace, PathGuard.normalize(it)) }
        for (file in files) {
            if (!file.exists()) return ToolOutcome.Err("No such file or directory: ${file.path}")
        }
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                for (file in files) {
                    if (file.isDirectory) {
                        addDirectory(file, file.name, zos)
                    } else {
                        addFile(file, file.name, zos)
                    }
                }
            }
            ToolOutcome.Ok("Created ${dest.path} (${dest.length()} bytes) from ${files.size} source(s)")
        } catch (e: Exception) {
            ToolOutcome.Err("Zip failed: ${e.message}")
        }
    }

    fun unzipFiles(source: String, destination: String): ToolOutcome {
        val srcPath = PathGuard.normalize(source)
        val src = PathGuard.toFile(workspace, srcPath)
        if (!src.exists()) return ToolOutcome.Err("No such archive: $srcPath")
        val destDir = if (destination.isBlank()) {
            src.parentFile ?: workspace
        } else {
            PathGuard.toFile(workspace, PathGuard.normalize(destination))
        }
        return try {
            destDir.mkdirs()
            var count = 0
            ZipInputStream(FileInputStream(src)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.contains("..")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val target = File(destDir, name)
                    val canonical = target.canonicalFile
                    val rootCanonical = destDir.canonicalFile
                    if (!canonical.path.startsWith(rootCanonical.path)) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zis.copyTo(out) }
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ToolOutcome.Ok("Extracted $count file(s) to ${destDir.path}")
        } catch (e: Exception) {
            ToolOutcome.Err("Unzip failed: ${e.message}")
        }
    }

    fun listZip(source: String): ToolOutcome {
        val srcPath = PathGuard.normalize(source)
        val src = PathGuard.toFile(workspace, srcPath)
        if (!src.exists()) return ToolOutcome.Err("No such archive: $srcPath")
        return try {
            val sb = StringBuilder()
            var count = 0
            ZipInputStream(FileInputStream(src)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    sb.append(if (entry.isDirectory) "[dir]  " else "[file] ")
                    sb.append(entry.name)
                    if (!entry.isDirectory) sb.append("  (").append(entry.size).append(" bytes)")
                    sb.append('\n')
                    count++
                    if (count >= MAX_ENTRIES) {
                        sb.append("… truncated at $MAX_ENTRIES entries\n")
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ToolOutcome.Ok("Archive $srcPath contains $count entr(ies):\n${sb.toString().trimEnd()}")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot read archive: ${e.message}")
        }
    }

    private fun addDirectory(dir: File, baseName: String, zos: ZipOutputStream) {
        val entries = dir.listFiles() ?: return
        if (entries.isEmpty()) {
            zos.putNextEntry(ZipEntry("$baseName/"))
            zos.closeEntry()
            return
        }
        for (entry in entries) {
            val name = "$baseName/${entry.name}"
            if (entry.isDirectory) addDirectory(entry, name, zos) else addFile(entry, name, zos)
        }
    }

    private fun addFile(file: File, name: String, zos: ZipOutputStream) {
        if (file.length() > MAX_ENTRY_BYTES) return
        FileInputStream(file).use { input ->
            zos.putNextEntry(ZipEntry(name))
            input.copyTo(zos)
            zos.closeEntry()
        }
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
        private const val MAX_ENTRIES = 500
    }
}