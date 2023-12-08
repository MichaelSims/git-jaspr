package sims.michael.gitjaspr.githubtests

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import sims.michael.gitjaspr.DEFAULT_TARGET_REF
import sims.michael.gitjaspr.JGitClient
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.ident
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.testing.FunctionalTest
import kotlin.test.assertEquals

// TODO extract a common superclass from this and the other one?
@FunctionalTest
class GitHubTestHarnessFunctionalTest {

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
                            remoteRefs += "main"
                            localRefs += "main"
                        }
                    }
                },
            )

            JGitClient(localRepo).logRange("${DEFAULT_TARGET_REF}~2", DEFAULT_TARGET_REF).let { log ->
                val (commitOne, commitThree) = log
                assertEquals(commitOne.copy(shortMessage = "commit_one"), commitOne)
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
            val jGitClient = JGitClient(localRepo)
            val log = jGitClient.logRange("HEAD~2", "HEAD")

            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "commit_one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "commit_two"), commitThree)

            jGitClient.logRange("one~1", "one")
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "commit_one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "commit_two"), commitOneTwo)
        }
    }

    @Test
    fun `can open PRs from created commits`() {
        withTestSetup(useFakeRemote = false) {
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
        }
    }

    @Test
    fun `can update existing PRs`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote = false) {
            val m = ident {
                email = "michael.h.sims@gmail.com"
                name = "Michael Sims"
            }
            val d = ident {
                email = "derelictman@gmail.com"
                name = "Frank Grimes"
            }
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            committer.from(m)
                        }
                        commit {
                            title = "two"
                            committer.from(m)
                        }
                        commit {
                            title = "three"
                            committer.from(m)
                            localRefs += "one"
                            remoteRefs += "one"
                        }
                    }
                    pullRequest {
                        title = testInfo.displayName
                        baseRef = "main"
                        headRef = "one"
                        userKey = "michael"
                    }
                },
            )
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            committer.from(d)
                        }
                        commit {
                            title = "three"
                            committer.from(d)
                        }
                        commit {
                            title = "four"
                            committer.from(d)
                            localRefs += "one"
                            remoteRefs += "one"
                        }
                    }
                    pullRequest {
                        title = testInfo.displayName
                        baseRef = "main"
                        headRef = "one"
                        userKey = "derelictMan"
                    }
                },
            )
        }
    }
}
