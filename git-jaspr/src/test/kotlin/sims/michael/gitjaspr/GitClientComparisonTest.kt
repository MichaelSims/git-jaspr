package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

class GitClientComparisonTest {
    @Test
    fun `compare logAll`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                },
            )

            val git = CliGitClient(localGit.workingDirectory)
            assertEquals(localGit.logAll().sortedBy(Commit::hash).first(), git.logAll().sortedBy(Commit::hash).first())
        }
    }
}
