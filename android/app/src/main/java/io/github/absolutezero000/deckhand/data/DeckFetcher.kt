package io.github.absolutezero000.deckhand.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest

class SyncException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, NOT_FOUND, RATE_LIMIT, OFFLINE, BROKEN, TOO_NEW, BAD_REPO, OTHER }
}

sealed interface FetchResult {
    /** The manifest is unchanged since [etag] or has the version we already have. */
    data class Unchanged(val etag: String?) : FetchResult
    data class Fresh(val deck: DeckFile, val etag: String?) : FetchResult
}

/**
 * Reads manifest.json and deck.json from the `published` branch of a repo through the
 * GitHub contents API (see "How updates reach the phone" in the README).
 */
class DeckFetcher(
    private val http: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun fetch(repo: String, token: String, knownVersion: String?, etag: String?): FetchResult =
        withContext(Dispatchers.IO) {
            if (!REPO_RE.matches(repo)) throw SyncException(SyncException.Kind.BAD_REPO, "Enter the repo as owner/name.")
            val (mStatus, mBody, mEtag) = get(repo, token, "manifest.json", etag)
            if (mStatus == 304) return@withContext FetchResult.Unchanged(etag)
            val manifest = parse<Manifest>(mBody!!)
            if (manifest.schema > SUPPORTED_SCHEMA) throw SyncException(
                SyncException.Kind.TOO_NEW, "These cards need a newer version of the app."
            )
            if (manifest.version == knownVersion) return@withContext FetchResult.Unchanged(mEtag)

            val (_, dBody, _) = get(repo, token, manifest.deck_url, null)
            if (sha256(dBody!!) != manifest.sha256.lowercase()) throw broken("deck.json doesn't match its manifest")
            val deck = parse<DeckFile>(dBody)
            if (deck.schema != manifest.schema || deck.version != manifest.version) throw broken("deck.json and manifest.json disagree")
            FetchResult.Fresh(deck, mEtag)
        }

    private fun get(repo: String, token: String, path: String, etag: String?): Triple<Int, ByteArray?, String?> {
        val req = Request.Builder()
            .url("$apiBase/repos/$repo/contents/$path?ref=published")
            .header("Accept", "application/vnd.github.raw")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        val resp: Response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.OFFLINE, "Offline. Studying from the last synced cards.")
        }
        resp.use { r ->
            when {
                r.code == 304 -> return Triple(304, null, etag)
                r.isSuccessful -> return Triple(r.code, r.body!!.bytes(), r.header("ETag"))
                r.code == 401 -> throw SyncException(
                    SyncException.Kind.AUTH, "Token expired or revoked. Create a new one and update it in Settings."
                )
                r.code == 429 || (r.code == 403 && r.header("x-ratelimit-remaining") == "0") -> throw SyncException(
                    SyncException.Kind.RATE_LIMIT, "GitHub rate limit hit; try again in a few minutes."
                )
                r.code == 403 || r.code == 404 -> throw SyncException(
                    SyncException.Kind.NOT_FOUND,
                    "Repo or its published branch not found, or the token can't read this repo.",
                )
                else -> throw SyncException(SyncException.Kind.OTHER, "GitHub answered ${r.code}. Try again later.")
            }
        }
    }

    private inline fun <reified T> parse(body: ByteArray): T = try {
        json.decodeFromString<T>(body.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw broken("couldn't read the published files")
    } catch (e: IllegalArgumentException) {
        throw broken("couldn't read the published files")
    }

    private fun broken(why: String) =
        SyncException(SyncException.Kind.BROKEN, "The published deck looks broken ($why). Check the cards repo's CI.")

    companion object {
        val REPO_RE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")

        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
