package sims.michael.gitjaspr

import java.io.File
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

interface GitClientTest {
    val logger: Logger

    fun createGitClient(workingDirectory: File): GitClient

    @Test
    fun `getRemoteBranches should not include HEAD`() {
        // If we've pushed to HEAD, ensure that getRemoteBranches does not return it. At some point
        // git changed its behavior to include HEAD in the list of remote branches, which we don't
        // want.
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            branch {
                                commit { title = "a" }
                                commit { title = "b" }
                                commit { title = "c" }
                                commit {
                                    title = "d"
                                    localRefs += "development"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            remoteRefs += listOf("main", Constants.HEAD)
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertFalse(
                cliGit.getRemoteBranches(remoteName).any { branch ->
                    branch.name.endsWith("/${Constants.HEAD}")
                },
                "List of remote branches should not be empty",
            )
            assertEquals(cliGit.getRemoteBranches(remoteName), git.getRemoteBranches(remoteName))
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
