package sims.michael.gitkspr

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.sources.PropertiesValueSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitkspr.testing.FunctionalTest
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Functional test which reads the actual git configuration and interacts with GitHub.
 */
@FunctionalTest
class GitKsprFunctionalTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @Test
    fun `push new commits`(testInfo: TestInfo) = withTestSetup(remoteUri = REPO_URI) {
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
                        localRefs += "feature/1"
                    }
                }
            },
        )

        val wiring = createAppWiring(localRepo)
        val gitKspr = wiring.gitKspr
        val gitHub = wiring.gitHubClient

        System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath) // TODO why?
        gitKspr.push()

        val testCommits = localGit.log(JGitClient.HEAD, 3)
        val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
        val remotePrIds = gitHub.getPullRequests(testCommits).mapNotNull(PullRequest::commitId).toSet()
        assertEquals(testCommitIds, remotePrIds)
    }

    @Test
    fun `amend HEAD commit and re-push`(testInfo: TestInfo) = runBlocking {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val wiring = createAppWiring(gitDir)
        val git = wiring.gitClient.clone(REPO_URI)
        val gitKspr = wiring.gitKspr
        val gitHub = wiring.gitHubClient

        fun addCommit() {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-${generateUuid()}.txt"
            gitDir.resolve(testFileName).writeText("This is a test file.\n")
            git.add(testFileName).commit(testFileName)
        }

        addCommit()
        addCommit()
        addCommit()

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        gitKspr.push()

        val first = gitDir.walkTopDown().maxDepth(1).filter { it.name.endsWith(".txt") }.first()
        first.appendText("An amendment.\n")
        val headCommit = git.log(JGitClient.HEAD, 1).single()
        val commitSubject = "I amended this"
        git.add(first.relativeTo(gitDir).name).commitAmend("$commitSubject\n\n${COMMIT_ID_LABEL}: ${headCommit.id}")

        gitKspr.push()

        val testCommits = git.log(JGitClient.HEAD, 3)
        val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
        val remotePrs = gitHub.getPullRequests(testCommits)
        val remotePrIds = remotePrs.mapNotNull(PullRequest::commitId).toSet()
        assertEquals(testCommitIds, remotePrIds)

        val headCommitId = checkNotNull(headCommit.id)
        assertEquals(commitSubject, remotePrs.single { it.commitId == headCommitId }.title)

        git.deleteRemoteRefsFrom(testCommits)
    }

    @Test
    fun `reorder, drop, add, and re-push`(testInfo: TestInfo) = runBlocking {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val wiring = createAppWiring(gitDir)
        val git = wiring.gitClient.clone(REPO_URI)
        val gitKspr = wiring.gitKspr
        val gitHub = wiring.gitHubClient

        fun addCommit(commitLabel: String): Commit {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-$commitLabel.txt"
            gitDir.resolve(testFileName).writeText("$commitLabel\n")
            val commitId = "${commitLabel}_${generateUuid()}"
            return git.add(testFileName).commit("$commitId\n\n${COMMIT_ID_LABEL}: $commitId\n")
        }

        val a = addCommit("A")
        val b = addCommit("B")
        val c = addCommit("C")
        val d = addCommit("D")
        val e = addCommit("E")

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        gitKspr.push()

        git.reset("${a.hash}^")
        git.cherryPick(e)
        git.cherryPick(c)
        val one = addCommit("1")
        git.cherryPick(b)
        git.cherryPick(a)
        val two = addCommit("2")

        gitKspr.push()

        val remotePrs = gitHub.getPullRequests(listOf(e, c, one, b, a, two))

        val prs = remotePrs.map { pullRequest -> pullRequest.baseRefName to pullRequest.headRefName }.toSet()
        val remoteBranchPrefix = wiring.config.remoteBranchPrefix
        val commits = git
            .log(JGitClient.HEAD, 6)
            .reversed()
            .windowedPairs()
            .map { (prevCommit, currentCommit) ->
                val baseRefName = prevCommit?.let { "$remoteBranchPrefix${it.id}" } ?: DEFAULT_TARGET_REF
                val headRefName = "$remoteBranchPrefix${currentCommit.id}"
                baseRefName to headRefName
            }
            .toSet()
        assertEquals(commits, prs)

        git.deleteRemoteRefsFrom(listOf(a, b, c, d, e, one, two))
    }

    @Test
    fun `DELETE ALL KSPR BRANCHES (DANGEROUS)`() {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val wiring = createAppWiring(gitDir)
        val git = wiring.gitClient.clone(REPO_URI)
        val regex = ".*(${wiring.config.remoteBranchPrefix}.*?)$".toRegex()

        val refSpecs = git
            .getRemoteBranches()
            .map(RemoteBranch::name)
            .mapNotNull(regex::matchEntire)
            .map { it.groupValues[1] }
            .map { remoteBranch -> RefSpec("+", remoteBranch) }
        git.push(refSpecs)
    }

    private fun JGitClient.deleteRemoteRefsFrom(commits: List<Commit>) {
        try {
            push(commits.map { commit -> RefSpec("+", "$remoteBranchPrefix${commit.id}") })
        } catch (e: Exception) {
            logger.error("Caught exception during branch cleanup", e)
        }
    }

    private fun createTempDir() = checkNotNull(Files.createTempDirectory(GitKsprTest::class.java.simpleName).toFile())

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
