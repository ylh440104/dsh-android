package com.deepseek.harness.tools

sealed class ToolOutcome {
    data class Ok(val text: String) : ToolOutcome()
    data class Err(val message: String) : ToolOutcome()

    val isError: Boolean get() = this is Err

    fun render(): String = when (this) {
        is Ok -> text
        is Err -> "Error: $message"
    }
}