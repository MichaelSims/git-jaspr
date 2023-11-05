package sims.michael.gitkspr

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranch
import sims.michael.gitkspr.JGitClient.Companion.HEAD
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class GitKsprTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @BeforeEach
    fun setUp() = setGitCommitterInfo("Frank Grimes", "grimey@example.com")

    @Test
    fun `windowedPairs produces expected result`() {
        val input = listOf("one", "two", "three")
        val expected = listOf(null to "one", "one" to "two", "two" to "three")
        val actual = input.windowedPairs()
        assertEquals(expected, actual)
    }

    @Test
    fun `push fails unless workdir is clean`() {
        val tempDir = createTempDir()
        val remoteRepoDir = tempDir.resolve("test-remote").apply(File::initRepoWithInitialCommit)

        val localRepoDir = tempDir.resolve("test-local")
        val local =
            JGitClient(localRepoDir).clone(remoteRepoDir.toURI().toString()).checkout("development", CreateBranch)
        localRepoDir.resolve("README.txt").writeText("Change the file without committing it")

        assertThrows<IllegalStateException> {
            runBlocking { GitKspr(createDefaultGitHubClient(), local, config(localRepoDir)).push() }
        }
    }

    @Test
    fun `push fetches from remote`() {
        val tempDir = createTempDir()
        val remoteRepoDir = tempDir.resolve("test-remote")
        val remote = JGitClient(remoteRepoDir).init()
        val readme = "README.txt"
        val remoteReadMe = remoteRepoDir.resolve(readme)
        remoteReadMe.writeText("This is a test repo.\n")
        val messageA = "Initial commit"
        remote.add(readme).commit(messageA)

        val localRepoDir = tempDir.resolve("test-local")
        val local =
            JGitClient(localRepoDir).clone(remoteRepoDir.toURI().toString()).checkout("development", CreateBranch)

        remoteReadMe.appendText("Commit 2\n")
        val messageB = "New remote commit"
        remote.add(readme).commit(messageB)

        runBlocking { GitKspr(createDefaultGitHubClient(), local, config(localRepoDir)).push() }

        assertEquals(listOf(messageB, messageA), local.log("origin/main").map(Commit::shortMessage))
    }

    @TestFactory
    fun `push adds commit IDs`(): List<DynamicTest> {
        data class Test(val name: String, val expected: List<String>, val collectCommits: CommitCollector.() -> Unit)
        return listOf(
            Test("all commits missing IDs", listOf("0", "1", "2")) { (0..2).forEach(::addCommit) },
            Test("only recent commits missing IDs", listOf("A", "B", "0", "1", "2")) {
                addCommit(1, "A")
                addCommit(2, "B")

                val numCommits = 5
                for (num in (3..numCommits)) {
                    addCommit(num, null)
                }
            },
            Test("only commits in the middle missing IDs", listOf("A", "B", "0", "1", "2", "C", "D")) {
                addCommit(1, "A")
                addCommit(2, "B")

                for (num in (3..5)) {
                    addCommit(num, null)
                }

                addCommit(6, "C")
                addCommit(7, "D")
            },
        ).map { (name, expected, collectCommits) ->
            dynamicTest(name) {
                val tempDir = createTempDir()
                val remoteRepoDir = tempDir.resolve("test-remote").apply(File::initRepoWithInitialCommit)
                val localRepoDir = tempDir.resolve("test-local")
                val local = JGitClient(localRepoDir)
                    .clone(remoteRepoDir.toURI().toString())
                    .checkout("development", CreateBranch)
                val collector = CommitCollector(local).apply(collectCommits)
                val ids = uuidIterator()
                runBlocking {
                    GitKspr(createDefaultGitHubClient(), local, config(localRepoDir), ids::next).push()
                }
                assertEquals(expected, local.logRange("$HEAD~${collector.numCommits}", HEAD).map(Commit::id))
            }
        }
    }

    @Test
    fun `push pushes to expected remote branch names`() {
        val tempDir = createTempDir()
        val remoteRepoDir = tempDir.resolve("test-remote")
        val remote = JGitClient(remoteRepoDir).init()
        val readme = "README.txt"
        val remoteReadMe = remoteRepoDir.resolve(readme)
        remoteReadMe.writeText("This is a test repo.\n")
        val messageA = "Initial commit"
        remote.add(readme).commit(messageA)

        val localRepoDir = tempDir.resolve("test-local")
        val local =
            JGitClient(localRepoDir).clone(remoteRepoDir.toURI().toString()).checkout("development", CreateBranch)
        for (num in (1..3)) {
            val filePattern = "$num.txt"
            localRepoDir.resolve(filePattern).writeText("This is file number $num.\n")
            local.add(filePattern).commit("This is file number $num")
        }

        val ids = uuidIterator()
        val config = config(localRepoDir)
        runBlocking { GitKspr(createDefaultGitHubClient(), local, config, ids::next).push() }

        val prefix = "refs/heads/${config.remoteBranchPrefix}"
        assertEquals(
            (0..2).associate { "$prefix$it" to it.toString() },
            remote.commitIdsByBranch(),
        )
    }

    @Test
    fun `push pushes only changed branches`(): Unit = runBlocking {
        val commitOne = Commit("hOne", "One", "", "iOne")
        val commitTwo = Commit("hTwo", "Two", "", "iTwo")
        val commitThree = Commit("hThree", "Three", "", "iThree")

        val config = config()

        fun Commit.toRemoteBranch() = RemoteBranch("${config.remoteBranchPrefix}$id", this)
        val jGitClient = createDefaultGitClient {
            on { getLocalCommitStack(any(), any(), any()) } doReturn listOf(
                commitOne,
                commitTwo,
                commitThree,
            )
            on { getRemoteBranches() } doReturn listOf(commitOne).map(Commit::toRemoteBranch)
        }

        val gitKspr = GitKspr(createDefaultGitHubClient(), jGitClient, config)
        gitKspr.push()
        argumentCaptor<List<RefSpec>> {
            verify(jGitClient, times(1)).push(capture())

            val expected = listOf(commitTwo, commitThree)
                .map { commit -> RefSpec(commit.hash, "${config.remoteBranchPrefix}${commit.id}").forcePush() }
            val actual = firstValue
                // Filter out revision history branches
                .filterNot { (localRef, _) -> localRef.startsWith("$DEFAULT_REMOTE_NAME/") }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `push pushes revision history branches on update`(testInfo: TestInfo): Unit = runBlocking {
        val tempDir = createTempDir()
        val remoteRepoDir = tempDir.resolve("test-remote")
        val remote = JGitClient(remoteRepoDir).init()
        val readme = "README.txt"
        val remoteReadMe = remoteRepoDir.resolve(readme)
        remoteReadMe.writeText("This is a test repo.\n")
        val messageA = "Initial commit"
        remote.add(readme).commit(messageA)

        val localRepoDir = tempDir.resolve("test-local")
        val local =
            JGitClient(localRepoDir).clone(remoteRepoDir.toURI().toString()).checkout("development", CreateBranch)

        fun addCommit(commitLabel: String): Commit {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-$commitLabel.txt"
            localRepoDir.resolve(testFileName).writeText("$commitLabel\n")
            return local.add(testFileName).commit("$commitLabel\n\n${COMMIT_ID_LABEL}: $commitLabel\n")
        }

        val a = addCommit("a")
        val b = addCommit("b")
        val c = addCommit("c")

        val ids = uuidIterator()
        val config = config(localRepoDir)
        val gitKspr = GitKspr(createDefaultGitHubClient(), local, config, ids::next)
        gitKspr.push()

        local.reset("${a.hash}^")
        val z = addCommit("z")
        val a1 = local.cherryPick(a)
        val b1 = local.cherryPick(b)
        val c1 = local.cherryPick(c)

        gitKspr.push()

        local.reset("${z.hash}^")
        addCommit("y")
        local.cherryPick(a1)
        local.cherryPick(b1)
        local.cherryPick(c1)

        gitKspr.push()

        assertEquals(
            listOf("a", "a_01", "a_02", "b", "b_01", "b_02", "c", "c_01", "c_02", "y", "z")
                .map { name -> "${config.remoteBranchPrefix}$name" },
            local
                .getRemoteBranches()
                .map(RemoteBranch::name)
                .filter { name -> name.startsWith(config.remoteBranchPrefix) }
                .sorted(),
        )
    }

    @Test
    fun `push updates base refs for any reordered PRs`(): Unit = runBlocking {
        val localStack = (1..4).map(::commit)
        val remoteStack = listOf(1, 2, 4, 3).map(::commit)

        val config = config()
        val f = config.prFactory()

        val gitClient = createDefaultGitClient {
            on { getLocalCommitStack(any(), any(), any()) } doReturn localStack
        }
        val gitHubClient = mock<GitHubClient> {
            onBlocking { getPullRequests(eq(localStack)) } doReturn f.toPrs(remoteStack)
        }

        val gitKspr = GitKspr(gitHubClient, gitClient, config)
        gitKspr.push()

        argumentCaptor<PullRequest> {
            verify(gitHubClient, atLeastOnce()).updatePullRequest(capture())

            for (value in allValues) {
                logger.debug("updatePullRequest {}", value)
            }
        }

        inOrder(gitHubClient) {
            /**
             * Verify that the moved commits were first rebased to the target branch. For more info on this, see the
             * comment on [GitKspr.updateBaseRefForReorderedPrsIfAny]
             */
            for (pr in listOf(f.pullRequest(4), f.pullRequest(3))) {
                verify(gitHubClient).updatePullRequest(eq(pr))
            }
            verify(gitHubClient).updatePullRequest(f.pullRequest(3, 2))
            verify(gitHubClient).updatePullRequest(f.pullRequest(4, 3))
            verifyNoMoreInteractions()
        }
    }

    @Test
    fun `push fails when multiple PRs for a given commit ID exist`(): Unit = runBlocking {
        val config = config()
        val f = config.prFactory()
        val localStack = listOf(commit(1))
        val prs = listOf(f.pullRequest(1, 10), f.pullRequest(1, 20))

        val gitClient = createDefaultGitClient {
            on { getLocalCommitStack(any(), any(), any()) } doReturn localStack
        }
        val gitHubClient = mock<GitHubClient> {
            onBlocking { getPullRequests(eq(localStack)) } doReturn prs
        }

        val gitKspr = GitKspr(gitHubClient, gitClient, config)
        val exception = assertThrows<IllegalStateException> {
            gitKspr.push()
        }
        logger.info("Exception message: {}", exception.message)
    }

    @Test
    fun `getRemoteCommitStatuses produces expected result`() {
        val config = config()
        val localStack = listOf(commit(1))

        val gitClient = createDefaultGitClient {
            on { getLocalCommitStack(any(), any(), any()) } doReturn localStack
            on { getRemoteBranchesById() } doReturn mapOf(
                "1" to RemoteBranch("${DEFAULT_REMOTE_BRANCH_PREFIX}1", commit(1)),
            )
        }

        val gitKspr = GitKspr(mock<GitHubClient>(), gitClient, config)

        val remoteCommitStatuses = gitKspr.getRemoteCommitStatuses()
        assertEquals(listOf(RemoteCommitStatus(commit(1), commit(1))), remoteCommitStatuses)
        assertEquals(commit(1), remoteCommitStatuses.single().remoteCommit)
    }

    private fun createDefaultGitClient(init: KStubbing<JGitClient>.(JGitClient) -> Unit = {}) = mock<JGitClient> {
        on { isWorkingDirectoryClean() } doReturn true
    }.apply { KStubbing(this).init(this) }

    private fun createDefaultGitHubClient() = mock<GitHubClient> {
        onBlocking {
            getPullRequests(any())
        } doReturn emptyList()
    }

    private class CommitCollector(private val git: JGitClient) {
        var numCommits = 0
        fun addCommit(num: Int, id: String? = null) {
            val filePattern = "$num.txt"
            git.workingDirectory.resolve(filePattern).writeText("This is file number $num.\n")
            val message = "This is file number $num" + if (id != null) "\n\n$COMMIT_ID_LABEL: $id" else ""
            git.add(filePattern).commit(message)
            numCommits++
        }
    }

    private fun config(localRepo: File = File("/dev/null")) =
        Config(localRepo, DEFAULT_REMOTE_NAME, GitHubInfo("host", "owner", "name"), DEFAULT_REMOTE_BRANCH_PREFIX)

    private fun commit(label: Int, id: String? = null) =
        Commit(
            "$label",
            label.toString(),
            "",
            id ?: "$label",
            ZonedDateTime.of(2023, 10, 29, 7, 56, 0, 0, ZoneId.systemDefault()),
            ZonedDateTime.of(2023, 10, 29, 7, 56, 0, 0, ZoneId.systemDefault()),
        )

    private fun Config.prFactory() = PullRequestFactory(remoteBranchPrefix)

    private class PullRequestFactory(val remoteBranchPrefix: String) {
        fun pullRequest(label: Int, simpleBaseRef: Int? = null): PullRequest {
            val lStr = label.toString()
            val baseRef = simpleBaseRef?.let { "$remoteBranchPrefix$it" } ?: DEFAULT_TARGET_REF
            return PullRequest(lStr, lStr, label, "$remoteBranchPrefix$lStr", baseRef, lStr, "")
        }

        fun toPrs(commits: List<Commit>, useDefaultBaseRef: Boolean = false) = commits
            .windowedPairs()
            .map { pair ->
                val (prev, current) = pair
                val id = current.shortMessage
                pullRequest(id.toInt(), prev?.id?.takeUnless { useDefaultBaseRef }?.toInt())
            }
    }

    @Suppress("SameParameterValue")
    private fun setGitCommitterInfo(name: String, email: String) {
        SystemReader
            .setInstance(
                MockSystemReader()
                    .apply {
                        setProperty(GIT_COMMITTER_NAME_KEY, name)
                        setProperty(GIT_COMMITTER_EMAIL_KEY, email)
                    },
            )
    }

    private fun createTempDir() =
        checkNotNull(Files.createTempDirectory(GitKsprTest::class.java.simpleName).toFile()).also {
            logger.info("Temp dir created in {}", it.toStringWithClickableURI())
        }
}

private fun uuidIterator() = (0..Int.MAX_VALUE).asSequence().map(Int::toString).iterator()

private fun File.initRepoWithInitialCommit() {
    val git = JGitClient(this).init()
    val readme = "README.txt"
    resolve(readme).writeText("This is a test repo.\n")
    git.add(readme).commit("Initial commit")
}

private val filenameSafeRegex = "\\W+".toRegex()
fun String.sanitize() = replace(filenameSafeRegex, "_")
