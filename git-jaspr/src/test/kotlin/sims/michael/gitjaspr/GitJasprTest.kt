package sims.michael.gitjaspr

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.slf4j.Logger
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.TestCaseData
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import java.util.MissingFormatArgumentException
import kotlin.test.assertEquals

interface GitJasprTest {

    val logger: Logger
    val useFakeRemote: Boolean get() = true

    suspend fun GitHubTestHarness.push() = gitJaspr.push()

    suspend fun GitHubTestHarness.getAndPrintStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
    ) = gitJaspr.getStatusString(refSpec).also(::print)

    suspend fun GitHubTestHarness.merge(refSpec: RefSpec) = gitJaspr.merge(refSpec)

    suspend fun GitHubTestHarness.autoMerge(refSpec: RefSpec, pollingIntervalSeconds: Int = 10) =
        gitJaspr.autoMerge(refSpec)

    suspend fun GitHubTestHarness.getRemoteCommitStatuses(stack: List<Commit>) = gitJaspr.getRemoteCommitStatuses(stack)

    suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long = 30_000,
        pollingDelay: Long = 5_000, // Lowering this value too much will result in exhausting rate limits
    )

    suspend fun <T> assertEventuallyEquals(expected: T, getActual: suspend () -> T)

    @Test
    fun `windowedPairs produces expected result`() {
        val input = listOf("one", "two", "three")
        val expected = listOf(null to "one", "one" to "two", "two" to "three")
        val actual = input.windowedPairs()
        assertEquals(expected, actual)
    }

    @Test
    fun `push fails unless workdir is clean`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "some_commit"
                            localRefs += "development"
                        }
                    }
                    localWillBeDirty = true
                },
            )
            val exception = assertThrows<IllegalStateException> {
                push()
            }
            logger.info("Exception message is {}", exception.message)
        }
    }

    @Test
    fun `getRemoteCommitStatuses produces expected result`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            localRefs += "development"
                        }
                    }
                },
            )
            push()
            localGit.fetch(DEFAULT_REMOTE_NAME)
            val stack = localGit.getLocalCommitStack(DEFAULT_REMOTE_NAME, DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
            val remoteCommitStatuses = getRemoteCommitStatuses(stack)
            assertEquals(localGit.log("HEAD", maxCount = 1).single(), remoteCommitStatuses.single().remoteCommit)
        }
    }

    //region status tests
    @Test
    fun `status empty stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                            remoteRefs += "main"
                        }
                    }
                },
            )

            assertEquals("Stack is empty.\n", getAndPrintStatusString())
        }
    }

    @Test
    fun `status stack not pushed`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[➖➖➖➖➖] one
                    |[➖➖➖➖➖] two
                    |[➖➖➖➖➖] three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status one commit pushed without PR`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅➖➖➖➖] one
                    |[➖➖➖➖➖] two
                    |[➖➖➖➖➖] three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status one PR`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅⌛➖➖] %s : one
                    |[➖➖➖➖➖] two
                    |[➖➖➖➖➖] three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status one PR passing checks`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                            willPassVerification = true
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            waitForChecksToConclude("one")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅➖➖] %s : one
                    |[➖➖➖➖➖] two
                    |[➖➖➖➖➖] three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status one PR approved`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            waitForChecksToConclude("one")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅✅] %s : one
                    |[✅✅✅➖➖] %s : two
                    |[✅✅✅➖➖] %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status stack one commit behind target`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main"
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅➖] %s : one
                    |[✅✅✅✅➖] %s : two
                    |[✅✅✅✅➖] %s : three
                    |
                    |Your stack is out-of-date with the base branch (1 commit behind main).
                    |You'll need to rebase it (`git rebase origin/main`) before your stack will be mergeable.
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status stack two commits behind target`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main_one"
                                }
                                commit {
                                    title = "only_on_main_two"
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅➖] %s : one
                    |[✅✅✅✅➖] %s : two
                    |[✅✅✅✅➖] %s : three
                    |
                    |Your stack is out-of-date with the base branch (2 commits behind main).
                    |You'll need to rebase it (`git rebase origin/main`) before your stack will be mergeable.
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status stack check all mergeable`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅✅] %s : one
                    |[✅✅✅✅✅] %s : two
                    |[✅✅✅✅✅] %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status middle commit approved`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            push()

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEventuallyEquals(
                """
                    |[✅✅✅➖➖] %s : one
                    |[✅✅✅✅➖] %s : two
                    |[✅✅✅➖➖] %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                getActual = { actual },
            )
        }
    }

    @Test
    fun `status middle commit fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = false
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            push()

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅➖➖] %s : one
                    |[✅✅❌➖➖] %s : two
                    |[✅✅✅➖➖] %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status with non-main target branch`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += "development"
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three", "development")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three", "development")
                        baseRef = "development"
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("three")

            val actual = getAndPrintStatusString(RefSpec("development", "development"))
            assertEquals(
                """
                    |[✅✅✅✅✅] %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }
    //endregion

    //region push tests
    @Test
    fun `push fetches from remote`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
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

            push()

            assertEquals(
                listOf("three", "two", "one"),
                localGit.log("origin/main", maxCount = 3).map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `adding commit ID does not indent subject line`() {
        // assert the absence of a bug that used to occur with commits that had message bodies... the subject and footer
        // lines would be indented, which was invalid and would cause the commit(s) to effectively have no ID
        // if this test doesn't throw, then we're good
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Bump EnricoMi/publish-unit-test-result-action from 2.1.0 to 2.11.0"
                            body = """
                                |Bumps [EnricoMi/publish-unit-test-result-action](https://github.com/enricomi/publish-unit-test-result-action) from 2.1.0 to 2.11.0.
                                |- [Release notes](https://github.com/enricomi/publish-unit-test-result-action/releases)
                                |- [Commits](https://github.com/enricomi/publish-unit-test-result-action/compare/713caf1dd6f1c273144546ed2d79ca24a01f4623...ca89ad036b5fcd524c1017287fb01b5139908408)
                                |
                                |---
                                |updated-dependencies:
                                |- dependency-name: EnricoMi/publish-unit-test-result-action
                                |  dependency-type: direct:production
                                |  update-type: version-update:semver-minor
                                |...
                                |
                                |Signed-off-by: dependabot[bot] <support@github.com>
                            """.trimMargin()
                            id = ""
                            localRefs += "main"
                        }
                    }
                },
            )

            push()
        }
    }

    @Test
    fun `commit ID is added with a blank line before it`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Market Explorer: Remove unused code"
                            id = ""
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            assertEquals(
                """
Market Explorer: Remove unused code

commit-id: 0

                """.trimIndent(),
                localGit.log("HEAD", maxCount = 1).single().fullMessage,
            )
        }
    }

    @TestFactory
    fun `push adds commit IDs`(): List<DynamicTest> {
        data class Test(val name: String, val testCaseData: TestCaseData)
        return listOf(
            Test(
                "all commits missing IDs",
                testCase {
                    repository {
                        commit {
                            title = "0"
                            id = ""
                        }
                        commit {
                            title = "1"
                            id = ""
                        }
                        commit {
                            title = "2"
                            id = ""
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only recent commits missing IDs",
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "0"
                            id = ""
                        }
                        commit {
                            title = "1"
                            id = ""
                        }
                        commit {
                            title = "2"
                            id = ""
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only commits in the middle missing IDs",
                testCase {
                    repository {
                        commit {
                            title = "A"
                        }
                        commit {
                            title = "B"
                        }
                        commit {
                            title = "0"
                            id = ""
                        }
                        commit {
                            title = "1"
                            id = ""
                        }
                        commit {
                            title = "2"
                            id = ""
                        }
                        commit {
                            title = "C"
                        }
                        commit {
                            title = "D"
                            localRefs += "main"
                        }
                    }
                },
            ),
        ).map { (name, collectCommits) ->
            DynamicTest.dynamicTest(name) {
                withTestSetup(useFakeRemote) {
                    createCommitsFrom(collectCommits)
                    push()
                    val numCommits = collectCommits.repository.commits.size
                    assertTrue(
                        localGit
                            .logRange(
                                "${GitClient.HEAD}~$numCommits",
                                GitClient.HEAD,
                            )
                            .mapNotNull(Commit::id)
                            .filter(String::isNotBlank)
                            .size == numCommits,
                    )
                }
            }
        }
    }

    @Test
    fun `push pushes to expected remote branch names`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit {
                            title = "3"
                            localRefs += "main"
                        }
                    }
                },
            )
            push()

            assertEquals(
                (1..3).map { buildRemoteRef(it.toString()) },
                localGit.getRemoteBranches().map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @Test
    fun `push pushes revision history branches on update`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "z" }
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "z" }
                        commit { title = "a" }
                        commit { title = "d" }
                        commit {
                            title = "e"
                            localRefs += "main"
                        }
                    }
                },
            )
            push()
            gitLogLocalAndRemote()

            assertEquals(
                listOf("a", "a_01", "b", "b_01", "c", "c_01", "d", "e", "z")
                    .map { name -> buildRemoteRef(name) },
                localGit
                    .getRemoteBranches()
                    .map(RemoteBranch::name)
                    .filter { name -> name.startsWith(RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX) }
                    .sorted(),
            )
        }
    }

    @Test
    fun `push updates base refs for any reordered PRs`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "4" }
                        commit {
                            title = "3"
                            localRefs += "development"
                            remoteRefs += "development"
                        }
                    }
                },
            )

            push()

            assertEquals(
                setOf(
                    "jaspr/main/1 -> main",
                    "jaspr/main/2 -> jaspr/main/1",
                    "jaspr/main/4 -> jaspr/main/2",
                    "jaspr/main/3 -> jaspr/main/4",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "3" }
                        commit {
                            title = "4"
                            localRefs += "development"
                        }
                    }
                },
            )

            push()

            gitLogLocalAndRemote()

            assertEquals(
                setOf(
                    "jaspr/main/1 -> main",
                    "jaspr/main/2 -> jaspr/main/1",
                    "jaspr/main/3 -> jaspr/main/2",
                    "jaspr/main/4 -> jaspr/main/3",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )
        }
    }

    @Test
    fun `push fails when multiple PRs for a given commit ID exist`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "One PR"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "main"
                        title = "Two PR"
                    }
                },
            )
            val exception = assertThrows<GitJaspr.SinglePullRequestPerCommitConstraintViolation> {
                push()
            }
            logger.info("Exception message: {}", exception.message)
        }
    }

    @Test
    fun `reorder, drop, add, and re-push`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit { title = "D" }
                        commit {
                            title = "E"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "E" }
                        commit { title = "C" }
                        commit { title = "one" }
                        commit { title = "B" }
                        commit { title = "A" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            val remotePrs = gitHub.getPullRequestsById(listOf("E", "C", "one", "B", "A", "two"))

            val prs = remotePrs.map { pullRequest -> pullRequest.baseRefName to pullRequest.headRefName }.toSet()
            val commits = localGit
                .log(GitClient.HEAD, 6)
                .reversed()
                .windowedPairs()
                .map { (prevCommit, currentCommit) ->
                    val baseRefName = prevCommit
                        ?.let {
                            buildRemoteRef(checkNotNull(it.id), DEFAULT_TARGET_REF)
                        }
                        ?: DEFAULT_TARGET_REF
                    val headRefName = buildRemoteRef(checkNotNull(currentCommit.id), DEFAULT_TARGET_REF)
                    baseRefName to headRefName
                }
                .toSet()
            assertEquals(commits, prs)
        }
    }

    @Test
    fun `push creates draft PRs based on commit subject`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "DRAFT: this is a test"
                            id = "a"
                        }
                        commit {
                            title = "wip b"
                            id = "b"
                        }
                        commit {
                            title = "c"
                            localRefs += "development"
                        }
                    }
                },
            )
            push()

            assertEquals(
                listOf(true, true, false),
                gitHub.getPullRequests().map(PullRequest::isDraft),
            )
        }
    }

    @Test
    fun `amend HEAD commit and re-push`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitJaspr.push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "four"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitJaspr.push()

            val testCommits = localGit.log(GitClient.HEAD, 3)
            val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
            val remotePrs = gitHub.getPullRequests(testCommits)
            val remotePrIds = remotePrs.mapNotNull(PullRequest::commitId).toSet()
            assertEquals(testCommitIds, remotePrIds)

            val headCommit = localGit.log(GitClient.HEAD, 1).single()
            val headCommitId = checkNotNull(headCommit.id)
            assertEquals("four", remotePrs.single { it.commitId == headCommitId }.title)
        }
    }
    //endregion

    // region pr body tests
    @Test
    fun `pr descriptions basic stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit {
                            title = "3"
                            body = "This is a body"
                            footerLines["footer-line-test"] = "hi" // Will be stripped out in the description
                            localRefs += "main"
                        }
                    }
                },
            )
            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
