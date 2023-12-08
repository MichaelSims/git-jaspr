package sims.michael.gitjaspr

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.testing.FunctionalTest
import kotlin.test.assertEquals

@FunctionalTest
class GitJasprFunctionalTest : GitJasprTest {
    override val logger: Logger = LoggerFactory.getLogger(GitJasprDefaultTest::class.java)
    override val useFakeRemote: Boolean = false

    // TODO this test should be in the super class but it fails when using GitHubStubClient
    //  look into this
    @Test
    fun `amend HEAD commit and re-push`(testInfo: TestInfo) {
        GitHubTestHarness.withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitJaspr.push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "four"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitJaspr.push()

            val testCommits = localGit.log(JGitClient.HEAD, 3)
            val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
            val remotePrs = gitHub.getPullRequests(testCommits)
            val remotePrIds = remotePrs.mapNotNull(PullRequest::commitId).toSet()
            assertEquals(testCommitIds, remotePrIds)

            val headCommit = localGit.log(JGitClient.HEAD, 1).single()
            val headCommitId = checkNotNull(headCommit.id)
            assertEquals("four", remotePrs.single { it.commitId == headCommitId }.title)
        }
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
