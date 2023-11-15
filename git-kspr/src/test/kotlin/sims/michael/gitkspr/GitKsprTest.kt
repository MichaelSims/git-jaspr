package sims.michael.gitkspr

import com.nhaarman.mockitokotlin2.*
import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.JGitClient.Companion.HEAD
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.TestCaseData
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
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
    fun `push fails unless workdir is clean`() = withTestSetup {
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "Some commit"
                        localRefs += "development"
                    }
                }
                localIsDirty = true
            },
        )
        val exception = assertThrows<IllegalStateException> {
            gitKspr.push()
        }
        logger.info("Exception message is {}", exception.message)
    }

    @Test
    fun `push fetches from remote`() = withTestSetup {
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "one"
                    }
                    commit {
                        title = "two"
                        localRefs += "main"
                    }
                    commit {
                        title = "three"
                        remoteRefs += "main"
                    }
                }
            },
        )

        gitKspr.push()

        assertEquals(listOf("three", "two", "one"), localGit.log("origin/main", maxCount = 3).map(Commit::shortMessage))
    }

    @TestFactory
    fun `push adds commit IDs`(): List<DynamicTest> {
        data class Test(val name: String, val expected: List<String>, val testCaseData: TestCaseData)
        return listOf(
            Test(
                "all commits missing IDs",
                listOf("0", "1", "2"),
                testCase {
                    repository {
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only recent commits missing IDs",
                listOf("A", "B", "0", "1", "2"),
                testCase {
                    repository {
                        commit {
                            title = "A"
                            id = "A"
                        }
                        commit {
                            title = "B"
                            id = "B"
                        }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only commits in the middle missing IDs",
                listOf("A", "B", "0", "1", "2", "C", "D"),
                testCase {
                    repository {
                        commit {
                            title = "A"
                            id = "A"
                        }
                        commit {
                            title = "B"
                            id = "B"
                        }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit { title = "2" }
                        commit {
                            title = "C"
                            id = "C"
                        }
                        commit {
                            title = "D"
                            id = "D"
                            localRefs += "main"
                        }
                    }
                },
            ),
        ).map { (name, expected, collectCommits) ->
            dynamicTest(name) {
                withTestSetup {
                    createCommitsFrom(collectCommits)
                    gitKspr.push()
                    assertEquals(
                        expected,
                        localGit.logRange("$HEAD~${collectCommits.repository.commits.size}", HEAD).map(Commit::id),
                    )
                }
            }
        }
    }

    @Test
    fun `push pushes to expected remote branch names`() = withTestSetup {
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "1"
                        id = "1"
                    }
                    commit {
                        title = "2"
                        id = "2"
                    }
                    commit {
                        title = "3"
                        id = "3"
                        localRefs += "main"
                    }
                }
            },
        )
        gitKspr.push()

        val prefix = "refs/heads/${DEFAULT_REMOTE_BRANCH_PREFIX}"
        assertEquals(
            (1..3).associate { "$prefix$it" to it.toString() },
            remoteGit.commitIdsByBranch(),
        )
    }

    @Test
    fun `push pushes revision history branches on update`(testInfo: TestInfo): Unit = withTestSetup {
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "a"
                        id = "a"
                    }
                    commit {
                        title = "b"
                        id = "b"
                    }
                    commit {
                        title = "c"
                        id = "c"
                        localRefs += "main"
                    }
                }
            },
        )
        gitKspr.push()
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "z"
                        id = "z"
                    }
                    commit {
                        title = "a"
                        id = "a"
                    }
                    commit {
                        title = "b"
                        id = "b"
                    }
                    commit {
                        title = "c"
                        id = "c"
                        localRefs += "main"
                    }
                }
            },
        )
        gitKspr.push()
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "y"
                        id = "y"
                    }
                    commit {
                        title = "a"
                        id = "a"
                    }
                    commit {
                        title = "b"
                        id = "b"
                    }
                    commit {
                        title = "c"
                        id = "c"
                        localRefs += "main"
                    }
                }
            },
        )
        gitKspr.push()
        gitLogLocalAndRemote()

        assertEquals(
            listOf("a", "a_01", "a_02", "b", "b_01", "b_02", "c", "c_01", "c_02", "y", "z")
                .map { name -> "${DEFAULT_REMOTE_BRANCH_PREFIX}$name" },
            localGit
                .getRemoteBranches()
                .map(RemoteBranch::name)
                .filter { name -> name.startsWith(DEFAULT_REMOTE_BRANCH_PREFIX) }
                .sorted(),
        )
    }

    @Test
    fun `push updates base refs for any reordered PRs`() {
        val localStack = (1..4).map(::commit)
        val remoteStack = listOf(1, 2, 4, 3).map(::commit)

        val config = config()
        val f = config.prFactory()

        val gitHubClient = mock<GitHubClient> {
            onBlocking { getPullRequests(any()) } doReturn f.toPrs(remoteStack)
        }

        withTestSetup(mockGitHubClient = gitHubClient) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            id = "1"
                        }
                        commit {
                            title = "2"
                            id = "2"
                        }
                        commit {
                            title = "4"
                            id = "4"
                        }
                        commit {
                            title = "3"
                            id = "3"
                            localRefs += "development"
                            remoteRefs += "development"
                        }
                    }
                },
            )

