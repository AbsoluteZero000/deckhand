package io.github.absolutezero000.deckhand.data

/** The same card rules the deckhand CLI enforces (deckhand/cli.py), so edits made on the phone pass CI. */
object CardRules {
    private val ID_RE = Regex("^[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?$")
    private val TAG_RE = Regex("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")

    fun isValidId(id: String) = ID_RE.matches(id)
    fun isValidTag(tag: String) = TAG_RE.matches(tag)

    /** Splits "a, B ,c" into ["a", "b", "c"] like the CLI's --tags. */
    fun splitTags(text: String): List<String> =
        text.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** Problems with a card's fields, as messages for the edit form. Empty when valid. */
    fun problems(front: String, back: String, tags: List<String>): List<String> = buildList {
        if (front.isBlank()) add("Front can't be empty.")
        if (back.isBlank()) add("Back can't be empty.")
        tags.filterNot(::isValidTag).forEach { add("Tag \"$it\": use lowercase letters, digits and dashes.") }
    }

    /** Same as the CLI's slugify(). */
    fun slugify(text: String, limit: Int = 60): String {
        var s = text.replace("`", "").lowercase()
        s = s.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        s = s.take(limit).trimEnd('-')
        return s.ifEmpty { "card" }
    }

    /** Same as the CLI's unique_id(): "<deck>-<slug>", then "-2", "-3", … if taken. */
    fun newId(deckId: String, front: String, taken: Set<String>): String {
        val base = "$deckId-${slugify(front)}"
        var candidate = base
        var n = 2
        while (candidate in taken) {
            val suffix = "-$n"
            candidate = base.take(80 - suffix.length).trimEnd('-') + suffix
            n++
        }
        return candidate
    }
}
