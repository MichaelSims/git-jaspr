package sims.michael.gitjaspr

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Each test addresses a distinct route on the shared embedded server, so test instances don't stomp
 * on a mutable response field. This pattern survives parallel test execution.
 */
class KtorReleaseFetcherTest {

    @Test
    fun `parses GitHub releases response and picks newest non-draft of each channel`() {
        KtorReleaseFetcher(baseUrl = baseUrlFor("/standard")).use { fetcher ->
            val releases = assertNotNull(fetcher.fetchLatestReleases())
            assertEquals("v2.1.0", releases.stable?.tag)
            assertEquals("v2.2.0-beta.1", releases.prerelease?.tag)
            assertEquals("https://example.com/r/v2.1.0", releases.stable?.htmlUrl)
        }
    }

    @Test
    fun `skips draft releases`() {
        KtorReleaseFetcher(baseUrl = baseUrlFor("/drafts")).use { fetcher ->
            val releases = assertNotNull(fetcher.fetchLatestReleases())
            assertEquals("v2.1.0", releases.stable?.tag, "drafts must be ignored")
        }
    }

    @Test
    fun `returns null on non-200 status`() {
        KtorReleaseFetcher(baseUrl = baseUrlFor("/forbidden")).use { fetcher ->
            assertNull(fetcher.fetchLatestReleases())
        }
    }

    @Test
    fun `returns null on malformed JSON`() {
        KtorReleaseFetcher(baseUrl = baseUrlFor("/malformed")).use { fetcher ->
            assertNull(fetcher.fetchLatestReleases())
        }
    }

    @Test
    fun `returns releases with null channels when the array is empty`() {
        KtorReleaseFetcher(baseUrl = baseUrlFor("/empty")).use { fetcher ->
            val releases = assertNotNull(fetcher.fetchLatestReleases())
            assertNull(releases.stable)
            assertNull(releases.prerelease)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_NETWORK_TESTS", matches = "1")
    fun `integration - fetches against real GitHub`() {
        // Gated by RUN_NETWORK_TESTS=1 to avoid network flakes and rate limits in normal runs.
        KtorReleaseFetcher().use { fetcher ->
            val releases = assertNotNull(fetcher.fetchLatestReleases())
            assertTrue(
                releases.stable != null || releases.prerelease != null,
                "Expected at least one release on either channel for $UPDATE_CHECK_REPO_OWNER/" +
                    UPDATE_CHECK_REPO_NAME,
            )
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_NETWORK_TESTS", matches = "1")
    fun `integration - end-to-end notice rendering for an outdated current version`() {
        // Exercises the full stack (fetch → policy → formatted notice) against real GitHub. Pins
        // the "current version" to v0.0.1 so any real release qualifies as newer.
        val tempState =
            java.nio.file.Files.createTempDirectory("update-check-integration")
                .resolve("state.json")
                .toFile()
        try {
            KtorReleaseFetcher().use { fetcher ->
                val check =
                    UpdateCheck(
                        stateFile = tempState,
                        fetcher = fetcher,
                        now = java.time.Instant::now,
                        currentVersion = "v0.0.1",
                        interval = java.time.Duration.ofHours(24),
                        json = kotlinx.serialization.json.Json,
                        skip = { false },
                    )
                val notice = assertNotNull(check.maybeNotice())
                assertEquals("v0.0.1", notice.currentVersion)
                assertTrue(
                    notice.latestVersion.startsWith("v"),
                    "Expected a v-prefixed tag, got: ${notice.latestVersion}",
                )
                assertTrue(
                    notice.url.startsWith(
                        "https://github.com/$UPDATE_CHECK_REPO_OWNER/$UPDATE_CHECK_REPO_NAME/releases/tag/"
                    ),
                    "Expected a real release URL, got: ${notice.url}",
                )
                val message = notice.formatMessage()
                assertTrue(message.startsWith("Update available:"))
                assertTrue(message.contains(notice.latestVersion))
            }
        } finally {
            tempState.parentFile?.deleteRecursively()
        }
    }

    companion object {
        private lateinit var server: EmbeddedServer<*, *>

        private const val STANDARD_RESPONSE =
            """
            [
              {"tag_name": "v2.2.0-beta.1", "draft": false, "prerelease": true,
               "html_url": "https://example.com/r/v2.2.0-beta.1"},
              {"tag_name": "v2.1.0", "draft": false, "prerelease": false,
               "html_url": "https://example.com/r/v2.1.0"},
              {"tag_name": "v2.1.0-rc.1", "draft": false, "prerelease": true,
               "html_url": "https://example.com/r/v2.1.0-rc.1"},
              {"tag_name": "v2.0.0", "draft": false, "prerelease": false,
               "html_url": "https://example.com/r/v2.0.0"}
            ]
            """

        private const val DRAFTS_RESPONSE =
            """
            [
              {"tag_name": "v3.0.0", "draft": true, "prerelease": false,
               "html_url": "https://example.com/r/v3.0.0"},
              {"tag_name": "v2.1.0", "draft": false, "prerelease": false,
               "html_url": "https://example.com/r/v2.1.0"}
            ]
            """

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server =
                embeddedServer(ServerCIO, port = 0) {
                    routing {
                        get("/standard/repos/{owner}/{repo}/releases") {
                            call.respondText(STANDARD_RESPONSE, ContentType.Application.Json)
                        }
                        get("/drafts/repos/{owner}/{repo}/releases") {
                            call.respondText(DRAFTS_RESPONSE, ContentType.Application.Json)
                        }
                        get("/empty/repos/{owner}/{repo}/releases") {
                            call.respondText("[]", ContentType.Application.Json)
                        }
                        get("/malformed/repos/{owner}/{repo}/releases") {
                            call.respondText("not json", ContentType.Application.Json)
                        }
                        get("/forbidden/repos/{owner}/{repo}/releases") {
                            call.respondText(
                                "nope",
                                ContentType.Text.Plain,
                                HttpStatusCode.Forbidden,
                            )
                        }
                    }
                }
            server.start(wait = false)
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop(0, 0)
        }

        private fun baseUrlFor(prefix: String): String = "http://localhost:${serverPort()}$prefix"

        private fun serverPort(): Int = runBlocking {
            server.engine.resolvedConnectors().first().port
        }
    }
}
