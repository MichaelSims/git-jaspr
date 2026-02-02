package sims.michael.gitjaspr

import java.io.File
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    @Test
    fun `commit author and committer`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")
            val customCommitter = Ident("Custom Committer", "committer@example.com")
            val customAuthor = Ident("Custom Author", "author@example.com")

            // Test 1: No idents
            file.writeText("content1")
            git.add("test.txt")
            val commit1 = git.commit("Commit 1")
            assertEquals(
                commit1.author,
                commit1.committer,
                "Author should equal committer when no idents specified",
            )

            // Test 2: Committer only (should also set author)
            file.writeText("content2")
            git.add("test.txt")
            val commit2 = git.commit("Commit 2", committer = customCommitter)
            assertEquals(customCommitter, commit2.committer)
            assertEquals(
                customCommitter,
                commit2.author,
                "Author should equal committer when only committer is set",
            )

            // Test 3: Author only
            file.writeText("content3")
            git.add("test.txt")
            val commit3 = git.commit("Commit 3", author = customAuthor)
            assertEquals(customAuthor, commit3.author)

            // Test 4: Both committer and author
            file.writeText("content4")
            git.add("test.txt")
            val commit4 = git.commit("Commit 4", committer = customCommitter, author = customAuthor)
            assertEquals(customCommitter, commit4.committer)
            assertEquals(customAuthor, commit4.author)
        }
    }

    @Test
    fun `cherryPick author and committer`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")
            val customCommitter = Ident("Custom Committer", "committer@example.com")
            val customAuthor = Ident("Custom Author", "author@example.com")

            file.writeText("original")
            git.add("test.txt")
            val originalCommit = git.commit("Original")

            // Test 1: No idents
            git.reset("main")
            val cherryPicked1 = git.cherryPick(originalCommit)
            assertEquals(
                originalCommit.author,
                cherryPicked1.author,
                "Author should be preserved from original",
            )

            // Test 2: Committer only
            git.reset("main")
            val cherryPicked2 = git.cherryPick(originalCommit, committer = customCommitter)
            assertEquals(customCommitter, cherryPicked2.committer)
            assertEquals(
                originalCommit.author,
                cherryPicked2.author,
                "Author should be preserved when only committer is set",
            )

            // Test 3: Author only
            git.reset("main")
            val cherryPicked3 = git.cherryPick(originalCommit, author = customAuthor)
            assertEquals(customAuthor, cherryPicked3.author)

            // Test 4: Both committer and author
            git.reset("main")
            val cherryPicked4 =
                git.cherryPick(originalCommit, committer = customCommitter, author = customAuthor)
            assertEquals(customCommitter, cherryPicked4.committer)
            assertEquals(customAuthor, cherryPicked4.author)
        }
    }

    @Test
    fun `setCommitId author and committer`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")
            val customCommitter = Ident("Custom Committer", "committer@example.com")
            val customAuthor = Ident("Custom Author", "author@example.com")

            // Test 1: No idents
            file.writeText("content1")
            git.add("test.txt")
            val commit1 = git.commit("Commit 1")
            git.setCommitId("id-1")
            val modified1 = git.log("HEAD", 1).single()
            assertEquals(commit1.author, modified1.author, "Author should be preserved")
            assertEquals(commit1.committer, modified1.committer, "Committer should be preserved")

            // Test 2: Committer only
            file.writeText("content2")
            git.add("test.txt")
            val commit2 = git.commit("Commit 2")
            git.setCommitId("id-2", committer = customCommitter)
            val modified2 = git.log("HEAD", 1).single()
            assertEquals(customCommitter, modified2.committer)
            assertEquals(
                commit2.author,
                modified2.author,
                "Author should be preserved when only committer is set",
            )

            // Test 3: Author only
            file.writeText("content3")
            git.add("test.txt")
            git.commit("Commit 3")
            git.setCommitId("id-3", author = customAuthor)
            val modified3 = git.log("HEAD", 1).single()
            assertEquals(customAuthor, modified3.author)

            // Test 4: Both committer and author
            file.writeText("content4")
            git.add("test.txt")
            git.commit("Commit 4")
            git.setCommitId("id-4", committer = customCommitter, author = customAuthor)
            val modified4 = git.log("HEAD", 1).single()
            assertEquals(customCommitter, modified4.committer)
            assertEquals(customAuthor, modified4.author)
        }
    }

    @Test
    fun `amend commit with no content changes`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")

            file.writeText("content1")
            git.add("test.txt")
            val commit1 = git.commit("Commit 1")
            val commitDate1 = commit1.commitDate

            // The delay is required to verify that the commitDate is bumped, even when not changing
            // any content
            val delayInMillis = 1_500L
            Thread.sleep(delayInMillis)
            val commit2 = git.commit(amend = true)
            val commitDate2 = commit2.commitDate

            // The delay is required to verify that the commitDate is bumped, even when not changing
            // any content
            Thread.sleep(delayInMillis)
            val commit3 = git.commit(amend = true)
            val commitDate3 = commit3.commitDate

            // Committer date should be different after each amend operation
            assertNotEquals(commitDate1, commitDate2)
            assertNotEquals(commitDate2, commitDate3)
            assertNotEquals(commitDate1, commitDate3)
        }
    }

    @Test
    fun `amend commit message`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")

            file.writeText("content1")
            git.add("test.txt")
            git.commit("Original message", footerLines = mapOf("Key1" to "value1"))

            // Test 1: Amend with a new message, null footerLines (should keep original footers)
            val commit2 = git.commit("Amended message", amend = true)
            assertEquals("Amended message", commit2.shortMessage)
            assertEquals("value1", CommitParsers.getFooters(commit2.fullMessage)["Key1"])

            // Test 2: Amend with a null message, null footerLines (should keep both)
            val commit3 = git.commit(amend = true)
            assertEquals("Amended message", commit3.shortMessage)
            assertEquals("value1", CommitParsers.getFooters(commit3.fullMessage)["Key1"])

            // Test 3: Amend with a new message and new footers
            val commit4 =
                git.commit("New message", footerLines = mapOf("Key2" to "value2"), amend = true)
            assertEquals("New message", commit4.shortMessage)
            assertEquals(null, CommitParsers.getFooters(commit4.fullMessage)["Key1"])
            assertEquals("value2", CommitParsers.getFooters(commit4.fullMessage)["Key2"])

            // Test 4: Amend with a null message and new footers (should keep the message, replace
            // footers)
            val commit5 = git.commit(footerLines = mapOf("Key3" to "value3"), amend = true)
            assertEquals("New message", commit5.shortMessage)
            assertEquals(null, CommitParsers.getFooters(commit5.fullMessage)["Key2"])
            assertEquals("value3", CommitParsers.getFooters(commit5.fullMessage)["Key3"])
        }
    }

    @Test
    fun `amend commit footer lines`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")

            file.writeText("content1")
            git.add("test.txt")
            git.commit("Message", footerLines = mapOf("Key1" to "value1"))

            // Test 1: Amend with the same message and new footers (footers should be replaced)
            val commit2 =
                git.commit("Message", footerLines = mapOf("Key2" to "value2"), amend = true)
            assertEquals("Message", commit2.shortMessage)
            assertEquals(null, CommitParsers.getFooters(commit2.fullMessage)["Key1"])
            assertEquals("value2", CommitParsers.getFooters(commit2.fullMessage)["Key2"])

            // Test 2: Amend with a null message and null footers (should keep both)
            val commit3 = git.commit(amend = true)
            assertEquals("Message", commit3.shortMessage)
            assertEquals("value2", CommitParsers.getFooters(commit3.fullMessage)["Key2"])

            // Test 3: Amend with the new message and null footers (should keep existing footers)
            val commit4 = git.commit("New Message", amend = true)
            assertEquals("New Message", commit4.shortMessage)
            assertEquals("value2", CommitParsers.getFooters(commit4.fullMessage)["Key2"])

            // Test 4: Amend with a null message and empty footers (should clear footers)
            val commit5 = git.commit(footerLines = emptyMap(), amend = true)
            assertEquals("New Message", commit5.shortMessage)
            assertEquals(null, CommitParsers.getFooters(commit5.fullMessage)["Key2"])
        }
    }

    @Test
    fun `amend commit committer`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")
            val customCommitter = Ident("Custom Committer", "committer@example.com")

            file.writeText("content1")
            git.add("test.txt")
            val commit1 = git.commit("Message")
            val originalAuthor = commit1.author

            // Amend with custom committer only (no message change)
            val commit2 = git.commit(committer = customCommitter, amend = true)

            // Committer should change, author should remain the same
            assertEquals(customCommitter, commit2.committer)
            assertEquals(originalAuthor, commit2.author)
        }
    }

    @Test
    fun `amend commit author`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Initial"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = createGitClient(localGit.workingDirectory)
            val file = File(localGit.workingDirectory, "test.txt")
            val customAuthor = Ident("Custom Author", "author@example.com")

            file.writeText("content1")
            git.add("test.txt")
            val commit1 = git.commit("Message")
            val originalCommitter = commit1.committer

            // Amend with custom author only (no message change)
            val commit2 = git.commit(author = customAuthor, amend = true)

            // Author should change, committer should remain the same
            assertEquals(originalCommitter, commit2.committer)
            assertEquals(customAuthor, commit2.author)
        }
    }

    @Test
    fun `getUpstreamBranch returns null when in detached HEAD`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )
            localGit.checkout(localGit.log().last().hash)
            assertNull(localGit.getUpstreamBranch(remoteName))
        }
    }

    @Test
    fun `setUpstreamBranch when in detached HEAD fails`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            remoteRefs += "remoteBranch"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )
            localGit.checkout(localGit.log().last().hash)
            assertThrows<IllegalStateException> {
                localGit.setUpstreamBranch(remoteName, "remoteBranch")
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
