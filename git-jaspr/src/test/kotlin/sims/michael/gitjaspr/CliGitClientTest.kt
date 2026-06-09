package sims.michael.gitjaspr

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.INITIAL_COMMIT_SHORT_MESSAGE
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import sims.michael.gitjaspr.testing.toStringWithClickableURI

class CliGitClientTest : GitClientTest {

    override val logger: Logger = LoggerFactory.getLogger(CliGitClientTest::class.java)

    override fun createGitClient(workingDirectory: File) = CliGitClient(workingDirectory)

    @Test
    fun `compare logAll`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.logAll().sortedBy(Commit::hash),
                git.logAll().sortedBy(Commit::hash),
            )
        }
    }

    @Test
    fun `compare log`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.log(), git.log())
        }
    }

    @Test
    fun `compare log with count`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.log("some-other-branch", 2), git.log("some-other-branch", 2))
        }
    }

    @Test
    fun `compare log with default count`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.log("some-other-branch"), git.log("some-other-branch"))
        }
    }

    @Test
    fun `compare logRange`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.logRange("main", "some-other-branch"),
                git.logRange("main", "some-other-branch"),
            )
        }
    }

    @Test
    fun `logRange throws when given nonexistent refs`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = CliGitClient(localGit.workingDirectory)
            assertThrows<IllegalArgumentException> { git.logRange("sam", "max") }
        }
    }

    @Test
    fun `hasUncommittedChangesToTrackedFiles returns expected value`() {
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
                                    localRefs += "some-other-branch"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            localRefs += "main"
                        }
                    }
                }
            )

            val readme = localRepo.resolve("README.txt")
            check(readme.exists())
            readme.appendText("This is a change")
            val git = CliGitClient(localGit.workingDirectory)
            assertTrue(git.hasUncommittedChangesToTrackedFiles())
        }
    }

    @Test
    fun `hasUncommittedChangesToTrackedFiles ignores untracked files`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "main"
                        }
                    }
                }
            )

            val git = CliGitClient(localGit.workingDirectory)
            assertFalse(git.hasUncommittedChangesToTrackedFiles())

            // Create an untracked file — should still have no uncommitted changes
            localRepo.resolve("untracked-file.txt").writeText("Not tracked by git")
            assertFalse(git.hasUncommittedChangesToTrackedFiles())

            // Modify a tracked file — now it should have uncommitted changes
            localRepo.resolve("README.txt").appendText("Modified")
            assertTrue(git.hasUncommittedChangesToTrackedFiles())
        }
    }

    @Test
    fun `compare getCommitStack`() {
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
                                    localRefs += "main"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            remoteRefs += "main"
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getCommitStack(remoteName, DEFAULT_TARGET_REF, DEFAULT_TARGET_REF),
                git.getCommitStack(remoteName, DEFAULT_TARGET_REF, DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `compare getParents`() {
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
                                    localRefs += "main"
                                }
                            }
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            body = "This is a body"
                            remoteRefs += "main"
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val main = cliGit.log(DEFAULT_TARGET_REF, 1).single()
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.getParents(main), git.getParents(main))
        }
    }

    @Test
    fun `compare getBranches`() {
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
                            remoteRefs += "main"
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.getBranchNames(), git.getBranchNames())
        }
    }

    @Test
    fun `compare getRemoteBranches`() {
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
                cliGit.getRemoteBranches(remoteName).isEmpty(),
                "List of remote branches should not be empty",
            )
            assertEquals(cliGit.getRemoteBranches(remoteName), git.getRemoteBranches(remoteName))
        }
    }

    @Test
    fun `compare getRemoteBranchesById`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            branch {
                                commit {
                                    title = "a"
                                    localRefs += buildRemoteRef("a")
                                }
                                commit {
                                    title = "b"
                                    localRefs += buildRemoteRef("b")
                                }
                                commit {
                                    title = "c"
                                    localRefs += buildRemoteRef("c")
                                }
                                commit {
                                    title = "d"
                                    localRefs += buildRemoteRef("d")
                                }
                            }
                        }
                        commit {
                            title = "two"
                            localRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            body = "This is a body"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertFalse(
                cliGit.getRemoteBranchesById(remoteName).isEmpty(),
                "Map of remote branches should not be empty",
            )
            assertEquals(
                cliGit.getRemoteBranchesById(remoteName),
                git.getRemoteBranchesById(remoteName),
            )
        }
    }

    @Test
    fun `compare getRemoteUriOrNull`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.getRemoteUriOrNull(remoteName), git.getRemoteUriOrNull(remoteName))
        }
    }

    @Test
    fun `compare getUpstreamBranch`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.getUpstreamBranch(remoteName), git.getUpstreamBranch(remoteName))
        }
    }

    @Test
    fun `compare setUpstreamBranch`() {
        fun setAndGetUpstream(createGitClient: (File) -> GitClient): String {
            val harness = withTestSetup {
                with(createGitClient(localGit.workingDirectory)) {
                    val branchName = "new-branch"
                    branch(branchName)
                    push(listOf(RefSpec(branchName, branchName)), remoteName)
                    setUpstreamBranch(remoteName, branchName)
                }
            }
            val git = harness.localGit
            val remoteName = harness.remoteName
            val remoteBranch =
                checkNotNull(git.getUpstreamBranch(remoteName)) {
                    "No upstream branch found for remote $remoteName"
                }
            return remoteBranch.name
        }
        assertEquals(setAndGetUpstream(::CliGitClient), setAndGetUpstream(::JGitClient))
    }

    @Test
    fun `compare getUpstreamBranchName`() {
        withTestSetup {
            val branchName = "my-feature"
            localGit.branch(branchName)
            localGit.push(listOf(RefSpec(branchName, branchName)), remoteName)
            localGit.checkout(branchName)
            localGit.setUpstreamBranch(remoteName, branchName)
            localGit.checkout("main")

            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)

            // Branch with upstream configured
            assertEquals(
                cliGit.getUpstreamBranchName(branchName, remoteName),
                jGit.getUpstreamBranchName(branchName, remoteName),
            )

            // Branch with no upstream configured
            assertEquals(
                cliGit.getUpstreamBranchName("main", remoteName),
                jGit.getUpstreamBranchName("main", remoteName),
            )
        }
    }

    @Test
    fun `compare setUpstreamBranchForLocalBranch`() {
        withTestSetup {
            val branchName = "my-feature"
            localGit.branch(branchName)
            localGit.push(listOf(RefSpec(branchName, branchName)), remoteName)

            val cliDir = localGit.workingDirectory
            val cliGit = CliGitClient(cliDir)
            val jGit = JGitClient(cliDir)

            // Set upstream without checkout using CliGitClient
            cliGit.setUpstreamBranchForLocalBranch(branchName, remoteName, branchName)
            val cliResult = cliGit.getUpstreamBranchName(branchName, remoteName)

            // Unset it so we can test with JGitClient
            cliGit.setUpstreamBranchForLocalBranch(branchName, remoteName, null)
            assertNull(cliGit.getUpstreamBranchName(branchName, remoteName))

            // Set upstream without checkout using JGitClient
            jGit.setUpstreamBranchForLocalBranch(branchName, remoteName, branchName)
            val jGitResult = jGit.getUpstreamBranchName(branchName, remoteName)

            assertEquals(cliResult, jGitResult)
        }
    }

    @Test
    fun `compare setUpstreamBranchForLocalBranch unset`() {
        withTestSetup {
            val branchName = "my-feature"
            localGit.branch(branchName)
            localGit.push(listOf(RefSpec(branchName, branchName)), remoteName)

            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)

            // Set upstream, then unset with CliGitClient
            cliGit.setUpstreamBranchForLocalBranch(branchName, remoteName, branchName)
            assertNotNull(cliGit.getUpstreamBranchName(branchName, remoteName))
            cliGit.setUpstreamBranchForLocalBranch(branchName, remoteName, null)
            val cliResult = cliGit.getUpstreamBranchName(branchName, remoteName)

            // Set upstream, then unset with JGitClient
            jGit.setUpstreamBranchForLocalBranch(branchName, remoteName, branchName)
            assertNotNull(jGit.getUpstreamBranchName(branchName, remoteName))
            jGit.setUpstreamBranchForLocalBranch(branchName, remoteName, null)
            val jGitResult = jGit.getUpstreamBranchName(branchName, remoteName)

            assertEquals(cliResult, jGitResult)
        }
    }

    @Test
    fun `compare reflog`() {
        withTestSetup {
            val titles = (1..4).map(Int::toString)
            for (thisTitle in titles) {
                createCommitsFrom(
                    testCase {
                        repository {
                            commit {
                                id = "same"
                                title = thisTitle
                                localRefs += "development"
                            }
                        }
                    }
                )
            }
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.reflog(), git.reflog())
        }
    }

    @Test
    fun `compare getCurrentBranchName`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.getCurrentBranchName(), git.getCurrentBranchName())
        }
    }

    @Test
    fun `compare isHeadDetached`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.isHeadDetached(), git.isHeadDetached())
            cliGit.checkout(cliGit.log().first().hash)
            assertEquals(cliGit.isHeadDetached(), git.isHeadDetached())
        }
    }

    @Test
    fun testInit() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory.resolve("new-repo"))
            git.init()
            assertTrue(localGit.workingDirectory.resolve(".git").exists())
        }
    }

    @Test
    fun testCheckout() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "development"
                        }
                    }
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.checkout("development")
            assertEquals("one", git.log("HEAD", 1).single().shortMessage)
            git.checkout("main")
        }
    }

    @Test
    fun testClone() {
        withTestSetup {
            val cloneDirectory = scratchDir.resolve("cloned")
            val git = CliGitClient(cloneDirectory)
            git.clone(localRepo.absolutePath)
            assertTrue(cloneDirectory.resolve(".git").exists())
        }
    }

    @Test
    fun testCloneUri() {
        withTestSetup {
            val cloneDirectory = scratchDir.resolve("cloned")
            val git = CliGitClient(cloneDirectory)
            git.clone(localRepo.toURI().toString())
            assertTrue(cloneDirectory.resolve(".git").exists())
        }
    }

    @Test
    fun testFetch() {
        val tempDir =
            checkNotNull(
                    Files.createTempDirectory(CliGitClientTest::class.java.simpleName).toFile()
                )
                .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
        val remoteDir = tempDir.resolve("remote")
        val remoteGit = CliGitClient(remoteDir).init()
        remoteDir.resolve("README.txt").writeText("This is a README")
        remoteGit.add("README.txt").commit("This is a README", committer = DEFAULT_COMMITTER)
        val localGit = CliGitClient(tempDir.resolve("local")).clone(remoteDir.absolutePath)
        val newFile = remoteGit.workingDirectory.resolve("NEW.txt")
        newFile.appendText("This is a new file")
        remoteGit.add("NEW.txt").commit("Add new file", committer = DEFAULT_COMMITTER)
        val git = CliGitClient(localGit.workingDirectory)
        git.fetch(DEFAULT_REMOTE_NAME)
        assertEquals("Add new file", git.log("origin/main", 1).single().shortMessage)
    }

    @Test
    fun testReset() {
        withTestSetup {
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
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.reset("development~1")
            assertEquals("two", git.log("HEAD", 1).single().shortMessage)
        }
    }

    @Test
    fun testBranch() {
        withTestSetup {
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
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.branch("new-branch", "development^")
            assertEquals("two", git.log("new-branch", 1).single().shortMessage)
        }
    }

    @Test
    fun testDeleteBranches() {
        withTestSetup {
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
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.deleteBranches(listOf("development"))
            assertFalse(git.getBranchNames().contains("development"))
        }
    }

    @Test
    fun testDeleteBranchesEmpty() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            git.deleteBranches(emptyList())
        }
    }

    @Test
    fun testAddAndCommit() {
        withTestSetup {
            val newFile = localGit.workingDirectory.resolve("NEW.txt")
            newFile.appendText("This is a new file")
            val git = CliGitClient(localGit.workingDirectory)
            git.add("NEW.txt")
            git.commit(
                """
                Add new file

                This is a commit body

                """
                    .trimIndent(),
                mapOf("Co-authored-by" to "Michael Sims"),
                DEFAULT_COMMITTER,
            )
            assertFalse(git.hasUncommittedChangesToTrackedFiles())
        }
    }

    @Test
    fun testPush() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "development"
                        }
                    }
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.push(listOf(RefSpec("development", "main")), remoteName)
            assertEquals("one", remoteGit.log("main", 1).single().shortMessage)
        }
    }

    @Test
    fun testCherryPick() {
        withTestSetup {
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
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.checkout("main")
            git.cherryPick(localGit.log("development~1", 1).single(), DEFAULT_COMMITTER)
            assertEquals("two", git.log("HEAD", 1).single().shortMessage)
        }
    }

    @Test
    fun testSetCommitId() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            id = ""
                            localRefs += "development"
                        }
                    }
                }
            )
            val git = CliGitClient(localGit.workingDirectory)
            git.setCommitId("newCommitId", DEFAULT_COMMITTER)
            assertEquals("newCommitId", git.log("HEAD", 1).single().id)
        }
    }

    @Test
    fun testRefExists() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            assertTrue(git.refExists("main"))
            assertFalse(git.refExists("nonexistent"))
        }
    }

    @Test
    fun testGetUpstreamBranch() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            val actual = checkNotNull(git.getUpstreamBranch(remoteName))
            assertEquals(
                actual.copy(
                    name = "main",
                    commit = actual.commit.copy(shortMessage = INITIAL_COMMIT_SHORT_MESSAGE),
                ),
                actual,
            )
        }
    }

    @Test
    fun testSetUpstreamBranch() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            val branchName = "new-branch"
            git.branch(branchName)
            git.push(listOf(RefSpec(branchName, branchName)), remoteName)
            git.setUpstreamBranch(remoteName, branchName)
            assertEquals(branchName, git.getUpstreamBranch(remoteName)?.name)
        }
    }

    @Test
    fun testReflog() {
        withTestSetup {
            val titles = (1..4).map(Int::toString)
            for (thisTitle in titles) {
                // "amend" this commit 4 times
                createCommitsFrom(
                    testCase {
                        repository {
                            commit {
                                id = "same"
                                title = thisTitle
                                localRefs += "development"
                            }
                        }
                    }
                )
            }
            val git = CliGitClient(localGit.workingDirectory)
            val reflog = git.reflog()
            // Build a list of short messages in the order that HEAD moved, then reverse it to match
            // the reflog order. This bakes some assumptions about how our test harness works, so if
            // that changes, this will break
            val expectedShortMessages =
                buildList {
                        add(INITIAL_COMMIT_SHORT_MESSAGE) // The result of "git init"
                        for (title in titles) {
                            add(INITIAL_COMMIT_SHORT_MESSAGE)
                            add(title)
                        }
                    }
                    .reversed()
            assertEquals(expectedShortMessages, reflog.map(Commit::shortMessage))
        }
    }

    @Test
    fun testGetCurrentBranchName() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            assertEquals("main", git.getCurrentBranchName())
        }
    }

    @Test
    fun testIsHeadDetached() {
        withTestSetup {
            val git = CliGitClient(localGit.workingDirectory)
            assertFalse(git.isHeadDetached())
            git.checkout(git.log().first().hash)
            assertTrue(git.isHeadDetached())
        }
    }

    @Test
    fun `compare pushWithLease - ref must not exist`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)

            // Test 1: Push with lease should succeed when the ref doesn't exist
            val commitC = cliGit.log("dev", 1).single()
            val commitB = cliGit.log("dev^", 1).single()
            cliGit.pushWithLease(
                listOf(RefSpec(commitC.hash, "test-branch")),
                remoteName,
                forceWithLeaseRefs = mapOf("test-branch" to null),
            )

            // Verify the branch was created
            assertTrue(cliGit.getRemoteBranches(remoteName).any { it.name == "test-branch" })

            // Test 2: Push with lease should fail when the ref already exists
            // Try to push a different commit (commitB) to the same branch
            assertThrows<PushFailedException> {
                cliGit.pushWithLease(
                    listOf(RefSpec(commitB.hash, "test-branch")),
                    remoteName,
                    forceWithLeaseRefs = mapOf("test-branch" to null), // test-branch exists!
                )
            }

            // Test 3: Same test with JGitClient - push should succeed when the ref doesn't exist
            val commitA = jGit.log("dev^^", 1).single()
            jGit.pushWithLease(
                listOf(RefSpec(commitC.hash, "jgit-test-branch")),
                remoteName,
                forceWithLeaseRefs = mapOf("jgit-test-branch" to null),
            )

            // Verify the branch was created
            assertTrue(jGit.getRemoteBranches(remoteName).any { it.name == "jgit-test-branch" })

            // Test 4: JGit push with lease should fail when the ref already exists
            // Try to push a different commit (commitA) to the same branch
            assertThrows<PushFailedException> {
                jGit.pushWithLease(
                    listOf(RefSpec(commitA.hash, "jgit-test-branch")),
                    remoteName,
                    forceWithLeaseRefs = mapOf("jgit-test-branch" to null), // exists!
                )
            }
        }
    }

    @Test
    fun `compare pushWithLease - ref must have specific value`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                }
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)

            // Create a branch first
            val commitB = cliGit.log("dev^", 1).single()
            val commitC = cliGit.log("dev", 1).single()
            cliGit.push(listOf(RefSpec(commitB.hash, "test-branch")), remoteName)

            // Test 1: Push with lease should succeed when ref has expected value
            cliGit.pushWithLease(
                listOf(RefSpec(commitC.hash, "test-branch")),
                remoteName,
                forceWithLeaseRefs = mapOf("test-branch" to commitB.hash),
            )

            // Verify the branch was updated
            val remoteBranch =
                cliGit.getRemoteBranches(remoteName).single { it.name == "test-branch" }
            assertEquals(commitC.hash, remoteBranch.commit.hash)

            // Test 2: Push with lease should fail when ref has different value
            assertThrows<PushFailedException> {
                cliGit.pushWithLease(
                    listOf(RefSpec(commitB.hash, "test-branch")),
                    remoteName,
                    forceWithLeaseRefs =
                        mapOf("test-branch" to commitB.hash), // Wrong! It's now commitC
                )
            }

            // Test 3: Same test with JGitClient
            jGit.push(listOf(RefSpec(commitB.hash, "jgit-test-branch")), remoteName)

            jGit.pushWithLease(
                listOf(RefSpec(commitC.hash, "jgit-test-branch")),
                remoteName,
                forceWithLeaseRefs = mapOf("jgit-test-branch" to commitB.hash),
            )

            val jgitRemoteBranch =
                jGit.getRemoteBranches(remoteName).single { it.name == "jgit-test-branch" }
            assertEquals(commitC.hash, jgitRemoteBranch.commit.hash)

            // Test 4: JGit push with lease should fail when ref has different value
            assertThrows<PushFailedException> {
                jGit.pushWithLease(
                    listOf(RefSpec(commitB.hash, "jgit-test-branch")),
                    remoteName,
                    forceWithLeaseRefs = mapOf("jgit-test-branch" to commitB.hash), // Wrong!
                )
            }
        }
    }

    @Test
    fun `mergeTreeWriteTree returns result tree SHA on a clean merge`() {
        withMergeRepo { workDir, baseSha, oursSha, theirsSha ->
            val git = CliGitClient(workDir)
            val result = git.mergeTreeWriteTree(baseSha, oursSha, theirsSha)
            assertNotNull(result)
            // The result should be a valid tree SHA. Round-tripping it through `git rev-parse`
            // would confirm, but checking the format (40 hex chars) is sufficient for a unit test.
            assertTrue(
                result!!.matches("^[0-9a-f]{40}$".toRegex()),
                "expected tree SHA, got: $result",
            )
        }
    }

    @Test
    fun `mergeTreeWriteTree returns null when the merge would conflict`() {
        withConflictingMergeRepo { workDir, baseSha, oursSha, theirsSha ->
            val git = CliGitClient(workDir)
            assertNull(git.mergeTreeWriteTree(baseSha, oursSha, theirsSha))
        }
    }

    @Test
    fun `compare gitDir`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.gitDir(), jGit.gitDir())
            assertTrue(cliGit.gitDir().isDirectory, "gitDir should point at an existing directory")
        }
    }

    @Test
    fun `addWorktree and removeWorktree round-trip via CLI`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "development"
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val worktreeDir = scratchDir.resolve("worktree-cli")
            cliGit.addWorktree(worktreeDir, ref = "development")
            assertTrue(worktreeDir.resolve(".git").exists(), "worktree should have a .git pointer")
            cliGit.removeWorktree(worktreeDir, force = true)
            assertFalse(worktreeDir.exists(), "worktree dir should be gone after remove")
        }
    }

    @Test
    fun `JGitClient addWorktree throws UnsupportedOperationException`() {
        withTestSetup {
            val jGit = JGitClient(localGit.workingDirectory)
            assertThrows<UnsupportedOperationException> {
                jGit.addWorktree(scratchDir.resolve("never-created"))
            }
        }
    }

    @Test
    fun `compare cherryPickAbort recovers from a conflicting pick`() {
        // Tests both clients against the same kind of conflict: each gets a fresh repo, attempts
        // a cherry-pick that conflicts, then aborts. Both should leave the working tree clean and
        // clear any CHERRY_PICK_HEAD sentinel.
        fun runOne(makeClient: (java.io.File) -> GitClient) {
            withTestSetup {
                createCommitsFrom(
                    testCase {
                        repository {
                            commit {
                                title = "base"
                                localRefs += "main"
                            }
                        }
                    }
                )
                val git = makeClient(localGit.workingDirectory)
                val shared = localRepo.resolve("shared.txt")

                shared.writeText("A\n")
                git.add("shared.txt")
                val commitA = git.commit("a", footerLines = mapOf(COMMIT_ID_LABEL to "a"))

                git.reset("main")
                shared.writeText("B\n")
                git.add("shared.txt")
                git.commit("b", footerLines = mapOf(COMMIT_ID_LABEL to "b"))

                assertThrows<Exception> { git.cherryPick(commitA) }

                git.cherryPickAbort()

                assertFalse(
                    git.gitDir().resolve("CHERRY_PICK_HEAD").exists(),
                    "CHERRY_PICK_HEAD should be gone after cherryPickAbort",
                )
                assertFalse(
                    git.hasUncommittedChangesToTrackedFiles(),
                    "working tree should be clean after cherryPickAbort",
                )
            }
        }
        runOne(::CliGitClient)
        runOne(::JGitClient)
    }

    @Test
    fun `JGitClient removeWorktree throws UnsupportedOperationException`() {
        withTestSetup {
            val jGit = JGitClient(localGit.workingDirectory)
            assertThrows<UnsupportedOperationException> {
                jGit.removeWorktree(scratchDir.resolve("never-created"))
            }
        }
    }

    @Test
    fun `compare gitCommonDir`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.gitCommonDir(), jGit.gitCommonDir())
            assertTrue(
                cliGit.gitCommonDir().isDirectory,
                "gitCommonDir should point at an existing directory",
            )
            // In a non-worktree checkout, gitDir and gitCommonDir resolve to the same place.
            assertEquals(cliGit.gitDir(), cliGit.gitCommonDir())
        }
    }

    @Test
    fun `compare updateRef`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "development"
                        }
                    }
                }
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val jGit = JGitClient(localGit.workingDirectory)
            val targetSha = cliGit.log("development", 1).single().hash
            val olderSha = cliGit.log("development~1", 1).single().hash

            // Create a new ref with each client; verify the other client sees the same SHA.
            cliGit.updateRef("refs/jaspr-test/cli-created", targetSha)
            jGit.updateRef("refs/jaspr-test/jgit-created", targetSha)
            assertEquals(
                targetSha,
                jGit.log("refs/jaspr-test/cli-created", 1).single().hash,
                "JGit should observe the ref CLI created",
            )
            assertEquals(
                targetSha,
                cliGit.log("refs/jaspr-test/jgit-created", 1).single().hash,
                "CLI should observe the ref JGit created",
            )

            // Force-update an existing ref to a different SHA with each client; verify visibility.
            cliGit.updateRef("refs/jaspr-test/cli-created", olderSha)
            jGit.updateRef("refs/jaspr-test/jgit-created", olderSha)
            assertEquals(
                olderSha,
                jGit.log("refs/jaspr-test/cli-created", 1).single().hash,
                "JGit should observe the CLI force-update",
            )
            assertEquals(
                olderSha,
                cliGit.log("refs/jaspr-test/jgit-created", 1).single().hash,
                "CLI should observe the JGit force-update",
            )
        }
    }

    /**
     * Sets up a small repo with three commits: a base, a divergent "ours" commit touching one file,
     * and a divergent "theirs" commit touching a different file. The merge of ours/theirs against
     * the base is clean.
     */
    private fun withMergeRepo(block: (File, String, String, String) -> Unit) {
        val workDir = Files.createTempDirectory("jaspr-merge-test").toFile()
        try {
            shellGit(workDir, "init", "--initial-branch=main")
            shellGit(workDir, "config", "user.email", "test@example.com")
            shellGit(workDir, "config", "user.name", "Test")
            workDir.resolve("shared.txt").writeText("base\n")
            shellGit(workDir, "add", "shared.txt")
            shellGit(workDir, "commit", "-m", "base")
            val baseSha = shellGit(workDir, "rev-parse", "HEAD")
            workDir.resolve("ours.txt").writeText("ours\n")
            shellGit(workDir, "add", "ours.txt")
            shellGit(workDir, "commit", "-m", "ours")
            val oursSha = shellGit(workDir, "rev-parse", "HEAD")
            shellGit(workDir, "reset", "--hard", baseSha)
            workDir.resolve("theirs.txt").writeText("theirs\n")
            shellGit(workDir, "add", "theirs.txt")
            shellGit(workDir, "commit", "-m", "theirs")
            val theirsSha = shellGit(workDir, "rev-parse", "HEAD")
            block(workDir, baseSha, oursSha, theirsSha)
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Same shape as [withMergeRepo] but ours and theirs both modify the same line of the same file,
     * so the merge conflicts.
     */
    private fun withConflictingMergeRepo(block: (File, String, String, String) -> Unit) {
        val workDir = Files.createTempDirectory("jaspr-merge-conflict-test").toFile()
        try {
            shellGit(workDir, "init", "--initial-branch=main")
            shellGit(workDir, "config", "user.email", "test@example.com")
            shellGit(workDir, "config", "user.name", "Test")
            val shared = workDir.resolve("shared.txt")
            shared.writeText("base\n")
            shellGit(workDir, "add", "shared.txt")
            shellGit(workDir, "commit", "-m", "base")
            val baseSha = shellGit(workDir, "rev-parse", "HEAD")
            shared.writeText("ours\n")
            shellGit(workDir, "add", "shared.txt")
            shellGit(workDir, "commit", "-m", "ours")
            val oursSha = shellGit(workDir, "rev-parse", "HEAD")
            shellGit(workDir, "reset", "--hard", baseSha)
            shared.writeText("theirs\n")
            shellGit(workDir, "add", "shared.txt")
            shellGit(workDir, "commit", "-m", "theirs")
            val theirsSha = shellGit(workDir, "rev-parse", "HEAD")
            block(workDir, baseSha, oursSha, theirsSha)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun shellGit(dir: File, vararg args: String): String {
        val proc =
            ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0) { "git ${args.toList()} failed: $output" }
        return output
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
