package sims.michael.gitjaspr

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup

/** Tests for [JGitClient]'s upstream-tracking methods. */
class JGitClientTest {
    @Test
    fun `setUpstreamBranch happy path`() {
        withTestSetup {
            val jgit = JGitClient(localGit.workingDirectory)
            val branchName = "new-branch"
            localGit.branch(branchName)
            localGit.checkout(branchName)
            localGit.push(listOf(RefSpec(branchName, branchName)), remoteName)
            jgit.setUpstreamBranch(remoteName, branchName)
            assertEquals(branchName, jgit.getUpstreamBranch(remoteName)?.name)
        }
    }

    @Test
    fun `setUpstreamBranch fails if remote branch does not exist`() {
        withTestSetup {
            val jgit = JGitClient(localGit.workingDirectory)
            localGit.branch("new-branch")
            assertThrows<IllegalArgumentException> {
                jgit.setUpstreamBranch(remoteName, "does-not-exist")
            }
        }
    }
}
