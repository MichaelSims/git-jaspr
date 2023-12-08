package sims.michael.gitjaspr.githubtests

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.*
import sims.michael.gitjaspr.PullRequest
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
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
    fun `can create commits from model`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "commit_one"
                        }
                        commit {
                            title = "commit_two"
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
                        shortMessage = "commit_one",
                        committer = GitHubTestHarness.DEFAULT_COMMITTER,
                    ),
                    commitOne,
                )
                assertEquals(commitThree.copy(shortMessage = "commit_two"), commitThree)
            }
        }
    }

    @Test
    fun `can create commits with a branch from model`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "commit_one"
                            branch {
                                commit {
                                    title = "commit_one_one"
                                }
                                commit {
                                    title = "commit_one_two"
                                    localRefs += "one"
                                }
                            }
                        }
                        commit {
                            title = "commit_two"
                            localRefs += "main"
                        }
                    }
                },
            )
            val jGitClient = JGitClient(localRepo)

            jGitClient.logRange("main~2", "main").let { log ->
                assertEquals(2, log.size)
                val (commitOne, commitThree) = log
                assertEquals(commitOne.copy(shortMessage = "commit_one"), commitOne)
                assertEquals(commitThree.copy(shortMessage = "commit_two"), commitThree)
            }

            jGitClient.logRange("one~2", "one").let { log ->
                assertEquals(2, log.size)
                val (commitOneOne, commitOneTwo) = log
                assertEquals(commitOneOne.copy(shortMessage = "commit_one_one"), commitOneOne)
                assertEquals(commitOneTwo.copy(shortMessage = "commit_one_two"), commitOneTwo)
            }
        }
    }

    @Test
    fun `localRefs and remoteRefs test`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "commit_one"
                            branch {
                                commit {
                                    title = "commit_one_one"
                                    localRefs += "one"
                                }
                                commit {
                                    title = "commit_one_two"
                                    remoteRefs += "one"
                                }
                            }
                        }
                        commit {
                            title = "commit_two"
                            localRefs += "main"
                            remoteRefs += "main"
                        }
                    }
                },
            )

            localGit.logRange("main~2", "main").let { log ->
                assertEquals(2, log.size)
                val (commitOne, commitThree) = log
                assertEquals(commitOne.copy(shortMessage = "commit_one"), commitOne)
                assertEquals(commitThree.copy(shortMessage = "commit_two"), commitThree)
            }

            localGit.logRange("one~2", "one").let { log ->
                assertEquals(2, log.size)
                val (commitOneOne, commitOneTwo) = log
                assertEquals(commitOneOne.copy(shortMessage = "commit_one"), commitOneOne)
                assertEquals(commitOneTwo.copy(shortMessage = "commit_one_one"), commitOneTwo)
            }

            localGit.logRange("$DEFAULT_REMOTE_NAME/one~2", "$DEFAULT_REMOTE_NAME/one").let { log ->
                assertEquals(2, log.size)
                val (commitOneOne, commitOneTwo) = log
                assertEquals(commitOneOne.copy(shortMessage = "commit_one_one"), commitOneOne)
                assertEquals(commitOneTwo.copy(shortMessage = "commit_one_two"), commitOneTwo)
            }
        }
    }

    @Test
    fun `creating commits without named refs fails`(info: TestInfo) {
        withTestSetup {
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
    }

    @Test
    fun `duplicated commit titles are not allowed`(info: TestInfo) {
        withTestSetup {
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
    }

    @Test
    fun `duplicated pr titles are not allowed`(info: TestInfo) {
        withTestSetup {
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
    }

    @Test
    fun `can rewrite history`(info: TestInfo) {
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
                                    title = "feature_one"
                                    localRefs += "feature/1"
                                    remoteRefs += "feature/1"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature_two"
                                    localRefs += "feature/2"
                                    remoteRefs += "feature/2"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature_three"
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
                            title = "one_two"
                            branch {
                                commit {
                                    title = "feature_one_two"
                                    localRefs += "feature/1"
                                    remoteRefs += "feature/1"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature_two_two"
                                    localRefs += "feature/2"
                                    remoteRefs += "feature/2"
                                }
                            }
                            branch {
                                commit {
                                    title = "feature_three_two"
                                    localRefs += "feature/3"
                                    remoteRefs += "feature/3"
                                }
                            }
                        }
                        commit {
                            title = "two_two"
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

    @Test
    fun `rollbackRemoteChanges rolls back main branch even if moved externally`() {
        val harness = withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            gitJaspr.merge(RefSpec("development", "main"))
        }
        val jGitClient = JGitClient(harness.remoteRepo)

        assertEquals(listOf("main"), jGitClient.getBranchNames())
        assertEquals(
            GitHubTestHarness.INITIAL_COMMIT_SHORT_MESSAGE,
            jGitClient.log("main", maxCount = 1).single().shortMessage,
        )
    }

    @Test
    fun `can open PRs from created commits`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            branch {
                                commit { title = "f0_A" }
                                commit {
                                    title = "f0_B"
                                    localRefs += "f0"
                                    remoteRefs += "f0"
                                    branch {
                                        commit { title = "f1_A" }
                                        commit {
                                            title = "f1_B"
                                            localRefs += "f1"
                                            remoteRefs += "f1"
                                        }
                                    }
                                }
                            }
                        }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "main"
                            remoteRefs += "main"
                        }
                    }
                    pullRequest {
                        baseRef = "main"
                        headRef = "f0"
                        title = "thisun"
                        userKey = "derelictMan"
                    }
                    pullRequest {
                        baseRef = "f0"
                        headRef = "f1"
                        title = "anothern"
                        userKey = "derelictMan"
                    }
                    pullRequest {
                        baseRef = "main"
                        headRef = "f1"
                        title = "yet anothern"
                        userKey = "michael"
                    }
                },
            )

            assertEquals(
                listOf("thisun", "anothern", "yet anothern"),
                gitHub.getPullRequests().map(PullRequest::title),
            )
        }
    }
}