//            gitKspr.push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            id = "1"
                        }
                        commit {
                            title = "2"
                            id = "2"
                        }
                        commit {
                            title = "3"
                            id = "3"
                        }
                        commit {
                            title = "4"
                            id = "4"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            gitLogLocalAndRemote()

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
    }

    @Test
    fun `push fails when multiple PRs for a given commit ID exist`() {
        // TODO move this into the harness somehow?
        val config = config()
        val f = config.prFactory()
        val prs = listOf(f.pullRequest(1, 10), f.pullRequest(1, 20))

        val ghc = mock<GitHubClient>() {
            onBlocking { getPullRequests(any<List<Commit>>()) } doReturn prs
        }
        withTestSetup(mockGitHubClient = ghc) {
            val exception = assertThrows<IllegalStateException> {
                gitKspr.push()
            }
            logger.info("Exception message: {}", exception.message)
        }
    }

    @Test
    fun `getRemoteCommitStatuses produces expected result`() = withTestSetup {
        createCommitsFrom(
            testCase {
                repository {
                    commit {
                        title = "1"
                        id = "1"
                        localRefs += "development"
                    }
                }
            },
        )
        gitKspr.push()
        val remoteCommitStatuses = gitKspr.getRemoteCommitStatuses()
        assertEquals(localGit.log("HEAD", maxCount = 1).single(), remoteCommitStatuses.single().remoteCommit)
    }

    private fun createDefaultGitClient(init: KStubbing<JGitClient>.(JGitClient) -> Unit = {}) = mock<JGitClient> {
        on { isWorkingDirectoryClean() } doReturn true
    }.apply { KStubbing(this).init(this) }

    private fun createDefaultGitHubClient() = mock<GitHubClient> {
        onBlocking {
            getPullRequestsById(any())
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
            authorDate = ZonedDateTime.of(2023, 10, 29, 7, 56, 0, 0, ZoneId.systemDefault()),
            commitDate = ZonedDateTime.of(2023, 10, 29, 7, 56, 0, 0, ZoneId.systemDefault()),
        )

    private fun Config.prFactory() = PullRequestFactory(remoteBranchPrefix)

    private class PullRequestFactory(val remoteBranchPrefix: String) {
        fun pullRequest(label: Int, simpleBaseRef: Int? = null): PullRequest {
            val lStr = label.toString()
            val baseRef = simpleBaseRef?.let { "$remoteBranchPrefix$it" } ?: DEFAULT_TARGET_REF
            return PullRequest(
                lStr,
                lStr,
                label,
                "$remoteBranchPrefix$lStr",
                baseRef,
                lStr,
                "$lStr\n" +
                    "\n" +
                    "commit-id: $lStr\n",
            )
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
