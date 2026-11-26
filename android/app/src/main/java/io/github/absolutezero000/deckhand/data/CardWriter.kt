package io.github.absolutezero000.deckhand.data

import android.util.Base64 as AndroidBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.IOException

/** A change to one card, applied to its section file in the cards repo. */
sealed interface CardChange {
    val deckId: String
    val sectionId: String
    val cardId: String

    data class Upsert(val card: CardEntity) : CardChange {
        override val deckId get() = card.deckId
        override val sectionId get() = card.sectionId
        override val cardId get() = card.id
    }

    data class Delete(override val deckId: String, override val sectionId: String, override val cardId: String) : CardChange
}

/**
 * Commits card edits to cards/<deck>/<section>.yaml on the repo's default branch through the
 * GitHub contents API. The file is re-read right before writing, so edits made elsewhere are kept,
 * and the blob sha guards against overwriting a change that lands in between.
 *
 * The file is written as YAML whose strings are JSON-quoted. That is valid YAML the CLI reads;
 * the cards repo's CI then rewrites it in the canonical layout.
 */
class CardWriter(
    private val http: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
    private val b64: Codec = AndroidCodec,
) {
    interface Codec {
        fun encode(bytes: ByteArray): String
        fun decode(text: String): ByteArray
    }

    private object AndroidCodec : Codec {
        override fun encode(bytes: ByteArray): String = AndroidBase64.encodeToString(bytes, AndroidBase64.NO_WRAP)
        override fun decode(text: String): ByteArray = AndroidBase64.decode(text, AndroidBase64.DEFAULT)
    }

    suspend fun apply(repo: String, token: String, change: CardChange) = withContext(Dispatchers.IO) {
        val path = "cards/${change.deckId}/${change.sectionId}.yaml"
        // One retry covers the common race: the file changed between our read and write.
        repeat(2) { attempt ->
            val (sha, text) = read(repo, token, path)
            val updated = render(applyTo(parse(text, path), change))
            val message = when (change) {
                is CardChange.Upsert -> "Edit card ${change.cardId} from the deckhand app"
                is CardChange.Delete -> "Remove card ${change.cardId} from the deckhand app"
            }
            if (write(repo, token, path, sha, updated, message)) return@withContext
            if (attempt == 1) throw SyncException(
                SyncException.Kind.OTHER, "The card file kept changing on GitHub. Sync and try again.",
            )
        }
    }

    // -- GitHub

    private fun read(repo: String, token: String, path: String): Pair<String, String> {
        val req = request(repo, token, path).get().build()
        call(req).use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.header("x-ratelimit-remaining"), writing = false)
            val obj = json.parseToJsonElement(r.body!!.string()).jsonObject
            val sha = obj["sha"]!!.jsonPrimitive.content
            val content = obj["content"]!!.jsonPrimitive.content.replace("\n", "")
            return sha to b64.decode(content).toString(Charsets.UTF_8)
        }
    }

    /** Returns false on a sha conflict (409/422) so the caller can re-read and retry. */
    private fun write(repo: String, token: String, path: String, sha: String, text: String, message: String): Boolean {
        val body = buildJsonObject {
            put("message", message)
            put("content", b64.encode(text.toByteArray(Charsets.UTF_8)))
            put("sha", sha)
        }.toString().toRequestBody("application/json".toMediaType())
        call(request(repo, token, path).put(body).build()).use { r ->
            return when {
                r.isSuccessful -> true
                r.code == 409 || r.code == 422 -> false
                else -> throw httpError(r.code, r.header("x-ratelimit-remaining"), writing = true)
            }
        }
    }

    private fun request(repo: String, token: String, path: String) = Request.Builder()
        .url("$apiBase/repos/$repo/contents/$path")
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(req: Request) = try {
        http.newCall(req).execute()
    } catch (e: IOException) {
        throw SyncException(SyncException.Kind.OFFLINE, "You're offline. Editing needs a connection.")
    }

    private fun httpError(code: Int, rateRemaining: String?, writing: Boolean) = when {
        code == 401 -> SyncException(SyncException.Kind.AUTH, "Token expired or revoked. Create a new one and update it in Settings.")
        code == 429 || (code == 403 && rateRemaining == "0") ->
            SyncException(SyncException.Kind.RATE_LIMIT, "GitHub rate limit hit; try again in a few minutes.")
        code == 403 || (writing && code == 404) -> SyncException(
            SyncException.Kind.NOT_FOUND,
            "This token can't edit the repo. Give it Contents: Read and write, then update it in Settings.",
        )
        code == 404 -> SyncException(SyncException.Kind.NOT_FOUND, "That card's file isn't in the repo any more. Sync and try again.")
        else -> SyncException(SyncException.Kind.OTHER, "GitHub answered $code. Try again later.")
    }

    companion object {
        /** Parses a section file into (section fields, cards). */
        fun parse(text: String, path: String = "section"): Pair<Map<String, Any?>, MutableList<Map<String, Any?>>> {
            val root = Load(LoadSettings.builder().build()).loadFromString(text) as? Map<*, *>
                ?: throw SyncException(SyncException.Kind.BROKEN, "$path isn't a section file.")
            @Suppress("UNCHECKED_CAST")
            val section = (root["section"] as? Map<String, Any?>) ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val cards = ((root["cards"] as? List<*>) ?: emptyList<Any?>())
                .map { (it as? Map<String, Any?>) ?: emptyMap() }.toMutableList()
            return section to cards
        }

        fun applyTo(
            parsed: Pair<Map<String, Any?>, MutableList<Map<String, Any?>>>,
            change: CardChange,
        ): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
            val (section, cards) = parsed
            val i = cards.indexOfFirst { it["id"] == change.cardId }
            when (change) {
                is CardChange.Delete -> if (i >= 0) cards.removeAt(i)
                is CardChange.Upsert -> {
                    val c = change.card
                    val m = linkedMapOf<String, Any?>("id" to c.id, "front" to c.front.trim(), "back" to c.back.trim())
                    if (c.why.isNotBlank()) m["why"] = c.why.trim()
                    if (c.tags.isNotEmpty()) m["tags"] = c.tags
                    if (i >= 0) cards[i] = m else cards.add(m)
                }
            }
            return section to cards
        }

        /** Writes a section file with JSON-quoted strings (valid YAML; CI re-formats it). */
        fun render(parsed: Pair<Map<String, Any?>, List<Map<String, Any?>>>): String = buildString {
            val (section, cards) = parsed
            append("section:\n")
            for (k in listOf("id", "name", "order")) section[k]?.let { append("  $k: ${scalar(it)}\n") }
            if (cards.isEmpty()) {
                append("cards: []\n"); return@buildString
            }
            append("cards:\n")
            for (c in cards) {
                var first = true
                for (k in listOf("id", "front", "back", "why", "tags")) {
                    val v = c[k] ?: continue
                    append(if (first) "- " else "  ")
                    first = false
                    append("$k: ${scalar(v)}\n")
                }
            }
        }

        private fun scalar(v: Any?): String = when (v) {
            is Int, is Long -> v.toString()
            is List<*> -> v.joinToString(", ", "[", "]") { JsonPrimitive(it.toString()).toString() }
            else -> JsonPrimitive(v.toString()).toString()
        }
    }
}
