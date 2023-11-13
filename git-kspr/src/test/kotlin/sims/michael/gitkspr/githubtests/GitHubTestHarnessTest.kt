package sims.michael.gitkspr.githubtests

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.DEFAULT_REMOTE_NAME
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class GitHubTestHarnessTest {

    private val logger = LoggerFactory.getLogger(GitHubTestHarnessTest::class.java)

    @Test
    fun `can create repo with initial commit`() {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        GitHubTestHarness(localRepo, remoteRepo)
        val log = JGitClient(localRepo).log()
        assertEquals(1, log.size)
        val commit = log.single()
        assertEquals(commit.copy(shortMessage = "Initial commit"), commit)
    }

    @Test
    fun `can create commits from model`() = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo)
        harness.createCommits(
            testCase {
                repository {
                    commit {
                        title = "Commit one"
                    }
                    commit {
                        title = "Commit two"
                        localRefs += "main"
                    }
                }
            },
        )

        JGitClient(localRepo).logRange("main~2", "main").let { log ->
            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(
                commitOne.copy(
                    shortMessage = "Commit one",
                    committer = GitHubTestHarness.DEFAULT_COMMITTER,
                ),
                commitOne,
            )
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
        }
    }

    @Test
    fun `can create commits with a branch from model`() = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo, emptyMap())
        harness.createCommits(
            testCase {
                repository {
                    commit {
                        title = "Commit one"
                        branch {
                            commit {
                                title = "Commit one.one"
                            }
                            commit {
                                title = "Commit one.two"
                                localRefs += "one"
                            }
                        }
                    }
                    commit {
                        title = "Commit two"
                        localRefs += "main"
                    }
                }
            },
        )
        val jGitClient = JGitClient(localRepo)

        jGitClient.logRange("main~2", "main").let { log ->
            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
        }

        jGitClient.logRange("one~2", "one").let { log ->
            assertEquals(2, log.size)
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one.one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit one.two"), commitOneTwo)
        }

        harness.rollbackRemoteChanges()
    }

    @Test
    fun `localRefs and remoteRefs test`() = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo)
        harness.createCommits(
            testCase {
                repository {
                    commit {
                        title = "Commit one"
                        branch {
                            commit {
                                title = "Commit one.one"
                                localRefs += "one"
                            }
                            commit {
                                title = "Commit one.two"
                                remoteRefs += "one"
                            }
                        }
                    }
                    commit {
                        title = "Commit two"
                        localRefs += "main"
                        remoteRefs += "main"
                    }
                }
            },
        )
        val jGitClient = JGitClient(localRepo)

        jGitClient.logRange("main~2", "main").let { log ->
            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
        }

        jGitClient.logRange("one~2", "one").let { log ->
            assertEquals(2, log.size)
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit one.one"), commitOneTwo)
        }

        jGitClient.logRange("$DEFAULT_REMOTE_NAME/one~2", "$DEFAULT_REMOTE_NAME/one").let { log ->
            assertEquals(2, log.size)
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one.one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit one.two"), commitOneTwo)
        }
    }

    @Test
    fun `creating commits without named refs fails`(info: TestInfo) = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo)
        val exception = assertThrows<IllegalArgumentException> {
            harness.createCommits(
                testCase {
                    repository {
                        commit {
                            title = "Commit one"
                            branch {
                                commit {
                                    title = "Commit one.one"
                                }
                                commit {
                                    title = "Commit one.two"
                                }
                            }
                        }
                        commit {
                            title = "Commit two"
                        }
                    }
                },
            )
        }
        logger.info("{}: {}", info.displayName, exception.message)
    }

    @Test
    fun `duplicated titles is not allowed`(info: TestInfo) = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo)
        val exception = assertThrows<IllegalArgumentException> {
            harness.createCommits(
                testCase {
                    repository {
                        commit {
                            title = "Commit one"
                            branch {
                                commit {
                                    title = "Commit one.one"
                                }
                                commit {
                                    title = "Commit one.two"
                                    localRefs += "feature-one"
                                }
                            }
                        }
                        commit {
                            title = "Commit one"
                            localRefs += "main"
                        }
                    }
                },
            )
        }
        logger.info("{}: {}", info.displayName, exception.message)
    }

    @Test
    fun `can rewrite history`(info: TestInfo) = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo)
        harness.createCommits(
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
                        localRefs += "one"
                    }
                }
            },
        )
        harness.createCommits(
            testCase {
                repository {
                    commit {
                        title = "one"
                    }
                    commit {
                        title = "three"
                    }
                    commit {
                        title = "four"
                        localRefs += "one"
                    }
                }
            },
        )
        val jGitClient = JGitClient(localRepo)

        jGitClient.logRange("one~3", "one").let { log ->
            assertEquals(3, log.size)
            val (commitOne, commitTwo, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "one"), commitOne)
            assertEquals(commitTwo.copy(shortMessage = "three"), commitTwo)
            assertEquals(commitThree.copy(shortMessage = "four"), commitThree)
        }
    }

    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
}

fun File.createRepoDirs() =
    resolve(GitHubTestHarness.LOCAL_REPO_SUBDIR) to resolve(GitHubTestHarness.REMOTE_REPO_SUBDIR)
