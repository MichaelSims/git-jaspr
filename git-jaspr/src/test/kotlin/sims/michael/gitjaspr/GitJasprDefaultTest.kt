package sims.michael.gitjaspr

import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.BeforeEach
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import kotlin.test.assertEquals

class GitJasprDefaultTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)

    @BeforeEach
    fun setUp() = setGitCommitterInfo("Frank Grimes", "grimey@example.com")

    override suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long,
        pollingDelay: Long,
    ) {
        // No op
    }

    override suspend fun <T> assertEventuallyEquals(expected: T, getActual: suspend () -> T) =
        assertEquals(expected, getActual())

    @Suppress("SameParameterValue")
    private fun setGitCommitterInfo(name: String, email: String) {
        SystemReader
            .setInstance(
                MockSystemReader()
                    .apply {
                        setProperty(Constants.GIT_COMMITTER_NAME_KEY, name)
                        setProperty(Constants.GIT_COMMITTER_EMAIL_KEY, email)
                    },
            )
    }
}
