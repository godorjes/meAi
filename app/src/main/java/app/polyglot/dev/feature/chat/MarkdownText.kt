package app.polyglot.dev.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.CodeBg
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.Ink2
import app.polyglot.dev.ui.theme.SoftBg

/**
 * 轻量 Markdown 渲染：覆盖首版需要的
 * 标题、列表、引用、粗体/斜体/行内代码、链接（点击才打开）、围栏代码块（横向滚动+复制）。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = rememberBlocks(markdown)
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            when (block) {
                is MdBlock.Code -> CodeBlockView(block.lang, block.code)
                is MdBlock.Text -> SelectionContainer {
                    Text(
                        buildInline(block.lines),
                        fontSize = 13.5.sp,
                        lineHeight = 23.sp,
                        color = Ink2
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Text(val lines: List<String>) : MdBlock
}

@Composable
private fun rememberBlocks(markdown: String): List<MdBlock> = androidx.compose.runtime.remember(markdown) {
    val result = mutableListOf<MdBlock>()
    val textLines = mutableListOf<String>()
    var inCode = false
    var codeLang = ""
    val codeSb = StringBuilder()

    fun flushText() {
        if (textLines.isNotEmpty()) {
            result.add(MdBlock.Text(textLines.toList()))
            textLines.clear()
        }
    }

    markdown.split("\n").forEach { line ->
        val fence = line.trimStart()
        when {
            fence.startsWith("```") && !inCode -> {
                flushText()
                inCode = true
                codeLang = fence.removePrefix("```").trim()
                codeSb.setLength(0)
            }
            fence.startsWith("```") && inCode -> {
                result.add(MdBlock.Code(codeLang, codeSb.toString().trimEnd('\n')))
                inCode = false
            }
            inCode -> codeSb.append(line).append('\n')
            else -> textLines.add(line)
        }
    }
    if (inCode) result.add(MdBlock.Code(codeLang, codeSb.toString().trimEnd('\n')))
    flushText()
    result
}

@Composable
private fun CodeBlockView(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeBg, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(lang.ifBlank { "code" }, color = Color(0xFF8E99AB), fontSize = 10.5.sp)
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                copied.value = true
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF8E99AB), modifier = Modifier.height(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (copied.value) "已复制 ✓" else "复制", color = Color(0xFF8E99AB), fontSize = 10.5.sp)
            }
        }
        Text(
            code,
            color = Color(0xFFD7DEE9),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
        )
    }
}

/** 段落级排版 + 行内样式（**粗体**、*斜体*、`代码`、[链接](url)） */
private fun buildInline(lines: List<String>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { index, rawLine ->
        if (index > 0) append("\n")
        val line = rawLine.trimEnd()
        when {
            line.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.5.sp)) {
                appendInline(line.removePrefix("### "))
            }
            line.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.5.sp)) {
                appendInline(line.removePrefix("## "))
            }
            line.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                appendInline(line.removePrefix("# "))
            }
            line.startsWith("> ") -> withStyle(SpanStyle(color = Gray, fontStyle = FontStyle.Italic)) {
                append("▍")
                appendInline(line.removePrefix("> "))
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                append("  • ")
                appendInline(line.trimStart().drop(2))
            }
            else -> appendInline(line)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    val linkStyle = SpanStyle(color = BrandBlue, textDecoration = TextDecoration.Underline)
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, background = SoftBg)) {
                        append(" " + text.substring(i + 1, end) + " ")
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("[", i) -> {
                val mid = text.indexOf("](", i + 1)
                val end = if (mid > 0) text.indexOf(")", mid + 2) else -1
                if (mid > i + 1 && end > mid + 2) {
                    val label = text.substring(i + 1, mid)
                    val url = text.substring(mid + 2, end)
                    // 链接只在用户点击后打开（Compose 通过 UriHandler 处理点击）
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) { append(label) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
