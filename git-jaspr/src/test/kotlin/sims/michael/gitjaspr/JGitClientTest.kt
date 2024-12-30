package sims.michael.gitjaspr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import kotlin.test.assertEquals

class JGitClientTest {
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
}
