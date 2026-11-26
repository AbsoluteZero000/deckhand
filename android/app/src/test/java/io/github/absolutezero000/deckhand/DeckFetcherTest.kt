package io.github.absolutezero000.deckhand

import io.github.absolutezero000.deckhand.data.DeckFetcher
import io.github.absolutezero000.deckhand.data.FetchResult
import io.github.absolutezero000.deckhand.data.SyncException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeckFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: DeckFetcher

    private val deck = """{"schema":1,"version":"v2","generated_at":"x","decks":[{"id":"d","name":"D","description":"",""" +
        """"order":1,"sections":[{"id":"s","name":"S","order":1}]}],"cards":[{"id":"c1","deck":"d","section":"s",""" +
        """"front":"F","back":"B","why":"","tags":["t"],"order":0}]}"""

    private fun manifest(version: String = "v2", sha: String = DeckFetcher.sha256(deck.toByteArray()), schema: Int = 1) =
        """{"schema":$schema,"version":"$version","generated_at":"x","deck_url":"deck.json","sha256":"$sha","decks":1,"cards":1}"""

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        fetcher = DeckFetcher(OkHttpClient(), server.url("").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun downloadsAndVerifiesNewVersion() = runTest {
        server.enqueue(MockResponse().setBody(manifest()).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setBody(deck))
        val r = fetcher.fetch("me/cards", "tok", knownVersion = "v1", etag = null) as FetchResult.Fresh
        assertEquals("v2", r.deck.version)
        assertEquals("\"e1\"", r.etag)
        val req = server.takeRequest()
        assertEquals("/repos/me/cards/contents/manifest.json?ref=published", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("application/vnd.github.raw", req.getHeader("Accept"))
        assertEquals("/repos/me/cards/contents/deck.json?ref=published", server.takeRequest().path)
    }

    @Test fun sendsEtagAndHandles304() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))
        val r = fetcher.fetch("me/cards", "tok", "v2", "\"e1\"")
        assertTrue(r is FetchResult.Unchanged)
        assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, server.requestCount)
    }

    @Test fun sameVersionSkipsDeckDownload() = runTest {
        server.enqueue(MockResponse().setBody(manifest()))
        assertTrue(fetcher.fetch("me/cards", "tok", "v2", null) is FetchResult.Unchanged)
        assertEquals(1, server.requestCount)
    }

    @Test fun badHashIsBroken() = runTest {
        server.enqueue(MockResponse().setBody(manifest(sha = "00")))
        server.enqueue(MockResponse().setBody(deck))
        expect(SyncException.Kind.BROKEN) { fetcher.fetch("me/cards", "tok", null, null) }
    }

    @Test fun newerSchemaAsksForUpdate() = runTest {
        server.enqueue(MockResponse().setBody(manifest(schema = 2)))
        expect(SyncException.Kind.TOO_NEW) { fetcher.fetch("me/cards", "tok", null, null) }
    }

    @Test fun mapsHttpErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        expect(SyncException.Kind.AUTH) { fetcher.fetch("me/cards", "tok", null, null) }
        server.enqueue(MockResponse().setResponseCode(404))
        expect(SyncException.Kind.NOT_FOUND) { fetcher.fetch("me/cards", "tok", null, null) }
        server.enqueue(MockResponse().setResponseCode(403).setHeader("x-ratelimit-remaining", "0"))
        expect(SyncException.Kind.RATE_LIMIT) { fetcher.fetch("me/cards", "tok", null, null) }
    }

    @Test fun rejectsBadRepoNamesWithoutCalling() = runTest {
        expect(SyncException.Kind.BAD_REPO) { fetcher.fetch("not a repo", "tok", null, null) }
        expect(SyncException.Kind.BAD_REPO) { fetcher.fetch("../../x", "tok", null, null) }
        assertEquals(0, server.requestCount)
    }

    @Test fun offlineWhenServerIsGone() = runTest {
        server.shutdown()
        expect(SyncException.Kind.OFFLINE) { fetcher.fetch("me/cards", "tok", null, null) }
    }

    private suspend fun expect(kind: SyncException.Kind, block: suspend () -> Unit) {
        try {
            block(); fail("expected $kind")
        } catch (e: SyncException) {
            assertEquals(kind, e.kind)
        }
    }
}
