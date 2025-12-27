package sims.michael.gitjaspr

import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.ExecuteCli.executeCli
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.testing.DelayAfterTestMillis
import sims.michael.gitjaspr.testing.DelayAfterTestsExtension
import sims.michael.gitjaspr.testing.FunctionalTest

/**
 * Run this test to update the native-image metadata files
 *
 * Keep in mind this test class isn't really for verifications. It's mainly to provide a way to
 * update the native-image metadata. Some tests may be painful to get to pass under this setup.
 * [GitJasprFunctionalTest] should be the one used to verify behavior.
 */
@FunctionalTest
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(DelayAfterTestsExtension::class)
@DelayAfterTestMillis(2_000)
class GitJasprFunctionalExternalProcessTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)
    override val useFakeRemote: Boolean = false

    private val javaOptions =
        listOf(
            "-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image"
        )

    private fun buildHomeDirConfig() =
        Properties()
            .apply {
                File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME).inputStream().use(::load)
            }
            .map { (k, v) -> k.toString() to v.toString() }
            .toMap()

    override suspend fun GitHubTestHarness.push() {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = remoteName,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("push", remoteName),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    override suspend fun GitHubTestHarness.getAndPrintStatusString(refSpec: RefSpec): String {
        return executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = remoteName,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("status", remoteName, refSpec.toString()),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    override suspend fun GitHubTestHarness.merge(refSpec: RefSpec) {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = remoteName,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings = listOf("merge", remoteName, refSpec.toString()),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    override suspend fun GitHubTestHarness.autoMerge(
        refSpec: RefSpec,
        pollingIntervalSeconds: Int,
    ) {
        executeCli(
            scratchDir = scratchDir,
            remoteUri = remoteUri,
            remoteName = remoteName,
            extraCliArgs = emptyList(),
            homeDirConfig = buildHomeDirConfig(),
            repoDirConfig = emptyMap(),
            strings =
                listOf(
                    "auto-merge",
                    remoteName,
                    refSpec.toString(),
                    "--interval",
                    pollingIntervalSeconds.toString(),
                ),
            invokeLocation = localRepo,
            javaOptions = javaOptions,
        )
    }

    // Too painful to try to get this type of test to work with external processes, so we'll opt out
    override fun `push fails when multiple PRs for a given commit ID exist`() = Unit

    // Another test that we'll opt out of, since it pushes in one pass and merges in a second one.
    // Since we don't currently have a mechanism to control which GitHub PAT is used for the push
    // (it'll be whichever one is enabled in the main config file of the user running the test), we
    // run into the "Can not approve your own pull request" error for this one. This could be fixed
    // by making the PAT selection configurable for the external process test, but for now I'm
    // opting out.
    override fun `merge - push and merge`() = Unit

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
                }
                .await(),
        )
    }
}
