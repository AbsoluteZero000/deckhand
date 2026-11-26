package io.github.absolutezero000.deckhand.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle

/** Renders card text, showing `backtick` spans in monospace on a tinted background. */
fun codeSpans(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val parts = text.split('`')
    // An odd number of parts means the backticks are balanced.
    val balanced = parts.size % 2 == 1
    parts.forEachIndexed { i, part ->
        val isCode = balanced && i % 2 == 1
        if (isCode) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(" $part ")
            }
        } else {
            append(if (balanced) part else if (i == 0) part else "`$part")
        }
    }
}

@Composable
fun CardText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Center,
) {
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Text(codeSpans(text, bg), modifier = modifier, style = style, color = color, textAlign = align)
}
