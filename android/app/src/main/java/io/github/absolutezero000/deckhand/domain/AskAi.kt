package io.github.absolutezero000.deckhand.domain

import io.github.absolutezero000.deckhand.data.CardEntity

/** Where a card sits, with the background its deck and section give for Ask AI. */
data class CardContext(
    val deckName: String,
    val deckDescription: String = "",
    val deckContext: String = "",
    val sectionName: String,
    val sectionContext: String = "",
)

object AskAi {
    /**
     * The text Ask AI hands to an AI app: deck context, then section context, then the card,
     * then what to do with it. Empty fields are left out.
     */
    fun prompt(card: CardEntity, ctx: CardContext): String = buildString {
        appendLine("I'm studying with flashcards and want help understanding one card.")
        appendLine()
        appendLine("Deck: ${ctx.deckName}")
        ctx.deckDescription.ifNotBlank { appendLine(it) }
        ctx.deckContext.ifNotBlank { appendLine("About this deck: $it") }
        appendLine()
        appendLine("Lesson: ${ctx.sectionName}")
        ctx.sectionContext.ifNotBlank { appendLine("About this lesson: $it") }
        appendLine()
        appendLine("Card")
        appendLine("Q: ${card.front.trim()}")
        appendLine("A: ${card.back.trim()}")
        card.why.ifNotBlank { appendLine("Why: $it") }
        if (card.tags.isNotEmpty()) appendLine("Tags: ${card.tags.joinToString(", ")}")
        appendLine()
        append(
            "Using the deck and lesson above as background, explain this card more deeply: why the answer is right, " +
                "a short concrete example, and the mistakes people usually make. Keep it brief. " +
                "Then answer my follow-up questions about it."
        )
    }

    private inline fun String.ifNotBlank(block: (String) -> Unit) {
        if (isNotBlank()) block(trim())
    }
}
