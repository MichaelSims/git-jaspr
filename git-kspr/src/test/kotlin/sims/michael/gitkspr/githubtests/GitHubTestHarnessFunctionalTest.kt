package sims.michael.gitkspr.githubtests

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.sources.PropertiesValueSource
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.*
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitkspr.testing.FunctionalTest
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

// TODO extract a common superclass from this and the other one?
@FunctionalTest
class GitHubTestHarnessFunctionalTest {

    private val logger = LoggerFactory.getLogger(GitHubTestHarnessFunctionalTest::class.java)

    @Test
    fun `can create repo with initial commit`() {
        val tempDir = createTempDir()
        val harness = GitHubTestHarness(tempDir)
        val log = JGitClient(harness.localRepo).log()
        assertEquals(1, log.size)
        val commit = log.single()
        assertEquals(commit.copy(shortMessage = "Initial commit"), commit)
    }

    @Test
    fun `can create commits from model`() {
        val harness = GitHubTestHarness(createTempDir(), REPO_URI)
        try {
            harness.createCommits(
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

            JGitClient(harness.localRepo).logRange("${DEFAULT_TARGET_REF}~2", DEFAULT_TARGET_REF).let { log ->
                val (commitOne, commitThree) = log
                assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
                assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
            }
        } finally {
            harness.rollbackRemoteChanges()
        }
    }

    @Test
    fun `can create commits with a branch from model`() {
        val tempDir = createTempDir()
        val harness = GitHubTestHarness(tempDir, REPO_URI)
        try {
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
            val jGitClient = JGitClient(harness.localRepo)
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
    fun `can open PRs from created commits`() {
        val tempDir = createTempDir()
        val harness = GitHubTestHarness(tempDir, REPO_URI) { repoDir -> createAppWiring(repoDir).gitHubClient }
        try {
            harness.createCommits(
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
                },
            )
            val jGitClient = JGitClient(harness.localRepo)
            val log = jGitClient.logRange("HEAD~2", "HEAD")

        } finally {
            harness.rollbackRemoteChanges()
        }
    }

    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessFunctionalTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }

    // TODO quick and dirty way to get a similar app wiring to the production app. Revisit this
    private fun createAppWiring(dir: File): DefaultAppWiring = DefaultAppWiring(
        githubToken,
        Config(dir, "origin", GitHubInfo(REPO_HOST, REPO_OWNER, REPO_NAME), DEFAULT_REMOTE_BRANCH_PREFIX),
        JGitClient(dir),
    )

    private val githubToken by lazy {
        class ReadToken : NoOpCliktCommand() {
            init {
                context {
                    valueSource = PropertiesValueSource.from(File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME))
                }
            }

            val githubToken by option().required()
        }

        ReadToken().apply { main(arrayOf()) }.githubToken
    }

}

private const val REPO_HOST = "github.com"
private const val REPO_OWNER = "MichaelSims"
private const val REPO_NAME = "git-spr-demo"
private const val REPO_URI = "git@${REPO_HOST}:${REPO_OWNER}/${REPO_NAME}.git"

