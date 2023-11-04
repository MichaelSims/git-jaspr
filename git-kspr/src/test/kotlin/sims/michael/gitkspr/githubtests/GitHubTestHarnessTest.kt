package sims.michael.gitkspr.githubtests

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import sims.michael.gitkspr.githubtests.generatedtestdsl.branch

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

        val log = JGitClient(repo).log()
        val pairs = listOf("Initial commit", "Commit one", "Commit two").zip(log)
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
                commit {
                    title = "Commit one"
                    branch {
                        name = "One"
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
        val log = jGitClient.log()


        assertEquals(3, log.size)
        val (commitOne, commitTwo, commitThree) = log
        assertEquals(commitOne.copy(shortMessage = "Initial commit"), commitOne)
        assertEquals(commitTwo.copy(shortMessage = "Commit one"), commitTwo)
        assertEquals(commitThree.copy(shortMessage = "Commit two"), commitThree)

        jGitClient.checkout("One")
    }

    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitHubTestHarnessTest::class.java.simpleName).toFile())
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }

}