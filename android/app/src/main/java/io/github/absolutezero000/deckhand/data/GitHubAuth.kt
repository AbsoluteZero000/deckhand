package io.github.absolutezero000.deckhand.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * "Connect with GitHub" through the deckhand GitHub App and the OAuth device flow
 * (https://docs.github.com/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app#using-the-device-flow-to-generate-a-user-access-token).
 *
 * The resulting token can only reach repositories the person installed the app on, with the app's
 * permissions (Contents: read and write). No client secret is involved, so the public client id can
 * ship inside the app.
 */
class GitHubAuth(
    private val http: OkHttpClient,
    private val webBase: String = "https://github.com",
    private val apiBase: String = "https://api.github.com",
) {
    @Serializable
    data class DeviceCode(
        val device_code: String,
        val user_code: String,
        val verification_uri: String,
        val expires_in: Int,
        val interval: Int = 5,
    )

    sealed interface Poll {
        data object Pending : Poll
        /** GitHub asked us to poll less often; wait this many seconds from now on. */
        data class SlowDown(val interval: Int) : Poll
        data class Token(val token: String) : Poll
        data object Expired : Poll
        data object Denied : Poll
        /** GitHub couldn't be reached or answered with a server error; worth asking again. */
        data object Unreachable : Poll
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val error: String? = null,
        val interval: Int? = null,
    )

    @Serializable private data class User(val login: String)
    @Serializable private data class Installation(val id: Long)
    @Serializable private data class Installations(val installations: List<Installation> = emptyList())
    @Serializable private data class Repo(val full_name: String)
    @Serializable private data class Repos(val total_count: Int = 0, val repositories: List<Repo> = emptyList())

    suspend fun start(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("client_id", clientId).build()
        val req = Request.Builder().url("$webBase/login/device/code").post(body)
            .header("Accept", "application/json").build()
        call(req) { code, text ->
            if (code != 200) throw SyncException(SyncException.Kind.OTHER, "GitHub couldn't start sign-in ($code).")
            json.decodeFromString<DeviceCode>(text)
        }
    }

    suspend fun poll(clientId: String, deviceCode: String): Poll = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder().url("$webBase/login/oauth/access_token").post(body)
            .header("Accept", "application/json").build()
        val r = try {
            call(req) { code, text ->
                when {
                    code == 429 || code >= 500 -> null
                    code != 200 -> throw SyncException(SyncException.Kind.OTHER, "GitHub sign-in failed ($code).")
                    else -> runCatching { json.decodeFromString<TokenResponse>(text) }.getOrNull()
                }
            }
        } catch (e: SyncException) {
            if (e.kind == SyncException.Kind.OFFLINE) null else throw e
        } ?: return@withContext Poll.Unreachable
        when {
            r.access_token != null -> Poll.Token(r.access_token)
            r.error == "authorization_pending" -> Poll.Pending
            r.error == "slow_down" -> Poll.SlowDown(r.interval ?: 10)
            r.error == "expired_token" -> Poll.Expired
            r.error == "access_denied" -> Poll.Denied
            else -> throw SyncException(SyncException.Kind.OTHER, "GitHub sign-in failed (${r.error}).")
        }
    }

    /**
     * Polls until the person approves, denies, or the code expires at [expiresAtMillis].
     * Network drops and GitHub server errors don't end it (the phone often cuts a background
     * app's network while the person is in the browser); they're retried, and [onReachable]
     * reports whether the last attempt got through. [sleep] waits between polls and may return
     * early (e.g. when the app comes back to the foreground).
     */
    suspend fun waitForToken(
        clientId: String,
        deviceCode: String,
        intervalSeconds: Int,
        expiresAtMillis: Long,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit,
        onReachable: (Boolean) -> Unit = {},
    ): Poll {
        var interval = intervalSeconds.coerceAtLeast(5)
        var failures = 0
        while (true) {
            // Back off a little after repeated failures, but never past 30 s.
            sleep(minOf(interval * 1000L * (1 + failures), 30_000L))
            if (now() > expiresAtMillis) return Poll.Expired
            when (val p = poll(clientId, deviceCode)) {
                Poll.Pending -> { failures = 0; onReachable(true) }
                is Poll.SlowDown -> { interval = p.interval; failures = 0; onReachable(true) }
                Poll.Unreachable -> { failures = minOf(failures + 1, 5); onReachable(false) }
                else -> return p
            }
        }
    }

    suspend fun login(token: String): String = withContext(Dispatchers.IO) {
        call(api("/user", token)) { code, text ->
            if (code != 200) throw apiError(code)
            json.decodeFromString<User>(text).login
        }
    }

    /** Every repository the person installed the app on, as owner/name, sorted. */
    suspend fun repos(token: String): List<String> = withContext(Dispatchers.IO) {
        val installs = call(api("/user/installations?per_page=100", token)) { code, text ->
            if (code != 200) throw apiError(code)
            json.decodeFromString<Installations>(text).installations
        }
        installs.flatMap { inst ->
            var page = 1
            val names = ArrayList<String>()
            while (true) {
                val r = call(api("/user/installations/${inst.id}/repositories?per_page=100&page=$page", token)) { code, text ->
                    if (code != 200) throw apiError(code)
                    json.decodeFromString<Repos>(text)
                }
                names += r.repositories.map { it.full_name }
                if (r.repositories.size < 100 || names.size >= r.total_count) break
                page++
            }
            names
        }.distinct().sortedBy { it.lowercase() }
    }

    private fun api(path: String, token: String) = Request.Builder().url(apiBase + path)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()

    private fun apiError(code: Int) = when (code) {
        401 -> SyncException(SyncException.Kind.AUTH, "GitHub sign-in expired. Connect again.")
        else -> SyncException(SyncException.Kind.OTHER, "GitHub answered $code. Try again later.")
    }

    private inline fun <T> call(req: Request, read: (Int, String) -> T): T {
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.OFFLINE, "You're offline. Connecting needs a connection.")
        }
        return resp.use { read(it.code, it.body?.string().orEmpty()) }
    }
}
