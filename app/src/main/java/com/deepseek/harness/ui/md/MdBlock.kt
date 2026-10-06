package com.deepseek.harness.ui.md

sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Bullet(val items: List<MdListItem>) : MdBlock()
    data class Ordered(val items: List<MdListItem>) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

data class MdListItem(val text: String, val depth: Int)

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false
)