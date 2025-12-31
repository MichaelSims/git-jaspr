package sims.michael.gitjaspr

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup

class JGitClientTest : GitClientTest {
    override val logger: Logger = LoggerFactory.getLogger(JGitClientTest::class.java)

    override fun createGitClient(workingDirectory: File) = JGitClient(workingDirectory)

    @Test
    fun `setUpstreamBranch happy path`() {
        withTestSetup {
            val git = JGitClient(localGit.workingDirectory)
            val branchName = "new-branch"
            git.branch(branchName)
            git.checkout(branchName)
            localGit.push(listOf(RefSpec(branchName, branchName)), remoteName)
            git.setUpstreamBranch(remoteName, branchName)
            assertEquals(branchName, git.getUpstreamBranch(remoteName)?.name)
        }
    }

    @Test
    fun `setUpstreamBranch fails if remote branch does not exist`() {
        withTestSetup {
            val git = JGitClient(localGit.workingDirectory)
            val branchName = "new-branch"
            git.branch(branchName)
            assertThrows<IllegalArgumentException> {
                git.setUpstreamBranch(remoteName, "does-not-exist")
            }
        }
    }

    // Helper to reduce boilerplate, delegates to GitHubTestHarness.withTestSetup but applies our
    // factory function for the git client instances
    private fun withTestSetup(block: suspend GitHubTestHarness.() -> Unit): GitHubTestHarness =
        withTestSetup(
            createLocalGitClient = ::createGitClient,
            createRemoteGitClient = ::createGitClient,
            block = block,
        )
}
