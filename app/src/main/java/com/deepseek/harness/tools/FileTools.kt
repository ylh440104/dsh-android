package com.deepseek.harness.tools

import java.io.File

class FileTools(private val workspace: File) {

    init {
        if (!workspace.exists()) workspace.mkdirs()
    }

    private fun resolve(raw: String): File = PathGuard.toFile(workspace, PathGuard.normalize(raw))

    private fun blocked(path: String): ToolOutcome.Err? =
        if (PathGuard.isProtected(path)) {
            ToolOutcome.Err("Refusing to touch protected system path: $path")
        } else {
            null
        }

    fun listFiles(raw: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        val dir = resolve(raw)
        if (!dir.exists()) return ToolOutcome.Err("No such directory: $path")
        if (!dir.isDirectory) return ToolOutcome.Err("Not a directory: $path")
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return ToolOutcome.Err("Cannot read directory: $path")
        if (entries.isEmpty()) return ToolOutcome.Ok("(empty directory) $path")
        val sb = StringBuilder()
        sb.append("Directory listing for $path:\n")
        for (entry in entries) {
            sb.append(if (entry.isDirectory) "[dir]  " else "[file] ")
            sb.append(entry.name)
            if (entry.isFile) sb.append("  (").append(entry.length()).append(" bytes)")
            sb.append('\n')
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun readFile(raw: String, startLine: Int, endLine: Int): ToolOutcome {
        val path = PathGuard.normalize(raw)
        val file = resolve(raw)
        if (!file.exists()) return ToolOutcome.Err("No such file: $path")
        if (file.isDirectory) return ToolOutcome.Err("'$path' is a directory, use list_files instead")
        if (file.length() > MAX_BYTES) {
            return ToolOutcome.Err("File too large (${file.length()} bytes). Use read_file_part with start_line/end_line.")
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return ToolOutcome.Err("Cannot read file: ${e.message}")
        }
        val lines = text.split('\n')
        val from = startLine.coerceAtLeast(1)
        if (from > lines.size) {
            return ToolOutcome.Err("start_line $from is beyond end of file (${lines.size} lines)")
        }
        val to = endLine.coerceAtMost(lines.size)
        if (to < from) return ToolOutcome.Err("end_line must be >= start_line")
        val sb = StringBuilder()
        sb.append("Content of $path (lines $from-$to of ${lines.size}):\n")
        for (i in from..to) {
            sb.append(i).append('\t').append(lines[i - 1]).append('\n')
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun writeFile(raw: String, content: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        val file = resolve(raw)
        if (file.isDirectory) return ToolOutcome.Err("'$path' is a directory")
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolOutcome.Ok("Wrote ${content.toByteArray().size} bytes to $path")
        } catch (e: Exception) {
            ToolOutcome.Err("Write failed: ${e.message}")
        }
    }

    fun applyEdit(raw: String, oldText: String, newText: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        val file = resolve(raw)
        if (!file.exists()) return ToolOutcome.Err("No such file: $path. Use create_file to create it.")
        if (file.isDirectory) return ToolOutcome.Err("'$path' is a directory")
        if (oldText.isEmpty()) return ToolOutcome.Err("'old' must not be empty")
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return ToolOutcome.Err("Cannot read file: ${e.message}")
        }
        val first = text.indexOf(oldText)
        if (first < 0) {
            return ToolOutcome.Err("'old' text was not found in $path. Read the file again and copy the exact content.")
        }
        if (text.indexOf(oldText, first + 1) >= 0) {
            return ToolOutcome.Err("'old' text appears more than once in $path. Include more surrounding context to make it unique.")
        }
        return try {
            file.writeText(text.replaceFirst(oldText, newText))
            val line = text.substring(0, first).count { it == '\n' } + 1
            ToolOutcome.Ok("Edited $path at line $line (${oldText.length} -> ${newText.length} chars)")
        } catch (e: Exception) {
            ToolOutcome.Err("Write failed: ${e.message}")
        }
    }

    fun createFile(raw: String, content: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        val file = resolve(raw)
        if (file.exists()) return ToolOutcome.Err("File already exists: $path. Use edit_file to modify it.")
        return writeFile(raw, content)
    }

    fun deleteFile(raw: String, recursive: Boolean): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        if (path == PathGuard.SANDBOX_ROOT) return ToolOutcome.Err("Refusing to delete the workspace root")
        val file = resolve(raw)
        if (!file.exists()) return ToolOutcome.Err("No such file or directory: $path")
        if (file.isDirectory && !recursive) {
            return ToolOutcome.Err("'$path' is a directory. Pass recursive=true to delete it.")
        }
        val deleted = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            false
        }
        return if (deleted) ToolOutcome.Ok("Deleted $path") else ToolOutcome.Err("Delete failed: $path")
    }

