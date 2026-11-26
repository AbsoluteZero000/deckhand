package io.github.absolutezero000.deckhand.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** Ties together the database, settings and sync. One instance per app. */
class Repository(
    private val context: Context,
    val dao: DeckDao,
    val settings: Settings,
    private val fetcher: DeckFetcher,
    private val writer: CardWriter,
) {
    private val syncLock = Mutex()

    fun today(): Long = LocalDate.now().toEpochDay()

    /**
     * Syncs from the saved repo. Returns null on success, or the error message (also stored
     * in settings so Home can show it). [force] skips the "synced recently" shortcut.
     */
    suspend fun sync(force: Boolean): String? = syncLock.withLock {
        val s = settings.current()
        if (s.demo) return null
        if (!force && System.currentTimeMillis() - s.lastSyncMillis < 5 * 60_000) return null
        val (repo, token) = settings.credentials() ?: return "Not connected to a repo."
        try {
            when (val r = fetcher.fetch(repo, token, s.contentVersion, settings.etag())) {
                is FetchResult.Unchanged -> settings.recordSync(null, r.etag, System.currentTimeMillis())
                is FetchResult.Fresh -> {
                    apply(r.deck)
                    settings.recordSync(r.deck.version, r.etag, System.currentTimeMillis())
                }
            }
            null
        } catch (e: SyncException) {
            settings.recordError(e.message!!)
            e.message
        }
    }

    /** Verifies a new repo + token with a full download, then saves them. Returns an error message or null. */
    suspend fun connect(repo: String, token: String): String? = syncLock.withLock {
        try {
            val r = fetcher.fetch(repo.trim(), token.trim(), knownVersion = null, etag = null)
            val fresh = r as FetchResult.Fresh
            apply(fresh.deck)
            settings.saveConnection(repo.trim(), token.trim(), fresh.deck.version, fresh.etag, System.currentTimeMillis())
            null
        } catch (e: SyncException) {
            e.message
        }
    }

    /** Loads the bundled example deck so the app can be tried without a repo. */
    suspend fun loadDemo() = syncLock.withLock {
        val text = context.assets.open("demo-deck.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val deck = json.decodeFromString<DeckFile>(text)
        apply(deck)
        settings.setDemo(deck.version)
    }

    /** Removes the repo, token, cards and progress. */
    suspend fun disconnect() = syncLock.withLock {
        dao.clearAll()
        settings.clearConnection()
    }

    // -- editing

    suspend fun newCardId(deckId: String, front: String): String =
        CardRules.newId(deckId, front, dao.cardIds().toSet())

    /**
     * Saves a card: commits it to the cards repo (unless this is the example deck), then updates
     * the phone's copy right away. Returns an error message, or null on success.
     */
    suspend fun saveCard(card: CardEntity, isNew: Boolean): String? {
        val clean = card.copy(front = card.front.trim(), back = card.back.trim(), why = card.why.trim())
        CardRules.problems(clean.front, clean.back, clean.tags).firstOrNull()?.let { return it }
        if (!CardRules.isValidId(clean.id)) return "This card's id isn't valid."
        remote { repo, token -> writer.apply(repo, token, CardChange.Upsert(clean)) }?.let { return it }
        if (isNew) dao.addCardAtEnd(clean) else dao.upsertCard(clean)
        return null
    }

    /** Deletes a card and its progress, in the repo and on the phone. Returns an error message or null. */
    suspend fun deleteCard(card: CardEntity): String? {
        remote { repo, token -> writer.apply(repo, token, CardChange.Delete(card.deckId, card.sectionId, card.id)) }
            ?.let { return it }
        dao.deleteCard(card.id)
        return null
    }

    /** Runs a GitHub write unless we're on the example deck. Returns an error message or null. */
    private suspend fun remote(block: suspend (String, String) -> Unit): String? {
        if (settings.current().demo) return null
        val (repo, token) = settings.credentials() ?: return "Not connected to a repo."
        return try {
            block(repo, token); null
        } catch (e: SyncException) {
            e.message
        }
    }

    suspend fun logReview(delta: Int) = dao.logReview(today(), delta)

    private suspend fun apply(deck: DeckFile) {
        val (d, s, c) = deck.toEntities()
        dao.replaceContent(d, s, c)
    }
}
