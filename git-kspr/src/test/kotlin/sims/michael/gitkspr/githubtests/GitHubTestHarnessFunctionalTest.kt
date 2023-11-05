package sims.michael.gitkspr.githubtests

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.DEFAULT_TARGET_REF
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.githubtests.generatedtestdsl.branch
import sims.michael.gitkspr.testing.FunctionalTest
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.nio.file.Files
import kotlin.test.assertEquals

private const val REPO_HOST = "github.com"
private const val REPO_OWNER = "MichaelSims"
private const val REPO_NAME = "git-spr-demo"
private const val REPO_URI = "git@${REPO_HOST}:${REPO_OWNER}/${REPO_NAME}.git"

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
        val tempDir = createTempDir()
        val harness = GitHubTestHarness(tempDir, REPO_URI)
        harness.createCommits(
            branch {
                commit {
                    title = "Commit one"
                }
                commit {
                    title = "Commit two"
                    remoteRefs += "main"
                    localRefs += "main"
                }
            },
        )

        JGitClient(harness.localRepo).logRange("${DEFAULT_TARGET_REF}~2", DEFAULT_TARGET_REF).let { log ->
            val (commitOne, commitThree) = log
            assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
            assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)
        }
    }

    @Test
    fun `can create commits with a branch from model`() {
        val tempDir = createTempDir()
        val harness = GitHubTestHarness(tempDir, REPO_URI)
        harness.createCommits(
            branch {
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
            },
        )
        val jGitClient = JGitClient(harness.localRepo)
        val log = jGitClient.logRange("HEAD~2", "HEAD")

        assertEquals(2, log.size)
        val (commitOne, commitThree) = log
        assertEquals(commitOne.copy(shortMessage = "Commit one"), commitOne)
        assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)

        jGitClient.checkout("one")
        jGitClient.logRange("HEAD~1", "HEAD")
        val (commitOneOne, commitOneTwo) = log
        assertEquals(commitOneOne.copy(shortMessage = "Commit one"), commitOneOne)
        assertEquals(commitOneTwo.copy(shortMessage = "Commit two"), commitOneTwo)
    }

    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessFunctionalTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
}
