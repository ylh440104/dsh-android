package com.deepseek.harness.tools

import java.io.File

object PathGuard {

    fun resolve(workspace: File, raw: String): File? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
        if (cleaned.contains('\u0000')) return null
        val candidate = File(workspace, cleaned)
        val root = workspace.canonicalFile
        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            return null
        }
        if (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator)) {
            return null
        }
        return canonical
    }

    fun requirePath(raw: String): ToolOutcome.Err? =
        if (raw.isBlank()) ToolOutcome.Err("path parameter is required") else null

    fun outside(raw: String): ToolOutcome.Err =
        ToolOutcome.Err("Invalid path: '$raw'. Path must stay inside the workspace.")
}