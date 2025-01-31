package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.INITIAL_COMMIT_SHORT_MESSAGE
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import sims.michael.gitjaspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files

class CliGitClientTest {

    private val logger = LoggerFactory.getLogger(CliGitClientTest::class.java)

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
                },
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.logAll().sortedBy(Commit::hash), git.logAll().sortedBy(Commit::hash))
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
                },
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
                },
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
                },
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
                },
            )

            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(cliGit.logRange("main", "some-other-branch"), git.logRange("main", "some-other-branch"))
        }
    }

    @Test
    fun `logRange throws when given nonexistant refs`() {
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
                },
            )

            val git = CliGitClient(localGit.workingDirectory)
            assertThrows<IllegalArgumentException> {
                git.logRange("sam", "max")
            }
        }
    }

    @Test
    fun `isWorkingDirectoryClean returns expected value`() {
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
                },
            )

            val readme = localRepo.resolve("README.txt")
            check(readme.exists())
            readme.appendText("This is a change")
            val git = CliGitClient(localGit.workingDirectory)
            assertFalse(git.isWorkingDirectoryClean())
        }
    }

    @Test
    fun `compare getLocalCommitStack`() {
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
                },
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getLocalCommitStack(remoteName, DEFAULT_TARGET_REF, DEFAULT_TARGET_REF),
                git.getLocalCommitStack(remoteName, DEFAULT_TARGET_REF, DEFAULT_TARGET_REF),
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
                },
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val main = cliGit.log(DEFAULT_TARGET_REF, 1).single()
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getParents(main),
                git.getParents(main),
            )
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
                },
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getBranchNames(),
                git.getBranchNames(),
            )
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
                            remoteRefs += "main"
                        }
                    }
                },
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getRemoteBranches(),
                git.getRemoteBranches(),
            )
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
                },
            )
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getRemoteBranchesById(),
                git.getRemoteBranchesById(),
            )
        }
    }

    @Test
    fun `compare getRemoteUriOrNull`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getRemoteUriOrNull(remoteName),
                git.getRemoteUriOrNull(remoteName),
            )
        }
    }

    @Test
    fun `compare getUpstreamBranch`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.getUpstreamBranch(remoteName),
                git.getUpstreamBranch(remoteName),
            )
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
            val remoteBranch = checkNotNull(git.getUpstreamBranch(remoteName)) {
                "No upstream branch found for remote $remoteName"
            }
            return remoteBranch.name
        }
        assertEquals(setAndGetUpstream(::CliGitClient), setAndGetUpstream(::JGitClient))
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
                    },
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
            assertEquals(
                cliGit.getCurrentBranchName(),
                git.getCurrentBranchName(),
            )
        }
    }

    @Test
    fun `compare isHeadDetached`() {
        withTestSetup {
            val cliGit = CliGitClient(localGit.workingDirectory)
            val git = JGitClient(localGit.workingDirectory)
            assertEquals(
                cliGit.isHeadDetached(),
                git.isHeadDetached(),
            )
            cliGit.checkout(cliGit.log().first().hash)
            assertEquals(
                cliGit.isHeadDetached(),
                git.isHeadDetached(),
            )
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
                },
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
        val tempDir = checkNotNull(Files.createTempDirectory(CliGitClientTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
        val remoteDir = tempDir.resolve("remote")
        val remoteGit = CliGitClient(remoteDir).init()
        remoteDir.resolve("README.txt").writeText("This is a README")
        remoteGit.add("README.txt").commit("This is a README", commitIdent = DEFAULT_COMMITTER)
        val localGit = CliGitClient(tempDir.resolve("local")).clone(remoteDir.absolutePath)
        val newFile = remoteGit.workingDirectory.resolve("NEW.txt")
        newFile.appendText("This is a new file")
        remoteGit.add("NEW.txt").commit("Add new file", commitIdent = DEFAULT_COMMITTER)
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
                        commit {
                            title = "one"
                        }
                        commit {
                            title = "two"
                        }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
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
                        commit {
                            title = "one"
                        }
                        commit {
                            title = "two"
                        }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
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
                        commit {
                            title = "one"
                        }
                        commit {
                            title = "two"
                        }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
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

                """.trimIndent(),
                mapOf("Co-authored-by" to "Michael Sims"),
                DEFAULT_COMMITTER,
            )
            assertTrue(git.isWorkingDirectoryClean())
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
                },
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
                        commit {
                            title = "one"
                        }
                        commit {
                            title = "two"
                        }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
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
                },
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
                    },
                )
            }
            val git = CliGitClient(localGit.workingDirectory)
            val reflog = git.reflog()
            // Build a list of short messages in the order that HEAD moved, then reverse it to match the reflog order
            // This bakes some assumptions about how our test harness works, so if that changes this will break
            val expectedShortMessages = buildList {
                add(INITIAL_COMMIT_SHORT_MESSAGE) // The result of "git init"
                for (title in titles) {
                    add(INITIAL_COMMIT_SHORT_MESSAGE)
                    add(title)
                }
            }.reversed()
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
}
