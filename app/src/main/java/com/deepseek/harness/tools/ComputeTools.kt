package com.deepseek.harness.tools

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

class ComputeTools {

    fun calculate(expression: String, precision: Int): ToolOutcome {
        if (expression.isBlank()) return ToolOutcome.Err("expression parameter is required")
        return try {
            val parser = ExpressionParser(expression)
            val value = parser.parse()
            val digits = precision.coerceIn(0, 12)
            val text = if (digits == 0) {
                if (value == value.toLong().toDouble()) value.toLong().toString()
                else String.format(Locale.US, "%.$digits" + "f", value)
            } else {
                java.math.BigDecimal(value).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()
            }
            ToolOutcome.Ok("$expression = $text")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot evaluate expression: ${e.message}")
        }
    }

    fun base64Encode(text: String): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        val encoded = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        return ToolOutcome.Ok(encoded)
    }

    fun base64Decode(text: String): ToolOutcome {
        if (text.isBlank()) return ToolOutcome.Err("text parameter is required")
        return try {
            val bytes = android.util.Base64.decode(text.trim(), android.util.Base64.DEFAULT)
            ToolOutcome.Ok(String(bytes))
        } catch (e: Exception) {
            ToolOutcome.Err("Invalid base64 input: ${e.message}")
        }
    }

    fun hash(text: String, algorithm: String): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        val algo = when (algorithm.lowercase().replace("-", "")) {
            "", "sha256" -> "SHA-256"
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha512" -> "SHA-512"
            "sha384" -> "SHA-384"
            "sha224" -> "SHA-224"
            else -> return ToolOutcome.Err("Unsupported algorithm: $algorithm")
        }
        return try {
            val digest = MessageDigest.getInstance(algo)
            val bytes = digest.digest(text.toByteArray())
            val hex = bytes.joinToString("") { "%02x".format(it) }
            ToolOutcome.Ok("$algo($text) = $hex")
        } catch (e: Exception) {
            ToolOutcome.Err("Hash failed: ${e.message}")
        }
    }

    fun uuid(count: Int): ToolOutcome {
        val n = count.coerceIn(1, 50)
        val sb = StringBuilder()
        repeat(n) { sb.append(UUID.randomUUID().toString()).append('\n') }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun randomNumber(minValue: Long, maxValue: Long, count: Int): ToolOutcome {
        if (minValue > maxValue) return ToolOutcome.Err("min must not exceed max")
        val n = count.coerceIn(1, 200)
        val sb = StringBuilder()
        repeat(n) { sb.append(Random.nextLong(minValue, maxValue + 1)).append('\n') }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun textTransform(text: String, operation: String, argument: String): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        val result = when (operation.lowercase()) {
            "upper" -> text.uppercase()
            "lower" -> text.lowercase()
            "capitalize" -> text.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            "trim" -> text.trim()
            "reverse" -> text.reversed()
            "length" -> text.length.toString()
            "lines" -> text.lines().size.toString()
            "words" -> text.split(Regex("\\s+")).filter { it.isNotBlank() }.size.toString()
            "chars" -> text.replace(Regex("\\s"), "").length.toString()
            "replace" -> {
                val parts = argument.split("=>", limit = 2)
                if (parts.size != 2) return ToolOutcome.Err("argument must look like 'old=>new'")
                text.replace(parts[0], parts[1])
            }
            "split" -> {
                val sep = argument.ifEmpty { "," }
                text.split(sep).joinToString("\n")
            }
            "join" -> text.lines().filter { it.isNotBlank() }.joinToString(argument.ifEmpty { ", " })
            "strip_html" -> text.replace(Regex("<[^>]*>"), "")
            "escape_json" -> text.replace("\\", "\\\\").replace("\"", "\\\"")
            "collapse_spaces" -> text.replace(Regex("[ \\t]+"), " ").trim()
            "dedent" -> text.lines().joinToString("\n") { it.trimStart() }
            else -> return ToolOutcome.Err(
                "Unknown operation '$operation'. Use upper, lower, capitalize, trim, reverse, length, lines, words, chars, replace, split, join, strip_html, escape_json, collapse_spaces or dedent"
            )
        }
        return ToolOutcome.Ok(result)
    }

    fun regexOp(text: String, pattern: String, operation: String, replacement: String): ToolOutcome {
        if (pattern.isBlank()) return ToolOutcome.Err("pattern parameter is required")
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ToolOutcome.Err("Invalid regex: ${e.message}")
        }
        return when (operation.lowercase()) {
            "match" -> {
                val match = regex.find(text) ?: return ToolOutcome.Ok("No match")
                val sb = StringBuilder()
                sb.append("Match: ").append(match.value).append('\n')
                sb.append("Range: ").append(match.range.first).append("..").append(match.range.last).append('\n')
                for ((index, group) in match.groupValues.withIndex()) {
                    if (index == 0) continue
                    sb.append("Group ").append(index).append(": ").append(group).append('\n')
                }
                ToolOutcome.Ok(sb.toString().trimEnd())
            }
            "find_all" -> {
                val all = regex.findAll(text).toList()
                if (all.isEmpty()) return ToolOutcome.Ok("No match")
                val sb = StringBuilder()
                sb.append("Found ").append(all.size).append(" match(es):\n")
                for ((index, m) in all.withIndex()) {
                    sb.append(index + 1).append(". ").append(m.value)
                    if (m.range.first != m.range.last) sb.append("  @").append(m.range.first)
                    sb.append('\n')
                }
                ToolOutcome.Ok(sb.toString().trimEnd())
            }
            "replace" -> ToolOutcome.Ok(regex.replace(text, replacement))
            "replace_first" -> ToolOutcome.Ok(regex.replaceFirst(text, replacement))
            "split" -> ToolOutcome.Ok(regex.split(text).joinToString("\n"))
            "test" -> ToolOutcome.Ok(if (regex.containsMatchIn(text)) "true" else "false")
            else -> ToolOutcome.Err("Unknown operation '$operation'. Use match, find_all, replace, replace_first, split or test")
        }
    }

    fun sortLines(text: String, order: String, dedupe: Boolean): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        var lines = text.lines()
        if (dedupe) lines = lines.distinct()
        lines = when (order.lowercase()) {
            "desc" -> lines.sortedDescending()
            "numeric" -> lines.sortedBy { it.trim().toDoubleOrNull() ?: Double.MAX_VALUE }
            "length" -> lines.sortedBy { it.length }
            else -> lines.sorted()
        }
        return ToolOutcome.Ok(lines.joinToString("\n"))
    }

    fun diffText(left: String, right: String): ToolOutcome {
        val a = left.lines()
        val b = right.lines()
        val sb = StringBuilder()
        val maxLines = max(a.size, b.size)
        var changes = 0
        for (i in 0 until maxLines) {
            val la = a.getOrNull(i)
            val lb = b.getOrNull(i)
            when {
                la == lb -> sb.append("  ").append(la ?: "").append('\n')
                la == null -> {
                    sb.append("+ ").append(lb ?: "").append('\n')
                    changes++
                }
                lb == null -> {
                    sb.append("- ").append(la).append('\n')
                    changes++
                }
                else -> {
                    sb.append("- ").append(la).append('\n')
                    sb.append("+ ").append(lb).append('\n')
                    changes++
                }
            }
        }
        return ToolOutcome.Ok("$changes differing line(s)\n\n${sb.toString().trimEnd()}")
    }

    fun urlEncode(text: String): ToolOutcome =
        if (text.isEmpty()) ToolOutcome.Err("text parameter is required")
        else ToolOutcome.Ok(URLEncoder.encode(text, "UTF-8"))

    fun urlDecode(text: String): ToolOutcome =
        if (text.isEmpty()) ToolOutcome.Err("text parameter is required")
        else try {
            ToolOutcome.Ok(URLDecoder.decode(text, "UTF-8"))
        } catch (e: Exception) {
            ToolOutcome.Err("Invalid encoded input: ${e.message}")
        }

    fun jsonFormat(text: String, indent: Int): ToolOutcome {
        if (text.isBlank()) return ToolOutcome.Err("text parameter is required")
        val trimmed = text.trim()
        return try {
            when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                    val step = indent.coerceIn(0, 8)
                    if (step == 0) {
                        ToolOutcome.Ok(compactJson(trimmed))
                    } else {
                        ToolOutcome.Ok(prettyJson(trimmed, step))
                    }
                }
                else -> ToolOutcome.Err("Input is not JSON")
            }
        } catch (e: Exception) {
            ToolOutcome.Err("Invalid JSON: ${e.message}")
        }
    }

    private fun compactJson(json: String): String {
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in json) {
            if (inString) {
                sb.append(ch)
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    sb.append(ch)
                }
                ' ', '\n', '\r', '\t' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun prettyJson(json: String, step: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        val pad = " ".repeat(step)
        for (ch in json) {
            if (inString) {
                sb.append(ch)
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    sb.append(ch)
                }
                '{', '[' -> {
                    sb.append(ch).append('\n')
                    depth++
                    sb.append(pad.repeat(depth))
                }
                '}', ']' -> {
                    sb.append('\n')
                    depth--
                    sb.append(pad.repeat(depth.coerceAtLeast(0)))
                    sb.append(ch)
                }
                ',' -> sb.append(ch).append('\n').append(pad.repeat(depth))
                ':' -> sb.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun dateConvert(input: String, fromPattern: String, toPattern: String): ToolOutcome {
        val src = fromPattern.ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val dst = toPattern.ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val text = input.ifBlank { SimpleDateFormat(src, Locale.getDefault()).format(Date()) }
        return try {
            val parsed = SimpleDateFormat(src, Locale.getDefault()).parse(text)
                ?: return ToolOutcome.Err("Cannot parse '$text' with pattern '$src'")
            val formatted = SimpleDateFormat(dst, Locale.getDefault()).format(parsed)
            ToolOutcome.Ok("$text\n-> $formatted\nepoch millis: ${parsed.time}")
        } catch (e: Exception) {
            ToolOutcome.Err("Date conversion failed: ${e.message}")
        }
    }

    fun convertUnit(value: Double, from: String, to: String): ToolOutcome {
        val f = from.lowercase().trim()
        val t = to.lowercase().trim()
        val meters = when (f) {
            "m", "meter", "meters", "米" -> value
            "km", "kilometer", "千米", "公里" -> value * 1000
            "cm", "厘米" -> value / 100
            "mm", "毫米" -> value / 1000
            "mile", "miles", "英里" -> value * 1609.344
            "ft", "foot", "feet", "英尺" -> value * 0.3048
            "inch", "in", "英寸" -> value * 0.0254
            "yard", "yards", "码" -> value * 0.9144
            "kg", "kilogram", "千克", "公斤" -> value * 1000
            "g", "gram", "克" -> value
            "mg", "毫克" -> value / 1000
            "t", "ton", "吨" -> value * 1_000_000
            "lb", "pound", "磅" -> value * 453.59237
            "oz", "ounce", "盎司" -> value * 28.349523125
            "c", "celsius", "摄氏" -> value + 273.15
            "k", "kelvin", "开尔文" -> value
            "f", "fahrenheit", "华氏" -> (value - 32) * 5 / 9 + 273.15
            "l", "liter", "升" -> value
            "ml", "毫升" -> value / 1000
            "m3", "立方米" -> value * 1000
            "gal", "gallon", "加仑" -> value * 3.785411784
            "b", "byte", "字节" -> value
            "kb" -> value * 1024
            "mb" -> value * 1024 * 1024
            "gb" -> value * 1024 * 1024 * 1024
            "tb" -> value * 1024 * 1024 * 1024 * 1024
            else -> return ToolOutcome.Err("Unsupported source unit: $from")
        }
        val result = when (t) {
            "m", "meter", "meters", "米" -> meters
            "km", "kilometer", "千米", "公里" -> meters / 1000
            "cm", "厘米" -> meters * 100
            "mm", "毫米" -> meters * 1000
            "mile", "miles", "英里" -> meters / 1609.344
            "ft", "foot", "feet", "英尺" -> meters / 0.3048
            "inch", "in", "英寸" -> meters / 0.0254
            "yard", "yards", "码" -> meters / 0.9144
            "kg", "kilogram", "千克", "公斤" -> meters / 1000
            "g", "gram", "克" -> meters
            "mg", "毫克" -> meters * 1000
            "t", "ton", "吨" -> meters / 1_000_000
            "lb", "pound", "磅" -> meters / 453.59237
            "oz", "ounce", "盎司" -> meters / 28.349523125
            "c", "celsius", "摄氏" -> meters - 273.15
            "k", "kelvin", "开尔文" -> meters
            "f", "fahrenheit", "华氏" -> (meters - 273.15) * 9 / 5 + 32
            "l", "liter", "升" -> meters
            "ml", "毫升" -> meters * 1000
            "m3", "立方米" -> meters / 1000
            "gal", "gallon", "加仑" -> meters / 3.785411784
            "b", "byte", "字节" -> meters
            "kb" -> meters / 1024
            "mb" -> meters / (1024 * 1024)
            "gb" -> meters / (1024 * 1024 * 1024)
            "tb" -> meters / (1024.0 * 1024 * 1024 * 1024)
            else -> return ToolOutcome.Err("Unsupported target unit: $to")
        }
        val formatted = java.math.BigDecimal(result)
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return ToolOutcome.Ok("$value $from = $formatted $to")
    }
}

private class ExpressionParser(private val source: String) {
    private var pos = 0

    fun parse(): Double {
        val value = parseExpression()
        skipSpaces()
        if (pos < source.length) throw IllegalArgumentException("Unexpected character '${source[pos]}' at $pos")
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipSpaces()
            when {
                consume('+') -> value += parseTerm()
                consume('-') -> value -= parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parsePower()
        while (true) {
            skipSpaces()
            when {
                consume('*') -> value *= parsePower()
                consume('/') -> {
                    val divisor = parsePower()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    value /= divisor
                }
                consume('%') -> {
                    val divisor = parsePower()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    value %= divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        skipSpaces()
        if (consume('^')) return base.pow(parsePower())
        return base
    }

    private fun parseUnary(): Double {
        skipSpaces()
        if (consume('-')) return -parseUnary()
        if (consume('+')) return parseUnary()
        return parseAtom()
    }

    private fun parseAtom(): Double {
        skipSpaces()
        if (consume('(')) {
            val value = parseExpression()
            skipSpaces()
            if (!consume(')')) throw IllegalArgumentException("Missing closing parenthesis")
            return value
        }
        if (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) return parseNumber()
        val name = parseIdentifier()
        if (name.isEmpty()) throw IllegalArgumentException("Unexpected token at $pos")
        return when (name.lowercase()) {
            "pi" -> Math.PI
            "e" -> Math.E
            "sqrt" -> sqrt(parseArgument())
            "abs" -> abs(parseArgument())
            "sin" -> sin(parseArgument())
            "cos" -> cos(parseArgument())
            "tan" -> tan(parseArgument())
            "ln" -> ln(parseArgument())
            "log" -> log10(parseArgument())
            "log10" -> log10(parseArgument())
            "floor" -> floor(parseArgument())
            "ceil" -> ceil(parseArgument())
            "round" -> round(parseArgument())
            "min" -> {
                val a = parseArgument()
                val b = parseArgument()
                min(a, b)
            }
            "max" -> {
                val a = parseArgument()
                val b = parseArgument()
                max(a, b)
            }
            "pow" -> {
                val a = parseArgument()
                val b = parseArgument()
                a.pow(b)
            }
            else -> throw IllegalArgumentException("Unknown identifier '$name'")
        }
    }

    private fun parseArgument(): Double {
        skipSpaces()
        if (consume('(')) {
            val value = parseExpression()
            skipSpaces()
            if (!consume(')')) throw IllegalArgumentException("Missing closing parenthesis")
            return value
        }
        return parseUnary()
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            pos++
            if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        val text = source.substring(start, pos)
        return text.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number '$text'")
    }

    private fun parseIdentifier(): String {
        val start = pos
        while (pos < source.length && (source[pos].isLetter() || source[pos] == '_')) pos++
        return source.substring(start, pos)
    }

    private fun skipSpaces() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    private fun consume(ch: Char): Boolean {
        if (pos < source.length && source[pos] == ch) {
            pos++
            return true
        }
        return false
    }
}