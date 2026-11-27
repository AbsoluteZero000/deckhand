package io.github.absolutezero000.deckhand

import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.domain.AskAi
import io.github.absolutezero000.deckhand.domain.CardContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAiTest {
    private val card = CardEntity(
        "example-recall", "example", "basics", "What is active recall?", "Testing yourself instead of rereading.",
        "Retrieving builds memory.", listOf("learning"), 0,
    )

    @Test fun deckThenLessonThenCard() {
        val p = AskAi.prompt(
            card,
            CardContext("Example", "Starter deck", "General study skills.", "Basics", "How memory works."),
        )
        val order = listOf("Deck: Example", "About this deck: General study skills.", "Lesson: Basics",
            "About this lesson: How memory works.", "Q: What is active recall?", "A: Testing yourself",
            "Why: Retrieving builds memory.", "Tags: learning", "follow-up questions")
        val at = order.map { p.indexOf(it) }
        assertTrue(p, at.all { it >= 0 } && at == at.sorted())
    }

    @Test fun leavesOutEmptyFields() {
        val p = AskAi.prompt(card.copy(why = "", tags = emptyList()), CardContext("Example", sectionName = "Basics"))
        listOf("About this deck", "About this lesson", "Why:", "Tags:").forEach { assertFalse(it, it in p) }
    }
}