1

**Stack**:
- %s
- %s
- %s ⬅

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
2

**Stack**:
- %s
- %s ⬅
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
3

This is a body

**Stack**:
- %s ⬅
- %s
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                ),
                actual,
            )
        }
    }

    @Test
    fun `pr descriptions reordered and with history links`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit { title = "D" }
                        commit {
                            title = "E"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "E" }
                        commit { title = "C" }
                        commit { title = "one" }
                        commit { title = "B" }
                        commit { title = "A" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
A

**Stack**:
- %s
- %s ⬅
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
B

**Stack**:
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s ⬅
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
C

**Stack**:
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s
- %s ⬅
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
D

**Stack**:
- %s
- %s ⬅
- %s
- %s
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
E

**Stack**:
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s ⬅
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
one

**Stack**:
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s ⬅
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
two

**Stack**:
- %s ⬅
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/A_01..jaspr/main/A)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/B_01..jaspr/main/B)
- %s
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/C_01..jaspr/main/C)
- %s
  - [01..Current](https://%s/%s/%s/compare/jaspr/main/E_01..jaspr/main/E)

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                ),
                actual,
            )
        }
    }

    @Test
    fun `pr descriptions force pushed twice`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "D"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "E"
                            localRefs += "main"
                        }
                    }
                },
            )

            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
