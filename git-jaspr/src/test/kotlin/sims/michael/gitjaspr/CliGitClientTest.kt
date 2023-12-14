package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

class CliGitClientTest {
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

            val git = CliGitClient(localGit.workingDirectory)
            assertEquals(localGit.logAll(), git.logAll())
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

            val git = CliGitClient(localGit.workingDirectory)
            assertEquals(localGit.log(), git.log())
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

            val git = CliGitClient(localGit.workingDirectory)
            assertEquals(localGit.log("some-other-branch", 2), git.log("some-other-branch", 2))
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

            val git = CliGitClient(localGit.workingDirectory)
            assertEquals(localGit.logRange("main", "some-other-branch"), git.logRange("main", "some-other-branch"))
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
}
