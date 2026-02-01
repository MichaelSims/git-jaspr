package sims.michael.gitjaspr

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
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.testing.DelayAfterTestMillis
import sims.michael.gitjaspr.testing.DelayAfterTestsExtension
import sims.michael.gitjaspr.testing.FunctionalTest

@FunctionalTest
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(DelayAfterTestsExtension::class)
@DelayAfterTestMillis(2_000)
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
                    val checksPass =
                        gitHub.getPullRequestsById(commitFilter.toList()).associate {
                            checkNotNull(it.id) to (it.checksPass == true)
                        }
                    logger.trace("Checks pass: {}", checksPass)
                    if (checksPass.values.all { it }) break
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
