package io.github.absolutezero000.deckhand.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The published contract; see "Data contract" in the deckhand README. */
const val SUPPORTED_SCHEMA = 1

val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
data class Manifest(
    val schema: Int,
    val version: String,
    val sha256: String,
    val deck_url: String = "deck.json",
    val decks: Int = 0,
    val cards: Int = 0,
)

@Serializable
data class DeckFile(
    val schema: Int,
    val version: String,
    val decks: List<DeckJson>,
    val cards: List<CardJson>,
)

@Serializable
data class DeckJson(
    val id: String,
    val name: String,
    val description: String = "",
    val order: Int = 0,
    val sections: List<SectionJson> = emptyList(),
)

@Serializable
data class SectionJson(val id: String, val name: String, val order: Int = 0)

@Serializable
data class CardJson(
    val id: String,
    val deck: String,
    val section: String,
    val front: String,
    val back: String,
    val why: String = "",
    val tags: List<String> = emptyList(),
    val order: Int = 0,
)

fun DeckFile.toEntities(): Triple<List<DeckEntity>, List<SectionEntity>, List<CardEntity>> = Triple(
    decks.map { DeckEntity(it.id, it.name, it.description, it.order) },
    decks.flatMap { d -> d.sections.map { SectionEntity(d.id, it.id, it.name, it.order) } },
    cards.map { CardEntity(it.id, it.deck, it.section, it.front, it.back, it.why, it.tags, it.order) },
)
