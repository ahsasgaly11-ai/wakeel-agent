package com.wakeel.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders the light markdown the agent writes: ## headings, - bullets, 1. lists, | tables, **bold**, coloured ±%. */
@Composable
fun RichText(text: String, modifier: Modifier = Modifier) {
    val lines = text.replace("\r", "").split('\n')
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.width(0.dp))
                line.startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                        if (!cells.all { c -> c.isEmpty() || c.all { it == '-' || it == ':' || it == ' ' } }) rows += cells
                        i++
                    }
                    Table(rows)
                    continue
                }
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#').trim(), MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
                )
                line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ") -> Bullet("•", line.drop(2))
                Regex("^\\d{1,2}[.)]\\s").containsMatchIn(line) -> {
                    val n = line.substringBefore(' ')
                    Bullet(n, line.substringAfter(' '))
                }
                line == "---" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                else -> Text(inline(line), style = MaterialTheme.typography.bodyLarge)
            }
            i++
        }
    }
}

@Composable
private fun Bullet(mark: String, body: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(mark, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(inline(body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Table(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { r, cells ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    for (c in 0 until cols) {
                        Text(
                            inline(cells.getOrElse(c) { "" }),
                            modifier = Modifier.weight(1f),
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                            textAlign = if (c == 0) TextAlign.Start else TextAlign.Center,
                        )
                    }
                }
                if (r < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private val pct = Regex("[+\\-−]?\\d+(?:[.,]\\d+)?\\s?%")

@Composable
private fun inline(s: String, base: Color = Color.Unspecified): AnnotatedString {
    val up = Brand.Green
    val down = MaterialTheme.colorScheme.error
    return buildAnnotatedString {
        val parts = s.split("**")
        parts.forEachIndexed { idx, part ->
            val bold = idx % 2 == 1
            var last = 0
            for (m in pct.findAll(part)) {
                appendStyled(part.substring(last, m.range.first), bold, base)
                val v = m.value
                val color = when {
                    v.startsWith("+") -> up
                    v.startsWith("-") || v.startsWith("−") -> down
                    else -> base
                }
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(v) }
                last = m.range.last + 1
            }
            appendStyled(part.substring(last), bold, base)
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(t: String, bold: Boolean, color: Color) {
    if (t.isEmpty()) return
    if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(t) }
    else if (color != Color.Unspecified) withStyle(SpanStyle(color = color)) { append(t) }
    else append(t)
}
