package io.github.absolutezero000.deckhand

import io.github.absolutezero000.deckhand.data.GitHubAuth
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

class GitHubAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: GitHubAuth

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("").toString().trimEnd('/')
        auth = GitHubAuth(OkHttpClient(), webBase = base, apiBase = base)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun startsDeviceFlowWithClientId() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
        ))
        val code = auth.start("Iv1.client")
        assertEquals("ABCD-1234", code.user_code)
        val req = server.takeRequest()
        assertEquals("/login/device/code", req.path)
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("client_id=Iv1.client", req.body.readUtf8())
    }

    @Test fun pollMapsEveryAnswer() = runTest {
        listOf(
            """{"error":"authorization_pending"}""" to GitHubAuth.Poll.Pending,
            """{"error":"slow_down","interval":10}""" to GitHubAuth.Poll.SlowDown(10),
            """{"error":"expired_token"}""" to GitHubAuth.Poll.Expired,
            """{"error":"access_denied"}""" to GitHubAuth.Poll.Denied,
            """{"access_token":"ghu_x","token_type":"bearer"}""" to GitHubAuth.Poll.Token("ghu_x"),
        ).forEach { (body, expected) ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(expected, auth.poll("Iv1.client", "dc"))
        }
        val req = server.takeRequest()
        assertEquals("/login/oauth/access_token", req.path)
        assertTrue(req.body.readUtf8().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test fun listsReposAcrossInstallationsAndPages() = runTest {
        server.enqueue(MockResponse().setBody("""{"total_count":2,"installations":[{"id":1},{"id":2}]}"""))
        val page1 = (1..100).joinToString(",") { """{"full_name":"me/r$it"}""" }
        server.enqueue(MockResponse().setBody("""{"total_count":101,"repositories":[$page1]}"""))
        server.enqueue(MockResponse().setBody("""{"total_count":101,"repositories":[{"full_name":"me/zz"}]}"""))
        server.enqueue(MockResponse().setBody("""{"total_count":1,"repositories":[{"full_name":"org/Cards"}]}"""))
        val repos = auth.repos("ghu_x")
        assertEquals(102, repos.size)
        assertEquals("me/r1", repos.first())
        assertTrue("org/Cards" in repos)
        assertEquals("Bearer ghu_x", server.takeRequest().getHeader("Authorization"))
        assertEquals("/user/installations/1/repositories?per_page=100&page=1", server.takeRequest().path)
        assertEquals("/user/installations/1/repositories?per_page=100&page=2", server.takeRequest().path)
    }

    @Test fun expiredTokenIsAnAuthError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            auth.login("bad"); fail()
        } catch (e: SyncException) {
            assertEquals(SyncException.Kind.AUTH, e.kind)
        }
    }
}
