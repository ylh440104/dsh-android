package com.deepseek.harness.tools

import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class SearchTools(private val workspace: File) {

    fun grepCode(
        path: String,
        pattern: String,
        filePattern: String,
        caseInsensitive: Boolean,
        contextLines: Int,
        maxResults: Int
    ): ToolOutcome {
        if (pattern.isBlank()) return ToolOutcome.Err("pattern parameter is required")
        val root = PathGuard.normalize(path)
        if (PathGuard.isProtected(root)) return ToolOutcome.Err("Refusing to search protected path: $root")
        val base = PathGuard.toFile(workspace, root)
        if (!base.exists()) return ToolOutcome.Err("No such path: $root")

        val regex = try {
            Pattern.compile(pattern, if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0)
        } catch (e: PatternSyntaxException) {
            return ToolOutcome.Err("Invalid regex: ${e.message}")
        }
        val nameFilter = if (filePattern.isBlank() || filePattern == "*") null else globToRegex(filePattern, caseInsensitive)

        val hits = mutableListOf<String>()
        val files = mutableListOf<File>()
        collectFiles(base, nameFilter, files, 0)
        var total = 0

        for (file in files) {
            if (total >= maxResults) break
            if (file.length() > MAX_FILE_BYTES) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for ((index, line) in lines.withIndex()) {
                if (total >= maxResults) break
                if (!regex.matcher(line).find()) continue
                total++
                val from = (index - contextLines).coerceAtLeast(0)
                val to = (index + contextLines).coerceAtMost(lines.size - 1)
                hits.add("${file.absolutePath}:${index + 1}")
                for (i in from..to) {
                    val marker = if (i == index) ">" else " "
                    hits.add("$marker ${i + 1}: ${lines[i]}")
                }
                hits.add("")
            }
        }

        if (hits.isEmpty()) return ToolOutcome.Ok("No matches for '$pattern' under $root")
        val sb = StringBuilder()
        sb.append("Found $total match(es) for '$pattern'\n\n")
        sb.append(hits.joinToString("\n"))
        if (total >= maxResults) sb.append("\n… reached max_results limit ($maxResults)")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun grepContext(path: String, intent: String, maxResults: Int): ToolOutcome {
        if (intent.isBlank()) return ToolOutcome.Err("intent parameter is required")
        val keywords = intent.split(Regex("[\\s,，、]+"))
            .map { it.trim() }
            .filter { it.length > 1 }
            .distinct()
        if (keywords.isEmpty()) return ToolOutcome.Err("No usable keywords in intent")

        val root = PathGuard.normalize(path)
        if (PathGuard.isProtected(root)) return ToolOutcome.Err("Refusing to search protected path: $root")
        val base = PathGuard.toFile(workspace, root)
        if (!base.exists()) return ToolOutcome.Err("No such path: $root")

        val files = mutableListOf<File>()
        collectFiles(base, null, files, 0)

        data class Hit(val file: File, val line: Int, val text: String, val score: Int)
        val scored = mutableListOf<Hit>()

        for (file in files) {
            if (file.length() > MAX_FILE_BYTES) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for ((index, line) in lines.withIndex()) {
                var score = 0
                for (kw in keywords) {
                    if (line.contains(kw, ignoreCase = true)) score += kw.length
                }
                if (score > 0) scored.add(Hit(file, index, line.trim(), score))
            }
        }

        if (scored.isEmpty()) return ToolOutcome.Ok("Nothing relevant found under $root")
        val top = scored.sortedByDescending { it.score }.take(maxResults)
        val sb = StringBuilder()
        sb.append("Top ${top.size} relevant line(s) for: ${keywords.joinToString(", ")}\n\n")
        for (hit in top) {
            sb.append(hit.file.absolutePath).append(':').append(hit.line + 1).append('\n')
            sb.append("  ").append(hit.text.take(300)).append("\n\n")
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    private fun collectFiles(dir: File, nameFilter: Regex?, out: MutableList<File>, depth: Int) {
        if (out.size >= MAX_FILES) return
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (out.size >= MAX_FILES) return
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                collectFiles(entry, nameFilter, out, depth + 1)
            } else {
                if (nameFilter == null || nameFilter.matches(entry.name)) out.add(entry)
            }
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
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_FILES = 3000
        private const val MAX_DEPTH = 12
    }
}