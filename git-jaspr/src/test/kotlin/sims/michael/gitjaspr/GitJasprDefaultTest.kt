package sims.michael.gitjaspr

import kotlin.test.assertEquals
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.githubtests.GitHubTestHarness

class GitJasprDefaultTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)

    override suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long,
        pollingDelay: Long,
    ) {
        // No op
    }

    override suspend fun <T> assertEventuallyEquals(expected: T, getActual: suspend () -> T) =
        assertEquals(expected, getActual())
}
