package sims.michael.gitjaspr

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.ExecuteCli.executeCli
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.testing.FunctionalTest
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals

/** Run this test to update the native-image metadata files */
@FunctionalTest
class GitJasprFunctionalExternalProcessTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)
    override val useFakeRemote: Boolean = false

    private val javaOptions =
        listOf("-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image")

    private fun buildHomeDirConfig() = Properties()
        .apply {
            File(System.getenv("HOME"))
                .resolve(CONFIG_FILE_NAME)
                .inputStream()
                .use(::load)
        }
        .map { (k, v) -> k.toString() to v.toString() }
        .toMap()

    override suspend fun GitHubTestHarness.push() {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = DEFAULT_REMOTE_NAME,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("push"),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        ).lines().drop(1).joinToString("\n") // Hacky, drop the first line which is debug output.
    }

    override suspend fun GitHubTestHarness.getAndPrintStatusString(refSpec: RefSpec): String {
        return executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = DEFAULT_REMOTE_NAME,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("status", refSpec.toString()),
            invokeLocation = localRepo,
            javaOptions = javaOptions,

        ).lines().drop(1).joinToString("\n") // Hacky, drop the first line which is debug output.
    }

    override suspend fun GitHubTestHarness.merge(refSpec: RefSpec) {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = DEFAULT_REMOTE_NAME,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("merge", DEFAULT_REMOTE_NAME, refSpec.toString()),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    override suspend fun GitHubTestHarness.autoMerge(refSpec: RefSpec, pollingIntervalSeconds: Int) {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = DEFAULT_REMOTE_NAME,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf(
                "auto-merge",
                DEFAULT_REMOTE_NAME,
                refSpec.toString(),
                "--interval",
                pollingIntervalSeconds.toString(),
            ),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    override suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long,
        pollingDelay: Long,
    ) {
        withTimeout(timeout) {
            launch {
                while (true) {
                    val prs = gitHub.getPullRequestsById(commitFilter.toList())
                    val conclusionStates = prs.flatMap(PullRequest::checkConclusionStates)
                    logger.trace("Conclusion states: {}", conclusionStates)
                    if (conclusionStates.size == commitFilter.size) break
                    delay(pollingDelay)
                }
            }
        }
    }

    override suspend fun <T> assertEventuallyEquals(expected: T, getActual: suspend () -> T) {
        assertEquals(
            expected,
            withTimeout(30_000L) {
                async {
                    var actual: T = getActual()
                    while (actual != expected) {
                        logger.trace("Actual {}", actual)
                        logger.trace("Expected {}", expected)
                        delay(5_000L)
                        actual = getActual()
                    }
                    actual
                }
            }.await(),
        )
    }
}
