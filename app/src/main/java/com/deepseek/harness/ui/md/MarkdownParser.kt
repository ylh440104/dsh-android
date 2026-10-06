package com.deepseek.harness.ui.md

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val ORDERED = Regex("^\\s*\\d+[.)]\\s+")

    fun parse(source: String): List<MdBlock> {
        val lines = source.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            val fence = fenceOf(trimmed)
            if (fence != null) {
                val language = trimmed.removePrefix(fence).trim()
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim().startsWith(fence)) {
                        closed = true
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[j])
                    j++
                }
                blocks.add(MdBlock.Code(language, body.toString(), closed))
                i = if (closed) j + 1 else j
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                blocks.add(MdBlock.Heading(heading.groupValues[1].length.coerceIn(1, 6), heading.groupValues[2].trim()))
                i++
                continue
            }

            if (RULE.matches(trimmed)) {
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quote = StringBuilder()
                var j = i
                while (j < lines.size && lines[j].trim().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[j].trim().removePrefix(">").trim())
                    j++
                }
                blocks.add(MdBlock.Quote(quote.toString()))
                i = j
                continue
            }

            if (isTableRow(trimmed) && i + 1 < lines.size && isTableDivider(lines[i + 1].trim())) {
                val header = splitRow(trimmed)
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && isTableRow(lines[j].trim())) {
                    rows.add(splitRow(lines[j].trim()))
                    j++
                }
                blocks.add(MdBlock.Table(header, rows))
                i = j
                continue
            }

            val bullet = BULLET.find(trimmed)
            if (bullet != null && bullet.range.first == 0) {
                val items = mutableListOf<MdListItem>()
                var j = i
                while (j < lines.size) {
                    val t = lines[j].trim()
                    val m = BULLET.find(t) ?: break
                    if (m.range.first != 0) break
                    val indent = t.takeWhile { it == ' ' || it == '\t' }.length
                    items.add(MdListItem(t.substring(m.value.length).trim(), indent / 2))
                    j++
                }
                blocks.add(MdBlock.Bullet(items))
                i = j
                continue
            }

            val ordered = ORDERED.find(trimmed)
            if (ordered != null && ordered.range.first == 0) {
                val items = mutableListOf<MdListItem>()
                var j = i
                while (j < lines.size) {
                    val t = lines[j].trim()
                    val m = ORDERED.find(t) ?: break
                    if (m.range.first != 0) break
                    items.add(MdListItem(t.substring(m.value.length).trim(), 0))
                    j++
                }
                blocks.add(MdBlock.Ordered(items))
                i = j
                continue
            }

            val paragraph = StringBuilder()
            var j = i
            while (j < lines.size) {
                val tt = lines[j].trim()
                if (tt.isEmpty() || fenceOf(tt) != null || HEADING.matches(tt) || RULE.matches(tt) ||
                    tt.startsWith(">") || isTableRow(tt) ||
                    (BULLET.find(tt)?.range?.first == 0) || (ORDERED.find(tt)?.range?.first == 0)
                ) {
                    break
                }
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(tt)
                j++
            }
            if (paragraph.isNotEmpty()) blocks.add(MdBlock.Paragraph(paragraph.toString()))
            i = if (j > i) j else i + 1
        }
        return blocks
    }

    private fun fenceOf(line: String): String? = when {
        line.startsWith("```") -> "```"
        line.startsWith("~~~") -> "~~~"
        else -> null
    }

    private fun isTableRow(line: String): Boolean = line.startsWith("|") && line.length > 1

    private fun isTableDivider(line: String): Boolean =
        line.isNotEmpty() && line.all { it == '|' || it == '-' || it == ':' || it == ' ' } && line.contains('-')

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    fun parseInline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buffer = StringBuilder()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(MdSpan(buffer.toString()))
                buffer.clear()
            }
        }

        while (i < text.length) {
            val rest = text.substring(i)

            if (rest.startsWith("`")) {
                val end = rest.indexOf('`', 1)
                if (end > 1) {
                    flush()
                    out.add(MdSpan(rest.substring(1, end), code = true))
                    i += end + 1
                    continue
                }
            }

            if (rest.startsWith("**") || rest.startsWith("__")) {
                val marker = rest.take(2)
                val end = rest.indexOf(marker, 2)
                if (end > 2) {
                    flush()
                    out.add(MdSpan(rest.substring(2, end), bold = true))
                    i += end + 2
                    continue
                }
            }

            if (rest.startsWith("~~")) {
                val end = rest.indexOf("~~", 2)
                if (end > 2) {
                    flush()
                    out.add(MdSpan(rest.substring(2, end), strike = true))
                    i += end + 2
                    continue
                }
            }

            if (rest.startsWith("*") || rest.startsWith("_")) {
                val marker = rest[0]
                val end = rest.indexOf(marker, 1)
                if (end > 1 && !rest.substring(1, end).contains(' ')) {
                    flush()
                    out.add(MdSpan(rest.substring(1, end), italic = true))
                    i += end + 1
                    continue
                }
            }

            buffer.append(text[i])
            i++
        }
        flush()
        return out
    }
}