A

**Stack**:
- %s
- %s
- %s ⬅

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
B

**Stack**:
- %s
- %s ⬅
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
C

**Stack**:
- %s ⬅
- %s
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
D

**Stack**:
- %s ⬅
- %s
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                    """
E

**Stack**:
- %s ⬅
- %s
- %s

                    """.trimIndent().toPrBodyString(actualIterator.next()),
                ),
                actual,
            )
        }
    }
    //endregion

    //region merge tests
    @Test
    fun `merge empty stack`() {
        withTestSetup(useFakeRemote) {
            merge(RefSpec("main", "main"))
        }
    }

    @Test
    fun `merge happy path`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")
            merge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `autoMerge happy path`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            autoMerge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `merge fails when behind target branch`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main_one"
                                }
                                commit {
                                    title = "only_on_main_two"
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            merge(RefSpec("development", "main"))
            assertEquals(
                listOf("one", "two", "three"), // Nothing was merged
                localGit
                    .getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `merge pushes latest commit that passes the stack check`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("b")
                        }
                        commit {
                            title = "c"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c")
                        }
                        commit {
                            title = "d"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("d")
                        }
                        commit {
                            title = "e"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("e")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("a")
                        baseRef = "main"
                        title = "a"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("b")
                        baseRef = buildRemoteRef("a")
                        title = "b"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("c")
                        baseRef = buildRemoteRef("b")
                        title = "c"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("d")
                        baseRef = buildRemoteRef("c")
                        title = "d"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("e")
                        baseRef = buildRemoteRef("d")
                        title = "e"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("a", "b", "c", "d", "e")
            merge(RefSpec("development", "main"))
            assertEquals(
                // All mergeable commits were merged, leaving c, d, and e as the only one not merged
                listOf("c", "d", "e"),
                localGit
                    .getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `merge sets baseRef to targetRef on the latest PR that is mergeable`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
                        }
                        commit {
                            title = "five"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("five")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("five")
                        baseRef = buildRemoteRef("four")
                        title = "five"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three", "four", "five")

            merge(RefSpec("development", "main"))
            assertEquals(
                DEFAULT_TARGET_REF,
                gitHub.getPullRequestsByHeadRef(buildRemoteRef("four")).last().baseRefName,
            )
        }
    }

    @Test
    fun `merge closes PRs that were rolled up into the PR for the latest mergeable commit`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
                        }
                        commit {
                            title = "five"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("five")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("five")
                        baseRef = buildRemoteRef("four")
                        title = "five"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three", "four", "five")

            merge(RefSpec("development", "main"))
            assertEventuallyEquals(
                listOf("five"),
                getActual = { gitHub.getPullRequests().map(PullRequest::title) },
            )
        }
    }

    @Test
    fun `merge - none are mergeable`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            merge(RefSpec("development", "main"))
            assertEquals(
                listOf("one", "two", "three"),
                gitHub.getPullRequests().map(PullRequest::title),
            )
        }
    }

    @Test
    fun `merge with refspec`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two")
            merge(RefSpec("development^", "main"))
            assertEventuallyEquals(
                listOf("three"),
                getActual = { gitHub.getPullRequests().map(PullRequest::title) },
            )
        }
    }

    @Test
    fun `merge deletes relevant branches`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a_01")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("b_01")
                        }
                        commit {
                            title = "c"
                            localRefs += "dev1"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c_01")
                        }
                    }
                },
            )
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "z"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("z")
                        }
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            localRefs += "dev2"
                            remoteRefs += buildRemoteRef("b")
                        }
                        commit {
                            title = "c"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("z")
                        baseRef = "main"
                        title = "z"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("a")
                        baseRef = buildRemoteRef("z")
                        title = "a"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("b")
                        baseRef = buildRemoteRef("a")
                        title = "b"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("c")
                        baseRef = buildRemoteRef("b")
                        title = "c"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("z", "a", "b", "c")
            merge(RefSpec("dev2", "main"))
            assertEquals(
                listOf(
                    buildRemoteRef("b"),
                    buildRemoteRef("b_01"),
                    buildRemoteRef("c"),
                    buildRemoteRef("c_01"),
                    "main",
                ),
                localGit.getRemoteBranches().map(RemoteBranch::name),
            )
        }
    }

    @Test
    fun `merge rebases remaining PRs after merge`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a_01")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("b_01")
                        }
                        commit {
                            title = "c"
                            localRefs += "dev1"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c_01")
                        }
                    }
                },
            )
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "z"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("z")
                        }
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            localRefs += "dev2"
                            remoteRefs += buildRemoteRef("b")
                        }
                        commit {
                            title = "c"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("z")
                        baseRef = "main"
                        title = "z"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("a")
                        baseRef = buildRemoteRef("z")
                        title = "a"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("b")
                        baseRef = buildRemoteRef("a")
                        title = "b"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("c")
                        baseRef = buildRemoteRef("b")
                        title = "c"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("z", "a", "b", "c")
            merge(RefSpec("dev2", "main"))
            assertEquals(
                setOf(buildRemoteRef("c") to "main"),
                gitHub.getPullRequests().map { it.headRefName to it.baseRefName }.toSet(),
            )
        }
    }
    //endregion

    @Test
    fun `clean deletes expected branches`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a_01")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("b_01")
                        }
                        commit {
                            title = "c"
                            localRefs += "dev1"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("c_01")
                        }
                    }
                },
            )
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "z"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("z")
                        }
                        commit {
                            title = "a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("a")
                        }
                        commit {
                            title = "b"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("b")
                        }
                        commit {
                            title = "c"
                            willPassVerification = true
                            localRefs += "dev2"
                            remoteRefs += buildRemoteRef("c")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("z")
                        baseRef = "main"
                        title = "z"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("a")
                        baseRef = buildRemoteRef("z")
                        title = "a"
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            gitJaspr.clean(false)
            assertEquals(
                listOf(
                    buildRemoteRef("a"),
                    buildRemoteRef("a_01"),
                    buildRemoteRef("z"),
                    "main",
                ),
                localGit.getRemoteBranches().map(RemoteBranch::name),
            )
        }
    }

    // It may seem silly to repeat what is already defined in GitJaspr.HEADER, but if a dev changes the header I want
    // these tests to break so that any such changes are very deliberate. This is a compromise between referencing the
    // same value from both tests and prod and the other extreme of repeating this header text manually in every test.
    fun String.toStatusString(actual: String): String {
        // Extract URLs from the actual string and put them into the expected. For functional tests I can't predict what
        // they will be, so I only want to validate that they are present.
        val urls = "(http.*) :".toRegex().findAll(actual).map { result -> result.groupValues[1] }.toList()

        val formattedString = try {
            format(*urls.toTypedArray())
        } catch (e: MissingFormatArgumentException) {
            logger.error("Format string doesn't have enough arguments, should have {}", urls.size)
            this
        }
        return """
            | ┌─ commit is pushed
            | │ ┌─ pull request exists
            | │ │ ┌─ github checks pass
            | │ │ │ ┌── pull request approved
            | │ │ │ │ ┌─── stack check
            | │ │ │ │ │
            |$formattedString

        """.trimMargin()
    }

    // Much like toStatusString above, this repeats the PR body footer. See notes there for the rationale.
    fun String.toPrBodyString(actual: String = ""): String {
        val numRegex = "^- (#\\d+)(?: ⬅)?$".toRegex()
        val historyLineRegex = "^ {2}- (?:\\[.*]\\(https?://(.*?)/(.*?)/(.*?)/compare/jaspr.*?\\)(?:, )?)+".toRegex()
        val list = actual
            .lines()
            .fold(emptyList<String>()) { list, line ->
                val numRegexResult = numRegex.matchEntire(line)
                val historyLineRegexResult = historyLineRegex.matchEntire(line)
                when {
                    numRegexResult != null -> {
                        list + numRegexResult.groupValues[1]
                    }
                    historyLineRegexResult != null -> {
                        list + historyLineRegexResult.groupValues.drop(1)
                    }
                    else -> list
                }
            }
        val formattedString = try {
            format(*list.toTypedArray())
        } catch (e: MissingFormatArgumentException) {
            logger.error("Format string doesn't have enough arguments, should have {}", list.size)
            this
        }
        return "$formattedString\n" +
            "⚠️ *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). " +
            "Do not merge manually using the UI - doing so may have unexpected results.*\n"
    }
}
