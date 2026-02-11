package sims.michael.gitjaspr

import com.apollographql.apollo.ApolloClient
import com.apollographql.ktor.http.KtorHttpEngine
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.graphql.ErrorMappingInterceptor
import sims.michael.gitjaspr.graphql.RateLimitRetryInterceptor
import sims.michael.gitjaspr.testing.NativeImageMetadata
import sims.michael.gitjaspr.testing.toStringWithClickableURI

/**
 * Exercises production code paths to collect native-image metadata via the tracing agent.
 *
 * Run with: `./gradlew :git-jaspr:nativeImageMetadata`
 *
 * This attaches `-agentlib:native-image-agent=config-merge-dir=...` to the test JVM, capturing all
 * dynamic class loading, reflection, resource access, and JNI calls made during test execution.
 */
@NativeImageMetadata
class NativeImageMetadataTest {

    // region Apollo/GraphQL tests (mock server)

    @Test
    fun `exercise getPullRequestsByHeadRef`() = runBlocking {
        val client = buildGitHubClient()
        val prs = client.getPullRequestsByHeadRef("jaspr/main/test-id")
        assertEquals(1, prs.size)
        assertEquals("Test PR", prs.first().title)
    }

    @Test
    fun `exercise createPullRequest`() = runBlocking {
        // createPullRequest internally calls fetchRepositoryId (getRepositoryId query) first
        val client = buildGitHubClient()
        val pr =
            client.createPullRequest(
                PullRequest(
                    id = null,
                    commitId = "test-id",
                    number = null,
                    headRefName = "jaspr/main/test-id",
                    baseRefName = "main",
                    title = "New PR",
                    body = "New body",
                )
            )
        assertNotNull(pr.id)
        assertEquals(42, pr.number)
    }

    @Test
    fun `exercise updatePullRequest`() = runBlocking {
        val client = buildGitHubClient()
        client.updatePullRequest(
            PullRequest(
                id = "PR_existing",
                commitId = "test-id",
                number = 1,
                headRefName = "jaspr/main/test-id",
                baseRefName = "main",
                title = "Updated",
                body = "Updated body",
            )
        )
    }

    @Test
    fun `exercise closePullRequest`() = runBlocking {
        val client = buildGitHubClient()
        client.closePullRequest(
            PullRequest(
                id = "PR_existing",
                commitId = "test-id",
                number = 1,
                headRefName = "jaspr/main/test-id",
                baseRefName = "main",
                title = "To close",
                body = "body",
            )
        )
    }

    @Test
    fun `exercise approvePullRequest`() = runBlocking {
        val client = buildGitHubClient()
        client.approvePullRequest(
            PullRequest(
                id = "PR_existing",
                commitId = "test-id",
                number = 1,
                headRefName = "jaspr/main/test-id",
                baseRefName = "main",
                title = "To approve",
                body = "body",
            )
        )
    }

    @Test
    fun `exercise error mapping interceptor on 401`() {
        // Build without the Ktor Auth plugin so the raw 401 reaches ErrorMappingInterceptor.
        // Use X-Test-Mode header so this doesn't affect concurrent tests.
        val client = buildGitHubClient(includeAuth = false, testMode = TestMode.UNAUTHORIZED)
        val exception =
            assertThrows<GitJasprException> {
                runBlocking { client.getPullRequestsByHeadRef("jaspr/main/test-id") }
            }
        assertContains(exception.message, "authorization")
    }

    @Test
    fun `exercise rate limit retry interceptor`() {
        rateLimitAttempts.set(0)
        // Use X-Test-Mode header so only this client's requests trigger rate limiting
        val client = buildGitHubClient(testMode = TestMode.RATE_LIMITED)
        // The first request returns a rate limit error, retry (with 0ms delay) succeeds
        runBlocking { client.getPullRequestsByHeadRef("jaspr/main/test-id") }
        assertEquals(2, rateLimitAttempts.get())
    }

    // endregion

    // region JGit/GitJaspr tests (via test harness)

