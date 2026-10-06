package com.deepseek.harness.tools

import java.io.File

object PathGuard {

    const val SANDBOX_ROOT = "/workspace"

    fun normalize(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return SANDBOX_ROOT
        val absolute = trimmed.startsWith("/")
        val stack = ArrayDeque<String>()
        for (segment in trimmed.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> {}
                segment == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        val joined = stack.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> SANDBOX_ROOT
            else -> "$SANDBOX_ROOT/$joined"
        }
    }

    fun inSandbox(path: String): Boolean =
        path == SANDBOX_ROOT || path.startsWith("$SANDBOX_ROOT/")

    fun isProtected(path: String): Boolean = PROTECTED.any { path == it || path.startsWith("$it/") }

    fun toFile(workspace: File, path: String): File =
        if (inSandbox(path)) {
            val relative = path.removePrefix(SANDBOX_ROOT).trimStart('/')
            if (relative.isEmpty()) workspace else File(workspace, relative)
        } else {
            File(path)
        }

    fun requirePath(raw: String): ToolOutcome.Err? =
        if (raw.isBlank()) ToolOutcome.Err("path parameter is required") else null

    private val PROTECTED = listOf(
        "/system",
        "/vendor",
        "/boot",
        "/dev",
        "/proc",
        "/sys",
        "/init",
        "/data/misc",
        "/data/system",
        "/data/adb"
    )
}