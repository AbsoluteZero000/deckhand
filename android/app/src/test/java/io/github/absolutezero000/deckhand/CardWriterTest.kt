package io.github.absolutezero000.deckhand

import io.github.absolutezero000.deckhand.data.CardChange
import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.CardRules
import io.github.absolutezero000.deckhand.data.CardWriter
import io.github.absolutezero000.deckhand.data.SyncException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** A section file in the CLI's canonical layout, as it sits in a cards repo. */
val SECTION_YAML = """
section:
  id: everyday
  name: Everyday
  order: 1
cards:
- id: git-what-does-git-status-show
  front: What does `git status` show?
  back: Staged, unstaged and untracked changes, plus the current branch.
  tags: [basics]
- id: git-yes-or-no
  front: 'Quoted: yes # not a comment'
  back: |-
    Two
    lines
""".trimStart()

/** What the app writes after editing the first card. Checked against the deckhand CLI too. */
val EDITED_YAML = """
section:
  id: "everyday"
  name: "Everyday"
  order: 1
cards:
- id: "git-what-does-git-status-show"
  front: "What does `git status` show?"
  back: "Changes \"staged\", unstaged, untracked."
  why: "Run it before every commit."
  tags: ["basics", "exam-trap"]
- id: "git-yes-or-no"
  front: "Quoted: yes # not a comment"
  back: "Two\nlines"
""".trimStart()

class CardRulesTest {
    @Test fun slugMatchesCli() {
        assertEquals("what-does-git-status-show", CardRules.slugify("What does `git status` show?"))
        assertEquals("card", CardRules.slugify("???"))
        assertEquals(60, CardRules.slugify("a".repeat(100)).length)
    }

    @Test fun newIdsAreUnique() {
        assertEquals("git-what-is-head", CardRules.newId("git", "What is HEAD?", emptySet()))
        assertEquals("git-what-is-head-2", CardRules.newId("git", "What is HEAD?", setOf("git-what-is-head")))
    }

    @Test fun tagsAreNormalisedAndChecked() {
        assertEquals(listOf("exam-trap", "nat"), CardRules.splitTags(" Exam-Trap, nat nat"))
        assertEquals(1, CardRules.problems("f", "b", listOf("ok", "Bad_Tag")).size)
        assertEquals(2, CardRules.problems(" ", "", emptyList()).size)
    }
}

class CardWriterTest {
    private lateinit var server: MockWebServer
    private lateinit var writer: CardWriter
    private val codec = object : CardWriter.Codec {
        override fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        override fun decode(text: String): ByteArray = Base64.getMimeDecoder().decode(text)
    }

    private val edited = CardEntity(
        id = "git-what-does-git-status-show", deckId = "git", sectionId = "everyday",
        front = "What does `git status` show?", back = "Changes \"staged\", unstaged, untracked.",
        why = "Run it before every commit.", tags = listOf("basics", "exam-trap"), ord = 0,
    )

    private fun fileResponse(text: String, sha: String = "abc") = MockResponse().setBody(
        """{"sha":"$sha","content":"${codec.encode(text.toByteArray()).chunked(60).joinToString("\\n")}"}"""
    )

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        writer = CardWriter(OkHttpClient(), server.url("").toString().trimEnd('/'), codec)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun rendersEditsAsJsonQuotedYaml() {
        val out = CardWriter.render(CardWriter.applyTo(CardWriter.parse(SECTION_YAML), CardChange.Upsert(edited)))
        assertEquals(EDITED_YAML, out)
    }

    @Test fun deleteAndAdd() {
        val gone = CardWriter.applyTo(CardWriter.parse(SECTION_YAML), CardChange.Delete("git", "everyday", "git-yes-or-no"))
        assertEquals(1, gone.second.size)
        val added = CardWriter.applyTo(CardWriter.parse(SECTION_YAML), CardChange.Upsert(edited.copy(id = "git-new")))
        assertEquals("git-new", added.second.last()["id"])
        assertEquals(3, added.second.size)
    }

    @Test fun emptySectionAfterDeletingEverything() {
        var p = CardWriter.parse(SECTION_YAML)
        p = CardWriter.applyTo(p, CardChange.Delete("git", "everyday", "git-yes-or-no")).let { it.first to it.second.toMutableList() }
        p = CardWriter.applyTo(p, CardChange.Delete("git", "everyday", "git-what-does-git-status-show")).let { it.first to it.second.toMutableList() }
        assertTrue(CardWriter.render(p).endsWith("cards: []\n"))
    }

    @Test fun commitsWithShaAndMessage() = runTest {
        server.enqueue(fileResponse(SECTION_YAML, sha = "s1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        writer.apply("me/cards", "tok", CardChange.Upsert(edited))
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/repos/me/cards/contents/cards/git/everyday.yaml", get.path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = Json.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals("s1", body["sha"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("git-what-does-git-status-show"))
        assertEquals(EDITED_YAML, String(codec.decode(body["content"]!!.jsonPrimitive.content)))
    }

    @Test fun retriesOnceOnConflict() = runTest {
        server.enqueue(fileResponse(SECTION_YAML, sha = "old"))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(fileResponse(SECTION_YAML, sha = "new"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        writer.apply("me/cards", "tok", CardChange.Upsert(edited))
        assertEquals(4, server.requestCount)
    }

    @Test fun readOnlyTokenGetsAClearMessage() = runTest {
        server.enqueue(fileResponse(SECTION_YAML))
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            writer.apply("me/cards", "tok", CardChange.Upsert(edited)); fail()
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("Read and write"))
        }
    }
}
