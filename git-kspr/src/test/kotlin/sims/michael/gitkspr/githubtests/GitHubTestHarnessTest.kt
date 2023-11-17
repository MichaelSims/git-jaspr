package sims.michael.gitkspr.githubtests

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.DEFAULT_REMOTE_NAME
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import kotlin.test.assertEquals

class GitHubTestHarnessTest {

    private val logger = LoggerFactory.getLogger(GitHubTestHarnessTest::class.java)

    @Test
    fun `can create repo with initial commit`() {
        withTestSetup {
            val log = JGitClient(localRepo).log()
            assertEquals(1, log.size)
            val commit = log.single()
            assertEquals(commit.copy(shortMessage = "Initial commit"), commit)
        }
    }

    @Test
    fun `can create commits from model`() = withTestSetup {
        createCommitsFrom(
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
    fun `can create commits with a branch from model`() = withTestSetup {
        createCommitsFrom(
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
    }

    @Test
    fun `localRefs and remoteRefs test`() = withTestSetup {
        createCommitsFrom(
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

        localGit.logRange("main~2", "main").let { log ->
            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
        }

        localGit.logRange("one~2", "one").let { log ->
            assertEquals(2, log.size)
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit one.one"), commitOneTwo)
        }

        localGit.logRange("$DEFAULT_REMOTE_NAME/one~2", "$DEFAULT_REMOTE_NAME/one").let { log ->
            assertEquals(2, log.size)
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one.one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit one.two"), commitOneTwo)
        }
    }

    @Test
    fun `creating commits without named refs fails`(info: TestInfo) = withTestSetup {
        val exception = assertThrows<IllegalArgumentException> {
            createCommitsFrom(
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
    fun `duplicated commit titles are not allowed`(info: TestInfo) = withTestSetup {
        val exception = assertThrows<IllegalArgumentException> {
            createCommitsFrom(
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
    fun `duplicated pr titles are not allowed`(info: TestInfo) = withTestSetup {
        val exception = assertThrows<IllegalArgumentException> {
            createCommitsFrom(
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
                            title = "Commit two"
                            localRefs += "main"
                        }
                    }
                    pullRequest {
                        title = "One"
                        baseRef = "main"
                        headRef = "feature-one"
                    }
                    pullRequest {
                        title = "One"
                        baseRef = "main~1"
                        headRef = "feature-one"
                    }
                },
            )
        }
        logger.info("{}: {}", info.displayName, exception.message)
    }

    @Test
    fun `can rewrite history`(info: TestInfo) = withTestSetup {
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
                        localRefs += "one"
                    }
                }
            },
        )
        createCommitsFrom(
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
        localGit.logRange("one~3", "one").let { log ->
            assertEquals(3, log.size)
            val (commitOne, commitTwo, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "one"), commitOne)
            assertEquals(commitTwo.copy(shortMessage = "three"), commitTwo)
            assertEquals(commitThree.copy(shortMessage = "four"), commitThree)
        }
    }

    @Test
    fun `rollbackRemoteChanges works as expected`() {
        val harness = withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            branch {
                                commit {
                                    title = "feature one"
                                    localRefs += "feature/1"
                                    remoteRefs += "feature/1"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature two"
                                    localRefs += "feature/2"
                                    remoteRefs += "feature/2"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature three"
                                    localRefs += "feature/3"
                                    remoteRefs += "feature/3"
                                }
                            }
                        }
                        commit {
                            title = "two"
                            localRefs += "main"
                            remoteRefs += "main"
                        }
                    }
                },
            )
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one.two"
                            branch {
                                commit {
                                    title = "feature one.two"
                                    localRefs += "feature/1"
                                    remoteRefs += "feature/1"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature two.two"
                                    localRefs += "feature/2"
                                    remoteRefs += "feature/2"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature three.two"
                                    localRefs += "feature/3"
                                    remoteRefs += "feature/3"
                                }
                            }
                        }
                        commit {
                            title = "two.two"
                            localRefs += "main"
                            remoteRefs += "main"
                        }
                    }
                },
            )
        }
        val jGitClient = JGitClient(harness.remoteRepo)

        assertEquals(listOf("main"), jGitClient.getBranchNames())
        assertEquals(
            GitHubTestHarness.INITIAL_COMMIT_SHORT_MESSAGE,
            jGitClient.log("main", maxCount = 1).single().shortMessage,
        )
    }
}
