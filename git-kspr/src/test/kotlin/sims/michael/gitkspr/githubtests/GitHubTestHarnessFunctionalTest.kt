package sims.michael.gitkspr.githubtests

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.*
import sims.michael.gitkspr.githubtests.generatedtestdsl.ident
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitkspr.testing.FunctionalTest
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals

// TODO extract a common superclass from this and the other one?
@FunctionalTest
class GitHubTestHarnessFunctionalTest {

    private val logger = LoggerFactory.getLogger(GitHubTestHarnessFunctionalTest::class.java)

    private val derelictMan: GitHubClient
    private val michael: GitHubClient

    init {
        val properties = Properties().apply {
            val configFile = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME)
            configFile.inputStream().use { inputStream -> load(inputStream) }
        }

        fun gitHubToken(name: String) = properties.getProperty("github-token.$name")
        val gitHubInfo = GitHubInfo(REPO_HOST, REPO_OWNER, REPO_NAME)
        derelictMan = GitHubClientWiring(gitHubToken("derelictMan"), gitHubInfo, DEFAULT_REMOTE_BRANCH_PREFIX).gitHubClient
        michael = GitHubClientWiring(gitHubToken("michael"), gitHubInfo, DEFAULT_REMOTE_BRANCH_PREFIX).gitHubClient
    }

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
        val harness = GitHubTestHarness(localRepo, remoteRepo, emptyMap(), REPO_URI)
        try {
            harness.createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Commit one"
                        }
                        commit {
                            title = "Commit two"
                            remoteRefs += "main"
                            localRefs += "main"
                        }
                    }
                },
            )

            JGitClient(localRepo).logRange("${DEFAULT_TARGET_REF}~2", DEFAULT_TARGET_REF).let { log ->
                val (commitOne, commitThree) = log
                assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
                assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
            }
        } finally {
            harness.rollbackRemoteChanges()
        }
    }

    @Test
    fun `can create commits with a branch from model`() = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo, emptyMap(), REPO_URI)
        try {
            harness.createCommitsFrom(
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
            val log = jGitClient.logRange("HEAD~2", "HEAD")

            assertEquals(2, log.size)
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)

            jGitClient.logRange("one~1", "one")
            val (commitOneOne, commitOneTwo) = log
            assertEquals(commitOneOne.copy(shortMessage = "Commit one"), commitOneOne)
            assertEquals(commitOneTwo.copy(shortMessage = "Commit two"), commitOneTwo)
        } finally {
            harness.rollbackRemoteChanges()
        }
    }

    @Test
    fun `can open PRs from created commits`() = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo, useFakeRemote = true)
        try {
            harness.createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            branch {
                                commit { title = "f0 A" }
                                commit {
                                    title = "f0 B"
                                    localRefs += "f0"
                                    remoteRefs += "f0"
                                    branch {
                                        commit { title = "f1 A" }
                                        commit {
                                            title = "f1 B"
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
        } finally {
            harness.rollbackRemoteChanges()
        }
    }

    @Test
    fun `can update existing PRs`(testInfo: TestInfo) = runBlocking {
        val (localRepo, remoteRepo) = createTempDir().createRepoDirs()
        val harness = GitHubTestHarness(localRepo, remoteRepo, useFakeRemote = true)
        try {
            val m = ident {
                email = "michael.h.sims@gmail.com"
                name = "Michael Sims"
            }
            val d = ident {
                email = "derelictman@gmail.com"
                name = "Frank Grimes"
            }
            harness.createCommitsFrom(
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
            harness.createCommitsFrom(
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
        } finally {
            harness.rollbackRemoteChanges()
        }
    }
    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessFunctionalTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
}

private const val REPO_HOST = "github.com"
private const val REPO_OWNER = "MichaelSims"
private const val REPO_NAME = "git-spr-demo"
private const val REPO_URI = "git@${REPO_HOST}:${REPO_OWNER}/${REPO_NAME}.git"
