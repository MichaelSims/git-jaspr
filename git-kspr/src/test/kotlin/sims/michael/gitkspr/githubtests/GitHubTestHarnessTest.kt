package sims.michael.gitkspr.githubtests

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.githubtests.generatedtestdsl.branch
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class GitHubTestHarnessTest {

    private val logger = LoggerFactory.getLogger(GitHubTestHarnessTest::class.java)

    @Test
    fun `can create repo with initial commit`() {
        val repo = createTempDir().resolve("repo").also(File::mkdir)
        GitHubTestHarness(repo).createRepoWithInitialCommit()
        val log = JGitClient(repo).log()
        assertEquals(1, log.size)
        val commit = log.single()
        assertEquals(commit.copy(shortMessage = "Initial commit"), commit)
    }

    @Test
    fun `can create commits from model`() {
        val repo = createTempDir().resolve("repo").also(File::mkdir)
        val harness = GitHubTestHarness(repo).also(GitHubTestHarness::createRepoWithInitialCommit)
        harness.createCommits(
            branch {
                commit {
                    title = "Commit one"
                }
                commit {
                    title = "Commit two"
                }
            },
        )

        val log = JGitClient(repo).logRange("HEAD~2", "HEAD")
        val pairs = listOf("Commit one", "Commit two").zip(log)
        assertEquals(pairs.size, log.size)
        for ((expectedShortMessage, actual) in pairs) {
            assertEquals(expectedShortMessage, actual.shortMessage)
        }
    }

    @Test
    fun `can create commits with a branch from model`() {
        val repo = createTempDir().resolve("repo").also(File::mkdir)
        val harness = GitHubTestHarness(repo).also(GitHubTestHarness::createRepoWithInitialCommit)
        harness.createCommits(
            branch {
                name = "main"
                commit {
                    title = "Commit one"
                    branch {
                        name = "one"
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
            },
        )
        val jGitClient = JGitClient(repo)
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
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
}
