package sims.michael.gitjaspr

import java.util.MissingFormatArgumentException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.slf4j.Logger
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.TestCaseData
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

interface GitJasprTest {

    val logger: Logger
    val useFakeRemote: Boolean
        get() = true

    suspend fun GitHubTestHarness.push() = gitJaspr.push()

    suspend fun GitHubTestHarness.getAndPrintStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ) = gitJaspr.getStatusString(refSpec).also(::print)

    suspend fun GitHubTestHarness.merge(refSpec: RefSpec) = gitJaspr.merge(refSpec)

    suspend fun GitHubTestHarness.autoMerge(refSpec: RefSpec, pollingIntervalSeconds: Int = 10) =
        gitJaspr.autoMerge(refSpec)

    suspend fun GitHubTestHarness.getRemoteCommitStatuses(stack: List<Commit>) =
        gitJaspr.getRemoteCommitStatuses(stack)

    suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long = 30_000,
        pollingDelay: Long =
            5_000, // Lowering this value too much will result in exhausting rate limits
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
                }
            )
            val exception = assertThrows<GitJasprException> { push() }
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
                }
            )
            push()
            localGit.fetch(remoteName)
            val stack =
                localGit.getLocalCommitStack(remoteName, DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
            val remoteCommitStatuses = getRemoteCommitStatuses(stack)
            assertEquals(
                localGit.log("HEAD", maxCount = 1).single(),
                remoteCommitStatuses.single().remoteCommit,
            )
        }
    }

    // region status tests
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
                }
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
                }
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[ㄧㄧㄧㄧㄧㄧ] %s : three
                |[ㄧㄧㄧㄧㄧㄧ] %s : two
                |[ㄧㄧㄧㄧㄧㄧ] %s : one"""
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
                }
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[ㄧㄧㄧㄧㄧㄧ] %s : three
                |[ㄧㄧㄧㄧㄧㄧ] %s : two
                |[✅ㄧㄧㄧㄧㄧ] %s : one"""
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
                }
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[ㄧㄧㄧㄧㄧㄧ] %s : three
                |[ㄧㄧㄧㄧㄧㄧ] %s : two
                |[✅✅⌛✅ㄧㄧ] %s : %s : one"""
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
                }
            )

            waitForChecksToConclude("one")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[ㄧㄧㄧㄧㄧㄧ] %s : three
                |[ㄧㄧㄧㄧㄧㄧ] %s : two
                |[✅✅✅✅ㄧㄧ] %s : %s : one"""
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
                }
            )

            waitForChecksToConclude("one")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅ㄧㄧ] %s : %s : three
                |[✅✅✅✅ㄧㄧ] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
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
                }
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅✅ㄧ] %s : %s : three
                    |[✅✅✅✅✅ㄧ] %s : %s : two
                    |[✅✅✅✅✅ㄧ] %s : %s : one
                    |
                    |Your stack is out-of-date with the base branch (1 commit behind main).
                    |You'll need to rebase it (`git rebase $remoteName/main`) before your stack will be mergeable.
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
                                commit { title = "only_on_main_one" }
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
                }
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                    |[✅✅✅✅✅ㄧ] %s : %s : three
                    |[✅✅✅✅✅ㄧ] %s : %s : two
                    |[✅✅✅✅✅ㄧ] %s : %s : one
                    |
                    |Your stack is out-of-date with the base branch (2 commits behind main).
                    |You'll need to rebase it (`git rebase $remoteName/main`) before your stack will be mergeable.
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
                }
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : three
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status stack check with draft PR`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            id = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            id = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "draft: three"
                            id = "three"
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
                        title = "draft: three"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅ㄧ✅ㄧ] %s : %s : draft: three
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
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
                }
            )

            push()

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEventuallyEquals(
                """
                |[✅✅✅✅ㄧㄧ] %s : %s : three
                |[✅✅✅✅✅ㄧ] %s : %s : two
                |[✅✅✅✅ㄧㄧ] %s : %s : one"""
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
                }
            )

            push()

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅ㄧㄧ] %s : %s : three
                |[✅✅❌✅ㄧㄧ] %s : %s : two
                |[✅✅✅✅ㄧㄧ] %s : %s : one"""
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
                }
            )

            waitForChecksToConclude("three")

            val actual = getAndPrintStatusString(RefSpec("development", "development"))
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : three"""
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status with out of date commit`() {
        withTestSetup(useFakeRemote, rollBackChanges = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                            willPassVerification = true
                        }
                        commit {
                            title = "two"
                            remoteRefs += buildRemoteRef("two")
                            willPassVerification = true
                        }
                        commit {
                            title = "three"
                            remoteRefs += buildRemoteRef("three")
                            willPassVerification = true
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
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
                }
            )

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                }
            )

            waitForChecksToConclude("one", "three", "four")

            val actual = getAndPrintStatusString(RefSpec("development", "main"))
            assertEquals(
                """
                |[❗✅✅✅✅ㄧ] %s : %s : four
                |[❗✅✅✅✅ㄧ] %s : %s : three
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `status with two commits sharing same commit id`() {
        withTestSetup(useFakeRemote, rollBackChanges = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            id = "a"
                        }
                        commit {
                            title = "two"
                            id = "a"
                        }
                        commit {
                            title = "three"
                            id = "c"
                            localRefs += "main"
                        }
                    }
                }
            )

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[ㄧㄧㄧㄧㄧㄧ] %s : three
                |[❗ㄧㄧㄧㄧㄧ] %s : two
                |[❗ㄧㄧㄧㄧㄧ] %s : one
                |
                |Some commits in your local stack have duplicate IDs:
                |- a: (one, two)
                |This is likely because you've based new commit messages off of those from other commits.
                |Please correct this by amending the commits and deleting the commit-id lines, then retry your operation."""
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    // Test for a bug that was occurring when the stack had commits without ids
    @Test
    fun `status without commit IDs does not crash`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            id = ""
                        }
                        commit {
                            title = "two"
                            id = ""
                        }
                        commit {
                            title = "three"
                            id = ""
                            localRefs += "main"
                        }
                    }
                }
            )

            logger.info(gitJaspr.getStatusString())
        }
    }

    @Test
    fun `getPullRequests pagination works as expected`() {
        withTestSetup(useFakeRemote, getPullRequestsPageSize = 2) {
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
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
                        }
                        commit {
                            title = "five"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("five")
                        }
                        commit {
                            title = "six"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("six")
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
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("six")
                        baseRef = buildRemoteRef("five")
                        title = "six"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            waitForChecksToConclude("one", "two", "three", "four", "five", "six")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : six
                |[✅✅✅✅✅✅] %s : %s : five
                |[✅✅✅✅✅✅] %s : %s : four
                |[✅✅✅✅✅✅] %s : %s : three
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Test
    fun `named stack up to date`() {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
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
                    checkout = "development"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one", "two")

            val actual = getAndPrintStatusString()

            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(actual, NamedStackInfo(stackName, 0, 0, remoteName)),
                actual,
            )
        }
    }

    @Test
    fun `named stack behind`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "behind"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
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
                    checkout = "development"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one", "two")

            localGit.checkout("behind")
            localGit.setUpstreamBranch(
                remoteName,
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$stackName",
            )
            val actual = getAndPrintStatusString()

            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(
                        actual,
                        NamedStackInfo(
                            stackName,
                            numCommitsAhead = 0,
                            numCommitsBehind = 1,
                            remoteName,
                        ),
                    ),
                actual,
            )
        }
    }

    @Test
    fun `named stack ahead`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("one")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    checkout = "development"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one")

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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    checkout = "development"
                }
            )

            val actual = getAndPrintStatusString()

            assertEquals(
                """
                |[✅ㄧㄧㄧㄧㄧ] %s : two
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(
                        actual,
                        NamedStackInfo(
                            stackName,
                            numCommitsAhead = 1,
                            numCommitsBehind = 0,
                            remoteName,
                        ),
                    ),
                actual,
            )
        }
    }

    @Test
    fun `named stack diverged`() {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
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
                    checkout = "development"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one")

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
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
                    checkout = "development"
                }
            )

            val actual = getAndPrintStatusString()

            assertEquals(
                """
                |[✅ㄧㄧㄧㄧㄧ] %s : three
                |[✅✅✅✅✅✅] %s : %s : one"""
                    .trimMargin()
                    .toStatusString(
                        actual,
                        NamedStackInfo(
                            stackName,
                            numCommitsAhead = 1,
                            numCommitsBehind = 1,
                            remoteName,
                        ),
                    ),
                actual,
            )
        }
    }

    // endregion

    // region push tests
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
                }
            )

            push()

            assertEquals(
                listOf("three", "two", "one"),
                localGit.log("$remoteName/main", maxCount = 3).map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `adding commit ID does not indent subject line`() {
        // assert the absence of a bug that used to occur with commits that had message bodies...
        // the subject and footer
        // lines would be indented, which was invalid and would cause the commit(s) to effectively
        // have no ID
        // if this test doesn't throw, then we're good
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title =
                                "Bump EnricoMi/publish-unit-test-result-action from 2.1.0 to 2.11.0"
                            body =
                                """
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
                                |Signed-off-by: dependabot[bot] <support@github.com>"""
                                    .trimMargin()
                            id = ""
                            localRefs += "main"
                        }
                    }
                }
            )

            push()
        }
    }

    @Test
    fun `add footers does not consider a trailing URL a footer line`() {
        // assert the absence of a bug where a URL was being interpreted as a footer line
        withTestSetup(useFakeRemote, rollBackChanges = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Fix end of year data issue [providerDir]"
                            body =
                                """
                                |See this Slack thread:
                                |https://trillianthealth.slack.com/archives/C04J6Q655GR/p1702918943374039?thread_ts=1702918322.439999&cid=C04J6Q655GR
                                |"""
                                    .trimMargin()
                            id = ""
                            localRefs += "main"
                        }
                    }
                }
            )

            push()

            assertEquals(
                """
                Fix end of year data issue [providerDir]

                See this Slack thread:
                https://trillianthealth.slack.com/archives/C04J6Q655GR/p1702918943374039?thread_ts=1702918322.439999&cid=C04J6Q655GR

                commit-id: 0

                """
                    .trimIndent(),
                localGit.log("HEAD", maxCount = 1).single().fullMessage.withCommitIdZero(),
            )
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
                }
            )

            push()

            assertEquals(
                """
                Market Explorer: Remove unused code

                commit-id: 0

                """
                    .trimIndent(),
                localGit.log("HEAD", maxCount = 1).single().fullMessage.withCommitIdZero(),
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
                            }
                            commit { title = "C" }
                            commit {
                                title = "D"
                                localRefs += "main"
                            }
                        }
                    },
                ),
            )
            .map { (name, collectCommits) ->
                DynamicTest.dynamicTest(name) {
                    withTestSetup(useFakeRemote) {
                        createCommitsFrom(collectCommits)
                        push()
                        val numCommits = collectCommits.repository.commits.size
                        assertTrue(
                            localGit
                                .logRange("${GitClient.HEAD}~$numCommits", GitClient.HEAD)
                                .mapNotNull(Commit::id)
                                .filter(String::isNotBlank)
                                .size == numCommits
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
                }
            )
            push()

            assertEquals(
                (1..3).map { buildRemoteRef(it.toString()) },
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name) - DEFAULT_TARGET_REF,
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
                }
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
                }
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
                }
            )
            push()
            gitLogLocalAndRemote()

            assertEquals(
                listOf("a", "a_01", "b", "b_01", "c", "c_01", "d", "e", "z").map { name ->
                    buildRemoteRef(name)
                },
                localGit
                    .getRemoteBranches(remoteName)
                    .map(RemoteBranch::name)
                    .filter { name ->
                        name.startsWith(RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX)
                    }
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
                }
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
                }
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
                }
            )
            val exception =
                assertThrows<GitJaspr.SinglePullRequestPerCommitConstraintViolation> { push() }
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
                }
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
                }
            )

            push()

            val remotePrs = gitHub.getPullRequestsById(listOf("E", "C", "one", "B", "A", "two"))

            val prs =
                remotePrs
                    .map { pullRequest -> pullRequest.baseRefName to pullRequest.headRefName }
                    .toSet()
            val commits =
                localGit
                    .log(GitClient.HEAD, 6)
                    .reversed()
                    .windowedPairs()
                    .map { (prevCommit, currentCommit) ->
                        val baseRefName =
                            prevCommit?.let {
                                buildRemoteRef(checkNotNull(it.id), DEFAULT_TARGET_REF)
                            } ?: DEFAULT_TARGET_REF
                        val headRefName =
                            buildRemoteRef(checkNotNull(currentCommit.id), DEFAULT_TARGET_REF)
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
                }
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
                }
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
                }
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

    @Test
    fun `push with two commits sharing same commit id`() {
        withTestSetup(useFakeRemote, rollBackChanges = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            id = "a"
                        }
                        commit {
                            title = "two"
                            id = "a"
                        }
                        commit {
                            title = "three"
                            id = "c"
                            localRefs += "main"
                        }
                    }
                }
            )

            push()
            // No assert here... I'm basically just testing that this doesn't throw an unhandled
            // error, like it would
            // if we tried to push multiple source refs to the same destination ref
        }
    }

    @Test
    fun `push new named stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit { title = "four" }
                        commit {
                            title = "five"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)
            assertEquals(
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$stackName",
                localGit.getUpstreamBranch(remoteName)?.name,
            )
        }
    }

    @Test
    fun `push new named stack from detached HEAD is unsupported`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit { title = "four" }
                        commit {
                            title = "five"
                            localRefs += "main"
                        }
                    }
                }
            )

            localGit.checkout(localGit.log().first().hash)
            val message =
                assertThrows<GitJasprException> { gitJaspr.push(stackName = "my-stack-name") }
                    .message
            assertContains(message, "detached HEAD")
        }
    }

    @Test
    fun `push existing named stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit { title = "four" }
                        commit {
                            title = "five"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit { title = "four" }
                        commit { title = "five" }
                        commit {
                            title = "six"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            gitJaspr.push()
            assertEquals(
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$stackName",
                localGit.getUpstreamBranch(remoteName)?.name,
            )
        }
    }

    // endregion

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
                            footerLines["footer-line-test"] =
                                "hi" // Will be stripped out in the description
                            localRefs += "main"
                        }
                    }
                }
            )
            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
                    <!-- jaspr start -->
                    ### 1

                    **Stack**:
                    - %s
                    - %s
                    - %s ⬅

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### 2

                    **Stack**:
                    - %s
                    - %s ⬅
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### 3

                    This is a body

                    **Stack**:
                    - %s ⬅
                    - %s
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
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
                }
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
                }
            )

            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
                    <!-- jaspr start -->
                    ### A

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### B

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### C

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### D

                    **Stack**:
                    - %s
                    - %s ⬅
                    - %s
                    - %s
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### E

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### one

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### two

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

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
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
                }
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
                }
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
                }
            )

            push()

            val actual = gitHub.getPullRequests().map(PullRequest::body)
            val actualIterator = actual.iterator()
            assertEquals(
                listOf(
                    """
                    <!-- jaspr start -->
                    ### A

                    **Stack**:
                    - %s
                    - %s
                    - %s ⬅

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### B

                    **Stack**:
                    - %s
                    - %s ⬅
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### C

                    **Stack**:
                    - %s ⬅
                    - %s
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### D

                    **Stack**:
                    - %s ⬅
                    - %s
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                    """
                    <!-- jaspr start -->
                    ### E

                    **Stack**:
                    - %s ⬅
                    - %s
                    - %s

                    """
                        .trimIndent()
                        .toPrBodyString(actualIterator.next()),
                ),
                actual,
            )
        }
    }

    // endregion

    // region merge tests
    @Test
    fun `merge empty stack`() {
        withTestSetup(useFakeRemote) { merge(RefSpec("main", "main")) }
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
                }
            )

            waitForChecksToConclude("one", "two", "three")
            merge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `merge - push and merge`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "main"
                        }
                    }
                }
            )

            push()

            createCommitsFrom(
                testCase {
                    // Intentionally repeating the commits here... this is because GitHubTestHarness
                    // will not "notice"
                    // that the commits should pass verification unless they are defined again as
                    // part of this pass.
                    // I should fix that, but this works for now.
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "main"
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
                }
            )
            waitForChecksToConclude("one", "two", "three")
            merge(RefSpec("main", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(remoteName, "main", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `merge just one`() {
        withTestSetup(useFakeRemote, rollBackChanges = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("one")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            waitForChecksToConclude("one")
            merge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF),
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
                }
            )

            autoMerge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `autoMerge with limited refSpec`() {
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
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            autoMerge(RefSpec("development^", "main"))

            assertEquals(
                listOf("five"),
                localGit
                    .getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
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
                                commit { title = "only_on_main_one" }
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
                }
            )

            merge(RefSpec("development", "main"))
            assertEquals(
                listOf("one", "two", "three"), // Nothing was merged
                localGit
                    .getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
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
                            title = "draft: c"
                            id = "c"
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
                        title = "draft: c"
                        willBeApprovedByUserKey = "michael"
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
                }
            )

            waitForChecksToConclude("a", "b", "c", "d", "e")
            merge(RefSpec("development", "main"))
            assertEquals(
                // All mergeable commits were merged, leaving c, d, and e as the only one not merged
                listOf("draft: c", "d", "e"),
                localGit
                    .getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
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
                }
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
                }
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
                }
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
                }
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
                }
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
                }
            )

            waitForChecksToConclude("z", "a", "b", "c")
            merge(RefSpec("dev2", "main"))
            assertEquals(
                listOf(buildRemoteRef("c"), buildRemoteRef("c_01"), "main"),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name),
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
                }
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
                }
            )

            waitForChecksToConclude("z", "a", "b", "c")
            merge(RefSpec("dev2", "main"))
            assertEquals(
                setOf(buildRemoteRef("c") to "main"),
                gitHub.getPullRequests().map { it.headRefName to it.baseRefName }.toSet(),
            )
        }
    }

    @Test
    fun `merge with out of date commit`() {
        withTestSetup(useFakeRemote, rollBackChanges = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                            willPassVerification = true
                        }
                        commit {
                            title = "two"
                            remoteRefs += buildRemoteRef("two")
                            willPassVerification = true
                        }
                        commit {
                            title = "three"
                            remoteRefs += buildRemoteRef("three")
                            willPassVerification = true
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
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
                }
            )

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                }
            )

            waitForChecksToConclude("one", "two", "three", "four")
            merge(RefSpec("development", "main"))
            assertEquals(
                // One was merged, leaving three and four unmerged
                listOf("three", "four"),
                localGit
                    .getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
            )
        }
    }

    // endregion

    // region clean tests
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
                }
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
                }
            )

            gitJaspr.clean(false)
            assertEquals(
                listOf(buildRemoteRef("a"), buildRemoteRef("a_01"), buildRemoteRef("z"), "main"),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name),
            )
        }
    }

    @Test
    fun `getOrphanedBranches prunes stale tracking branches`() {
        withTestSetup(useFakeRemote, rollBackChanges = false) {
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
                }
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
                }
            )

            remoteGit.deleteBranches(
                listOf(buildRemoteRef("c"), buildRemoteRef("c_01")),
                force = true,
            )

            assertEquals(
                listOf(buildRemoteRef("b"), buildRemoteRef("b_01")),
                gitJaspr.getOrphanedBranches().sorted(),
            )
        }
    }

    // endregion

    private data class NamedStackInfo(
        val name: String,
        val numCommitsAhead: Int,
        val numCommitsBehind: Int,
        val remoteName: String,
    )

    // It may seem silly to repeat what is already defined in GitJaspr.HEADER, but if a dev changes
    // the header I want
    // these tests to break so that any such changes are very deliberate. This is a compromise
    // between referencing the
    // same value from both tests and prod and the other extreme of repeating this header text
    // manually in every test.
    private fun String.toStatusString(
        actual: String,
        namedStackInfo: NamedStackInfo? = null,
    ): String {
        // Extract commit hashes and URLs from the actual string and put them into the expected. I
        // can't predict what
        // they will be, so I only want to validate that they are present.
        val extracts =
            "] (.*?) : (?:(http.*?) : )?.*?\n"
                .toRegex()
                .findAll(actual)
                .flatMap { result -> result.groupValues.drop(1) }
                .filter { it.isNotEmpty() }
                .toList()

        val formattedString =
            try {
                format(*extracts.toTypedArray())
            } catch (e: MissingFormatArgumentException) {
                logger.error(
                    "Format string doesn't have enough arguments, should have {}",
                    extracts.size,
                )
                this
            }
        val namedStackInfoString = buildString {
            // As above, this duplicates the string building logic defined in GitJaspr, but this is
            // so any changes
            // to the rendering is done very deliberately.
            if (namedStackInfo != null) {
                appendLine()
                appendLine("Stack name: ${namedStackInfo.name}")
                with(namedStackInfo) {
                    appendLine(
                        if (numCommitsBehind == 0 && numCommitsAhead == 0) {
                            "Your stack is up to date with the remote stack in '$remoteName'."
                        } else if (numCommitsBehind > 0 && numCommitsAhead == 0) {
                            "Your stack is behind the remote stack in '$remoteName' by " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)}."
                        } else if (numCommitsBehind == 0) { // && numCommitsAhead > 0
                            "Your stack is ahead of the remote stack in '$remoteName' by " +
                                "$numCommitsAhead ${commitOrCommits(numCommitsAhead)}."
                        } else { // numCommitsBehind > 0 && numCommitsAhead > 0
                            "Your stack and the remote stack in '$remoteName' have diverged, and have " +
                                "$numCommitsAhead and $numCommitsBehind different commits each, " +
                                "respectively."
                        }
                    )
                }
            }
        }
        return """
            | ┌─────────── commit pushed
            | │ ┌─────────── exists       ┐
            | │ │ ┌───────── checks pass  │ PR
            | │ │ │ ┌─────── ready        │
            | │ │ │ │ ┌───── approved     ┘
            | │ │ │ │ │ ┌─ stack check
            | │ │ │ │ │ │ 
            |$formattedString

        """
            .trimMargin() + namedStackInfoString
    }

    // Much like toStatusString above, this repeats the PR body footer. See notes there for the
    // rationale.
    fun String.toPrBodyString(actual: String = ""): String {
        val numRegex = "^- (#\\d+)(?: ⬅)?$".toRegex()
        val historyLineRegex =
            "^ {2}- (?:\\[.*]\\(https?://(.*?)/(.*?)/(.*?)/compare/jaspr.*?\\)(?:, )?)+".toRegex()
        val list =
            actual.lines().fold(emptyList<String>()) { list, line ->
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
        val formattedString =
            try {
                format(*list.toTypedArray())
            } catch (e: MissingFormatArgumentException) {
                logger.error(
                    "Format string doesn't have enough arguments, should have {}",
                    list.size,
                )
                this
            }
        return "$formattedString\n" +
            "⚠️ *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). " +
            "Do not merge manually using the UI - doing so may have unexpected results.*\n"
    }

    private fun commitOrCommits(count: Int) = if (count == 1) "commit" else "commits"

    /**
     * Returns a copy of the string with the commit ID replaced with 0. Useful for comparing full
     * commit messages in tests where you don't care about the commit ID.
     */
    fun String.withCommitIdZero(): String =
        // Using zero-width assertions in the regex to keep the replacement simple
        replace("(?<=$COMMIT_ID_LABEL: ).*?(?=\n)".toRegex(), "0")
}
