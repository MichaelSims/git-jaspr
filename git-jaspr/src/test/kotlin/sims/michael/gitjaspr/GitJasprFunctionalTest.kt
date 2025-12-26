package sims.michael.gitjaspr

import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.testing.FunctionalTest

@FunctionalTest
class GitJasprFunctionalTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)
    override val useFakeRemote: Boolean = false

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
