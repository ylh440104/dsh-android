package com.deepseek.harness.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class WebTools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun visitWeb(url: String, includeLinks: Boolean, maxChars: Int): ToolOutcome {
        if (url.isBlank()) return ToolOutcome.Err("url parameter is required")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome.Err("url must start with http:// or https://")
        }
        return try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val contentType = response.header("content-type").orEmpty()
                val limit = maxChars.coerceIn(1000, 200_000)

                if (!contentType.contains("html", ignoreCase = true)) {
                    val body = if (raw.length > limit) raw.take(limit) + "\n…(truncated)" else raw
                    return ToolOutcome.Ok("HTTP ${response.code}  ${contentType.ifBlank { "unknown type" }}\n\n$body")
                }

                val title = extractTitle(raw)
                val text = htmlToText(raw)
                val links = if (includeLinks) extractLinks(raw, url) else emptyList()

                val sb = StringBuilder()
                sb.append("HTTP ").append(response.code).append('\n')
                sb.append("url: ").append(response.request.url).append('\n')
                if (title.isNotBlank()) sb.append("title: ").append(title).append('\n')
                sb.append('\n')
                sb.append(if (text.length > limit) text.take(limit) + "\n…(truncated)" else text)
                if (links.isNotEmpty()) {
                    sb.append("\n\nLinks:\n")
                    for ((index, link) in links.withIndex()) {
                        sb.append('[').append(index + 1).append("] ").append(link).append('\n')
                    }
                }
                ToolOutcome.Ok(sb.toString().trimEnd())
            }
        } catch (e: Exception) {
            ToolOutcome.Err("Failed to fetch page: ${e.message}")
        }
    }

    fun downloadFile(url: String, destination: String): ToolOutcome {
        if (url.isBlank()) return ToolOutcome.Err("url parameter is required")
        if (destination.isBlank()) return ToolOutcome.Err("destination parameter is required")
        val target = File(PathGuard.normalize(destination))
        if (PathGuard.isProtected(PathGuard.normalize(destination))) {
            return ToolOutcome.Err("Refusing to write protected path: ${target.path}")
        }
        return try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolOutcome.Err("Download failed with HTTP ${response.code}")
                }
                val body = response.body ?: return ToolOutcome.Err("Empty response body")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            ToolOutcome.Ok("Downloaded to ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            ToolOutcome.Err("Download failed: ${e.message}")
        }
    }

    private fun extractTitle(html: String): String {
        val match = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            .matcher(html)
        return if (match.find()) decodeEntities(match.group(1)).trim() else ""
    }

    private fun htmlToText(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        text = text.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        text = text.replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        text = text.replace(Regex("(?is)<svg[^>]*>.*?</svg>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|header|footer)>"), "\n")
        text = text.replace(Regex("(?i)<li[^>]*>"), "\n- ")
        text = text.replace(Regex("<[^>]*>"), " ")
        text = decodeEntities(text)
        val lines = text.lines()
            .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val out = StringBuilder()
        var blank = false
        for (line in lines) {
            if (line.isEmpty()) {
                if (!blank) {
                    out.append('\n')
                    blank = true
                }
            } else {
                out.append(line).append('\n')
                blank = false
            }
        }
        return out.toString().trim()
    }

    private fun decodeEntities(text: String): String {
        var out = text
        out = out.replace("&nbsp;", " ")
        out = out.replace("&amp;", "&")
        out = out.replace("&lt;", "<")
        out = out.replace("&gt;", ">")
        out = out.replace("&quot;", String(Character.toChars(34)))
        out = out.replace("&#39;", "'")
        out = out.replace("&apos;", String(Character.toChars(39)))
        out = out.replace("&mdash;", "—")
        out = out.replace("&ndash;", "–")
        out = out.replace("&hellip;", "…")
        val numeric = Regex("&#(x?)([0-9a-fA-F]+);")
        out = numeric.replace(out) { match ->
            val hex = match.groupValues[1].isNotEmpty()
            val code = match.groupValues[2].toIntOrNull(if (hex) 16 else 10)
            if (code == null) match.value else String(Character.toChars(code))
        }
        return out
    }

    private fun extractLinks(html: String, baseUrl: String): List<String> {
        val pattern = Pattern.compile("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        val seen = LinkedHashSet<String>()
        while (matcher.find() && seen.size < 60) {
            val href = matcher.group(1).trim()
            if (href.isEmpty()) continue
            if (href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) continue
            val absolute = absolutize(baseUrl, href)
            if (absolute != null) seen.add(absolute)
        }
        return seen.toList()
    }

    private fun absolutize(base: String, href: String): String? {
        return try {
            if (href.startsWith("http://") || href.startsWith("https://")) {
                href
            } else if (href.startsWith("//")) {
                val scheme = if (base.startsWith("https")) "https:" else "http:"
                "$scheme$href"
            } else {
                val baseUri = java.net.URI(base)
                baseUri.resolve(href).toString()
            }
        } catch (e: Exception) {
            null
        }
    }
}