    @Test
    fun `exercise push status merge and clean`() {
        withTestSetup(useFakeRemote = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                            localRefs += "development"
                        }
                    }
                }
            )
            gitJaspr.push(stackName = "metadata-test")
            gitJaspr.getStatusString()

            val refSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
            // merge throws because fake-remote PRs aren't fully mergeable (no checks/approvals),
            // but calling it still exercises the merge code path up to the mergeability check.
            assertThrows<GitJasprException> { gitJaspr.merge(refSpec) }

            val cleanPlan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            gitJaspr.executeCleanPlan(cleanPlan)
        }
    }

    @Test
    fun `exercise stack checkout rename and delete`() {
        withTestSetup(useFakeRemote = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                }
            )
            gitJaspr.push(stackName = "stack-a")

            val stacks = gitJaspr.getNamedStacks(DEFAULT_TARGET_REF)
            val stack = checkNotNull(stacks.find { stackRef -> stackRef.stackName == "stack-a" })
            gitJaspr.checkoutNamedStack(stack)

            gitJaspr.renameStack("stack-a", "stack-b", DEFAULT_TARGET_REF)
            gitJaspr.deleteStack("stack-b", DEFAULT_TARGET_REF)
        }
    }

    @Test
    fun `exercise install commit id hook`() {
        withTestSetup(useFakeRemote = true) { gitJaspr.installCommitIdHook() }
    }

    @Test
    fun `exercise suggest stack name`() {
        withTestSetup(useFakeRemote = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "my-feature-change"
                            localRefs += "development"
                        }
                    }
                }
            )
            val suggested = gitJaspr.suggestStackName()
            logger.info("Suggested stack name: {}", suggested)
        }
    }

    // endregion

    // region Serialization

    @Test
    fun `exercise config serialization`() {
        val json = Json { prettyPrint = true }
        val config =
            Config(
                workingDirectory = File("/tmp/test"),
                remoteName = "origin",
                gitHubInfo = GitHubInfo("github.com", "owner", "repo"),
            )
        val serialized = json.encodeToString(config)
        assertContains(serialized, "github.com")
    }

    // endregion

    // region CLI

    @Test
    fun `exercise cli command tree`() {
        // Constructing the command tree exercises Clikt option/subcommand registration.
        // Parsing --help exercises Mordant help formatting without needing a real AppWiring.
        val tempDir = Files.createTempDirectory("cli-metadata-test").toFile()
        JGitClient(tempDir).init()
        System.setProperty(WORKING_DIR_PROPERTY_NAME, tempDir.absolutePath)

        try {
            GitJasprRoot()
                .versionOption(VERSION)
                .subcommands(
                    Status(),
                    Push(),
                    Checkout(),
                    Merge(),
                    AutoMerge(),
                    Clean(),
                    Stack().subcommands(StackList(), StackRename(), StackDelete()),
                    PreviewTheme(),
                    InstallCommitIdHook(),
                    NoOp(),
                )
                .parse(listOf("--help"))
        } catch (_: PrintHelpMessage) {
            // Expected: --help triggers PrintHelpMessage before run() is called
        } finally {
            System.clearProperty(WORKING_DIR_PROPERTY_NAME)
            tempDir.deleteRecursively()
        }
    }

    // endregion

    private enum class TestMode {
        UNAUTHORIZED,
        RATE_LIMITED,
    }

    private fun buildGitHubClient(
        includeAuth: Boolean = true,
        testMode: TestMode? = null,
    ): GitHubClientImpl {
        val httpClient =
            HttpClient(ClientCIO) {
                if (includeAuth) {
                    install(Auth) {
                        bearer { loadTokens { BearerTokens("fake-token", "fake-token") } }
                    }
                }
            }
        val apolloClient =
            ApolloClient.Builder()
                .serverUrl("http://localhost:${server.serverPort()}/graphql")
                .httpEngine(KtorHttpEngine(httpClient))
                .addHttpInterceptor(ErrorMappingInterceptor())
                .addInterceptor(RateLimitRetryInterceptor(delays = listOf(0L, 0L, 0L, 0L)))
                .apply { if (testMode != null) addHttpHeader(TEST_MODE_HEADER, testMode.name) }
                .build()
        val config = Config(tempDir, "origin", GitHubInfo("example.com", "TestOwner", "TestRepo"))
        return GitHubClientImpl(
            apolloClient,
            JGitClient(tempDir),
            config,
            DEFAULT_REMOTE_BRANCH_PREFIX,
        )
    }

    companion object {

        private val logger = LoggerFactory.getLogger(NativeImageMetadataTest::class.java)

        private const val TEST_MODE_HEADER = "X-Test-Mode"
        private lateinit var server: EmbeddedServer<*, *>
        private val tempDir =
            checkNotNull(Files.createTempDirectory("native-image-metadata").toFile()).also {
                logger.info("Temp dir created in {}", it.toStringWithClickableURI())
            }
        private val rateLimitAttempts = AtomicInteger(0)

        private fun EmbeddedServer<*, *>.serverPort() = runBlocking {
            engine.resolvedConnectors().first().port
        }

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server =
                embeddedServer(ServerCIO, port = 0) {
                        routing {
                            post("/graphql") {
                                // Use per-request header to control behavior, safe for concurrent
                                // test execution
                                val testModeHeader = call.request.header(TEST_MODE_HEADER)
                                if (testModeHeader == null) {
                                    val body = call.receiveText()
                                    val json = Json.parseToJsonElement(body).jsonObject
                                    val operationName =
                                        json["operationName"]?.jsonPrimitive?.content

                                    val response =
                                        when (operationName) {
                                            "getRepositoryId" -> GET_REPOSITORY_ID_RESPONSE
                                            "getPullRequestsByHeadRef" ->
                                                GET_PRS_BY_HEAD_REF_RESPONSE

                                            "createPullRequest" -> CREATE_PR_RESPONSE
                                            "updatePullRequest" -> UPDATE_PR_RESPONSE
                                            "closePullRequest" -> CLOSE_PR_RESPONSE
                                            "addPullRequestReview" -> ADD_PR_REVIEW_RESPONSE
                                            else ->
                                                // language=JSON
                                                """
                                                {
                                                  "errors": [
                                                    {
                                                      "message": "Unknown operation: $operationName"
                                                    }
                                                  ]
                                                }
                                                """
                                                    .trimIndent()
                                        }
                                    call.respondText(response, ContentType.Application.Json)
                                } else {
                                    when (TestMode.valueOf(testModeHeader)) {
                                        TestMode.UNAUTHORIZED -> {
                                            call.respondText(
                                                "Unauthorized",
                                                ContentType.Text.Plain,
                                                HttpStatusCode.Unauthorized,
                                            )
                                        }

                                        TestMode.RATE_LIMITED -> {
                                            val count = rateLimitAttempts.getAndIncrement()
                                            if (count == 0) {
                                                call.respondText(
                                                    RATE_LIMIT_ERROR_RESPONSE,
                                                    ContentType.Application.Json,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .start(wait = false)
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop(0, 0)
        }

        // region Mock GraphQL responses

        private const val RATE_LIMIT_FIELDS =
            """
            "__typename": "RateLimit", "cost": 1, "used": 1, "limit": 5000, "remaining": 4999, "nodeCount": 1, "resetAt": "2024-01-01T00:00:00Z"
            """

        private const val PR_FIELDS =
            """
            "__typename": "PullRequest",
            "id": "PR_abc123",
            "number": 42,
            "title": "Test PR",
            "body": "Test body",
            "baseRefName": "main",
            "headRefName": "jaspr/main/test-id",
            "reviewDecision": "APPROVED",
            "permalink": "https://github.com/test/pr/1",
            "isDraft": false,
            "commits": {
                "nodes": [{
                    "commit": {
                        "statusCheckRollup": {
                            "state": "SUCCESS"
                        }
                    }
                }]
            }
            """

        private const val GET_REPOSITORY_ID_RESPONSE =
            """
            {
                "data": {
                    "rateLimit": { $RATE_LIMIT_FIELDS },
                    "repository": { "id": "R_test123" }
                }
            }"""

        private const val GET_PRS_BY_HEAD_REF_RESPONSE =
            """
            {
                "data": {
                    "rateLimit": { $RATE_LIMIT_FIELDS },
                    "repository": {
                        "pullRequests": {
                            "nodes": [{ $PR_FIELDS }]
                        }
                    }
                }
            }"""

        private const val CREATE_PR_RESPONSE =
            """
            {
                "data": {
                    "createPullRequest": {
                        "pullRequest": { $PR_FIELDS }
                    }
                }
            }"""

        private const val UPDATE_PR_RESPONSE =
            """
            {
                "data": {
                    "updatePullRequest": {
                        "pullRequest": { "number": 42 }
                    }
                }
            }"""

        private const val CLOSE_PR_RESPONSE =
            """
            {
                "data": {
                    "closePullRequest": {
                        "pullRequest": { "id": "PR_abc123" }
                    }
                }
            }"""

        private const val ADD_PR_REVIEW_RESPONSE =
            """
            {
                "data": {
                    "addPullRequestReview": {
                        "pullRequestReview": { "id": "PRR_abc123" }
                    }
                }
            }"""

        private const val RATE_LIMIT_ERROR_RESPONSE =
            """
            {
                "data": null,
                "errors": [{"message": "was submitted too quickly"}]
            }"""

        // endregion
    }
}
