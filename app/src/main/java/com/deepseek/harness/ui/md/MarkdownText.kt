package com.deepseek.harness.ui.md

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    onCopy: (String) -> Unit = {}
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, baseColor, 15.sp)
                is MdBlock.Heading -> HeadingText(block.level, block.text, baseColor)
                is MdBlock.Code -> CodeBlock(block, onCopy)
                is MdBlock.Quote -> QuoteBlock(block.text, baseColor)
                is MdBlock.Bullet -> BulletList(block.items, baseColor)
                is MdBlock.Ordered -> OrderedList(block.items, baseColor)
                is MdBlock.Table -> TableBlock(block, baseColor)
            }
        }
    }
}

@Composable
private fun InlineText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    val spans = remember(text) { MarkdownParser.parseInline(text) }
    val annotated = buildAnnotatedString {
        for (span in spans) {
            val start = length
            append(span.text)
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) MaterialTheme.colorScheme.surfaceVariant else Color.Unspecified,
                color = if (span.code) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
            addStyle(style, start, length)
        }
    }
    Text(annotated, color = color, fontSize = size, lineHeight = size * 1.5f)
}

@Composable
private fun HeadingText(level: Int, text: String, color: Color) {
    val size = when (level) {
        1 -> 22.sp
        2 -> 19.sp
        3 -> 17.sp
        else -> 16.sp
    }
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = if (level <= 2) 4.dp else 2.dp)
    )
}

@Composable
private fun CodeBlock(block: MdBlock.Code, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (block.language.isBlank()) "code" else block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                if (!block.closed) {
                    Text(
                        "生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        onCopy(block.code)
                        copied = true
                    }
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "复制代码",
                        tint = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuoteBlock(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            InlineText(text, MaterialTheme.colorScheme.onSurfaceVariant, 14.sp)
        }
    }
}

@Composable
private fun BulletList(items: List<MdListItem>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (item in items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width((item.depth * 14).dp))
                Text("•", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    InlineText(item.text, color, 15.sp)
                }
            }
        }
    }
}

@Composable
private fun OrderedList(items: List<MdListItem>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((index, item) in items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1}.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 15.sp,
                    modifier = Modifier.width(22.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    InlineText(item.text, color, 15.sp)
                }
            }
        }
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, color: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (cell in table.header) {
                    Text(
                        cell,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            for (row in table.rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (cell in row) {
                        Text(
                            cell,
                            color = color,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
fun CopyablePlainText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val clipboard = LocalClipboardManager.current
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = modifier.clickable {
            clipboard.setText(AnnotatedString(text))
        }
    )
}