    fun makeDirectory(raw: String, createParents: Boolean): ToolOutcome {
        val path = PathGuard.normalize(raw)
        blocked(path)?.let { return it }
        val dir = resolve(raw)
        if (dir.exists()) {
            return if (dir.isDirectory) ToolOutcome.Ok("Directory already exists: $path")
            else ToolOutcome.Err("A file already exists at $path")
        }
        val ok = try {
            if (createParents) dir.mkdirs() else dir.mkdir()
        } catch (e: Exception) {
            false
        }
        return if (ok) ToolOutcome.Ok("Created directory $path")
        else ToolOutcome.Err("Could not create directory: $path (missing parent? pass create_parents=true)")
    }

    fun fileExists(raw: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        val file = resolve(raw)
        return if (file.exists()) {
            val kind = if (file.isDirectory) "Directory" else "File"
            val size = if (file.isFile) "${file.length()} bytes" else "directory"
            ToolOutcome.Ok("$kind exists at $path ($size)")
        } else {
            ToolOutcome.Ok("Nothing exists at $path")
        }
    }

    fun fileInfo(raw: String): ToolOutcome {
        val path = PathGuard.normalize(raw)
        val file = resolve(raw)
        if (!file.exists()) return ToolOutcome.Err("No such file or directory: $path")
        val sb = StringBuilder()
        sb.append("File information for $path:\n")
        sb.append("Type: ").append(if (file.isDirectory) "directory" else "file").append('\n')
        sb.append("Size: ").append(file.length()).append(" bytes\n")
        sb.append("Readable: ").append(file.canRead()).append('\n')
        sb.append("Writable: ").append(file.canWrite()).append('\n')
        sb.append("Executable: ").append(file.canExecute()).append('\n')
        sb.append("Last modified: ").append(java.util.Date(file.lastModified())).append('\n')
        sb.append("Sandboxed: ").append(PathGuard.inSandbox(path))
        return ToolOutcome.Ok(sb.toString())
    }

    fun findFiles(rawRoot: String, pattern: String, maxDepth: Int, caseInsensitive: Boolean): ToolOutcome {
        if (pattern.isBlank()) return ToolOutcome.Err("pattern parameter is required")
        val root = PathGuard.normalize(rawRoot)
        blocked(root)?.let { return it }
        val base = resolve(rawRoot)
        if (!base.exists()) return ToolOutcome.Err("No such directory: $root")
        val regex = globToRegex(pattern, caseInsensitive)
        val matches = mutableListOf<File>()
        collect(base, regex, maxDepth, 0, matches)
        if (matches.isEmpty()) return ToolOutcome.Ok("No files matching '$pattern' under $root")
        val sb = StringBuilder()
        sb.append("Found ${matches.size} match(es) for '$pattern':\n")
        for (m in matches.take(MAX_FIND_RESULTS)) {
            sb.append(PathGuard.normalize(m.absolutePath))
            if (m.isFile) sb.append("  (").append(m.length()).append(" bytes)")
            sb.append('\n')
        }
        if (matches.size > MAX_FIND_RESULTS) {
            sb.append("... and ${matches.size - MAX_FIND_RESULTS} more")
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    private fun collect(dir: File, regex: Regex, maxDepth: Int, depth: Int, out: MutableList<File>) {
        if (out.size >= MAX_FIND_RESULTS * 2) return
        if (maxDepth >= 0 && depth > maxDepth) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            if (regex.matches(entry.name)) out.add(entry)
            if (entry.isDirectory) collect(entry, regex, maxDepth, depth + 1, out)
        }
    }

    private fun globToRegex(glob: String, caseInsensitive: Boolean): Regex {
        val sb = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        val options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(sb.toString(), options)
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024L
        private const val MAX_FIND_RESULTS = 200
    }
}