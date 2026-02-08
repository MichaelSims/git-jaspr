package sims.michael.gitjaspr

import java.util.MissingFormatArgumentException
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.slf4j.Logger
import sims.michael.gitjaspr.GitJaspr.CleanPlan
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.TestCaseData
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitjaspr.testing.Checkout
import sims.michael.gitjaspr.testing.Clean
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import sims.michael.gitjaspr.testing.DontPush
import sims.michael.gitjaspr.testing.Merge
import sims.michael.gitjaspr.testing.PrBody
import sims.michael.gitjaspr.testing.Push
import sims.michael.gitjaspr.testing.Status

interface GitJasprTest {

    val logger: Logger
    val useFakeRemote: Boolean
        get() = true

    suspend fun GitHubTestHarness.push(count: Int? = null) = gitJaspr.push(count = count)

    suspend fun GitHubTestHarness.getAndPrintStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ) = gitJaspr.getStatusString(refSpec).also(::print)

    suspend fun GitHubTestHarness.merge(refSpec: RefSpec, count: Int? = null) =
        gitJaspr.merge(refSpec, count = count)

    suspend fun GitHubTestHarness.autoMerge(
        refSpec: RefSpec,
        pollingIntervalSeconds: Int = 10,
        count: Int? = null,
    ) = gitJaspr.autoMerge(refSpec, pollingIntervalSeconds = 1, maxAttempts = 5, count = count)

    suspend fun GitHubTestHarness.getRemoteCommitStatuses(stack: List<Commit>) =
        gitJaspr.getRemoteCommitStatuses(stack)

    suspend fun GitHubTestHarness.checkout(stackName: String) {
        val stacks = gitJaspr.getNamedStacks(DEFAULT_TARGET_REF)
        val stack =
            checkNotNull(stacks.find { it.stackName == stackName }) {
                "No named stack '$stackName' found"
            }
        gitJaspr.checkoutNamedStack(stack)
    }

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
        // This test fails when ran from GitJasprFunctionalExternalProcessTest because the exception
        // type is lost. This is not a problem, but I should probably try to fix it at some point
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
    @Status
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

    @Status
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
                |[ㄧㄧㄧㄧㄧㄧ] %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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
                |[✅ㄧㄧㄧㄧㄧ] %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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
                |[✅✅⌛✅ㄧㄧ] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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
                |[✅✅✅✅ㄧㄧ] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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

    @Status
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

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
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

            val stackName = "flubber"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEventuallyEquals(
                """
                |[✅✅✅✅ㄧㄧ] %s : %s : three
                |[✅✅✅✅✅ㄧ] %s : %s : two
                |[✅✅✅✅ㄧㄧ] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual, NamedStackInfo(stackName, 0, 0, remoteName)),
                getActual = { actual },
            )
        }
    }

    @Status
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

            val stackName = "flubber"
            gitJaspr.push(stackName = stackName)

            waitForChecksToConclude("one", "two", "three")

            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅ㄧㄧ] %s : %s : three
                |[✅✅❌✅ㄧㄧ] %s : %s : two
                |[✅✅✅✅ㄧㄧ] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual, NamedStackInfo(stackName, 0, 0, remoteName)),
                actual,
            )
        }
    }

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : three
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
    @Test
    fun `status with out of date commit`() {
        withTestSetup(useFakeRemote) {
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Status
    @Test
    fun `status with two commits sharing same commit id`() {
        withTestSetup(useFakeRemote) {
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
                |Please correct this by amending the commits and deleting the commit-id lines, then retry your operation.
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    // Test for a bug that was occurring when the stack had commits without ids
    @Status
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

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual, NamedStackInfo(stackName, 0, 0, remoteName)),
                actual,
            )
        }
    }

    @Status
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
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/$stackName",
            )
            val actual = getAndPrintStatusString()

            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : one
                """
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

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
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

    @Status
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
                |[✅✅✅✅✅✅] %s : %s : one
                """
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
    @Push
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

    @Push
    @Test
    fun `adding commit ID does not indent subject line`() {
        // Assert the absence of a bug that used to occur with commits that had message bodies...
        // The subject and footer lines would be indented, which was invalid and would cause the
        // commit(s) to effectively have no ID.
        // If this test doesn't throw, then we're good.
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
                                |Signed-off-by: dependabot[bot] <support@github.com>
                                """
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

    @Push
    @Test
    fun `add footers does not consider a trailing URL a footer line`() {
        // assert the absence of a bug where a URL was being interpreted as a footer line
        withTestSetup(useFakeRemote) {
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

    @Push
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
                                title = "3"
                                id = ""
                            }
                            commit {
                                title = "4"
                                id = ""
                            }
                            commit {
                                title = "5"
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
                            commit { title = "C" }
                            commit { title = "D" }
                            commit {
                                title = "6"
                                id = ""
                            }
                            commit {
                                title = "7"
                                id = ""
                            }
                            commit {
                                title = "8"
                                id = ""
                            }
                            commit { title = "E" }
                            commit {
                                title = "F"
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

    @Push
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
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @Push
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
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name)
                    .filter { name ->
                        name.startsWith(RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX)
                    }
                    .sorted(),
            )
        }
    }

    @Push
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

    @Push
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

    @Push
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

    @Push
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

    @Push
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

    @Push
    @Test
    fun `push with two commits sharing same commit id`() {
        withTestSetup(useFakeRemote) {
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

    @Push
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

            val fullStackName =
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/$stackName"
            assertTrue(
                localGit.getRemoteBranches(remoteName).any { branch ->
                    branch.name == fullStackName
                }
            )
        }
    }

    @Push
    @Test
    fun `push from detached HEAD is supported`() {
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

            localGit.checkout(localGit.log().last().hash)
            gitJaspr.push()
            // Assert we have a named stack and it points to commit five
            assertEquals(
                "five",
                localGit
                    .getRemoteBranches(remoteName)
                    .single { branch ->
                        RemoteNamedStackRef.parse(
                            branch.name,
                            DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
                        ) != null
                    }
                    .commit
                    .shortMessage,
            )
        }
    }

    @Push
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
            val remoteNamedStack =
                RemoteNamedStackRef(stackName, DEFAULT_TARGET_REF, remoteName = remoteName).name()
            val remoteDiff = localGit.logRange("main", remoteNamedStack).map(Commit::shortMessage)
            val localDiff = localGit.logRange(remoteNamedStack, "main").map(Commit::shortMessage)
            assertEquals(
                emptyList(),
                remoteDiff,
                "main and $remoteNamedStack should be the same, but remote diff isn't empty",
            )
            assertEquals(
                emptyList(),
                localDiff,
                "main and $remoteNamedStack should be the same, but local diff isn't empty",
            )
        }
    }

    @Push
    @Test
    fun `push existing named stack with new name`() {
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
                    checkout = "main"
                }
            )

            val secondStackName = "my-second-stack-name"
            gitJaspr.push(stackName = secondStackName)

            waitForChecksToConclude("one", "two", "three")

            // As of now, the best way to test the detected stack names is to assert on the
            // status output
            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : three
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual, NamedStackInfo(secondStackName, 0, 0, remoteName)),
                actual,
            )

            localGit.checkout(localGit.log("main", 2).last().hash)

            // Now that our HEAD commit is reachable by two stacks, this should be considered
            // ambiguous, which displays the same as not finding a stack
            val detachedHeadActual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(detachedHeadActual, null),
                detachedHeadActual,
            )
        }
    }

    @Push
    @Test
    fun `push stack with commit contained in multiple named stacks`() {
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
                    checkout = "main"
                }
            )

            gitJaspr.push(stackName = "stack-1")

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit {
                            title = "four"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            gitJaspr.push(stackName = "stack-2")

            localGit.checkout(localGit.log().reversed()[1].hash)

            gitJaspr.push()

            val namedStacks =
                localGit
                    .getRemoteBranches(remoteName)
                    .mapNotNull { branch ->
                        RemoteNamedStackRef.parse(
                                branch.name,
                                DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
                            )
                            ?.name()
                    }
                    .toSet()

            val expected =
                listOf("stack-1", "stack-2").map { RemoteNamedStackRef(it).name() }.toSet()
            val difference = expected - namedStacks

            assertEquals(3, namedStacks.size)
            assertEquals(emptySet(), difference, "Expected named stacks were not found")
        }
    }

    @Push
    @Test
    fun `push empty stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        // No commits - empty stack
                    }
                }
            )
            push()
        }
    }

    @Push
    @Test
    fun `push without explicit stack name generates unique name`() {
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
                    checkout = "main"
                }
            )

            // Push without providing a stack name
            gitJaspr.push()

            val namedStackBranches =
                localGit.getRemoteBranches(remoteName).filter { branch ->
                    branch.name.startsWith(
                        "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/"
                    )
                }

            assertEquals(1, namedStackBranches.size)

            val generatedName =
                checkNotNull(
                        RemoteNamedStackRef.parse(
                            namedStackBranches.single().name,
                            DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
                        )
                    )
                    .name()

            logger.info("Generated stack name: $generatedName")
        }
    }

    @Push
    @Test
    fun `suggestStackName returns suggested name for new stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            val suggested = gitJaspr.suggestStackName()
            assertEquals("one", suggested)
        }
    }

    @Push
    @Test
    fun `suggestStackName returns null for existing stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            // Push with an explicit name to create an existing stack
            gitJaspr.push(stackName = "my-stack")

            // suggestStackName should return null since the stack already has a name
            val suggested = gitJaspr.suggestStackName()
            assertEquals(null, suggested)
        }
    }

    @Push
    @Test
    fun `push without explicit stack name handles collision by retrying`() {
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
                    checkout = "main"
                }
            )

            // The derived name for commit subject "X" is "x"
            val derivedName = StackNameGenerator.generateName("X")

            // Pre-create a named stack with the derived name to simulate collision
            gitJaspr.push(stackName = derivedName)

            // Now create a new stack whose first commit subject also derives to the same name
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "X" }
                        commit { title = "Y" }
                        commit {
                            title = "Z"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            // Generate a unique name: "x" will collide, so it should append a suffix
            val localRef = localGit.log("dev", 1).single().hash
            val generatedName = gitJaspr.generateUniqueStackName(DEFAULT_TARGET_REF, localRef, "X")

            // Verify that a suffixed name was generated (not the bare derived name)
            assertNotEquals(derivedName, generatedName)
            assertTrue(generatedName.startsWith("$derivedName-"))

            // Verify both named stacks exist
            val namedStackBranches =
                localGit.getRemoteBranches(remoteName).filter { branch ->
                    branch.name.startsWith(DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX)
                }
            assertEquals(2, namedStackBranches.size)
        }
    }

    @Push
    @Test
    fun `generateUniqueStackName throws exception after max attempts`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            val commitSubject = "Collision test"
            val derivedName = StackNameGenerator.generateName(commitSubject)

            // Pre-create a branch with the derived name
            gitJaspr.push(stackName = derivedName)

            // Use a constant random that always produces the same suffix, causing repeated
            // collisions
            val constantRandom =
                object : Random() {
                    override fun nextBits(bitCount: Int): Int = 0
                }

            // Also pre-create the suffixed variant so every attempt collides
            val suffix = StackNameGenerator.generateSuffix(constantRandom)
            gitJaspr.push(stackName = "$derivedName-$suffix")

            val localRef = localGit.log("main", 1).single().hash
            val exception =
                assertThrows<GitJasprException> {
                    gitJaspr.generateUniqueStackName(
                        DEFAULT_TARGET_REF,
                        localRef,
                        commitSubject,
                        maxAttempts = 3,
                        random =
                            object : Random() {
                                override fun nextBits(bitCount: Int): Int = 0
                            },
                    )
                }

            assertContains(
                exception.message,
                "Failed to generate a unique stack name after 3 attempts",
            )
        }
    }

    // endregion

    // region pr body tests
    @PrBody
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

    @PrBody
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

    @PrBody
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
    @Merge
    @Test
    fun `merge empty stack`() {
        withTestSetup(useFakeRemote) { merge(RefSpec("main", "main")) }
    }

    @Merge
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

    @Merge
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
                    // will not "notice" that the commits should pass verification unless they are
                    // defined again as part of this pass. I should fix that, but this works for
                    // now.
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

    @Merge
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

    @Merge
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

    @Merge
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

    @Merge
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

    @Merge
    @Test
    fun `merge fails when not all commits are mergeable`() {
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
                }
            )

            waitForChecksToConclude("a", "b", "c")
            val exception =
                assertThrows<GitJasprException> { merge(RefSpec("development", "main")) }
            assertContains(exception.message, "Not all commits in the stack are mergeable")
        }
    }

    @Merge
    @Test
    fun `merge sets baseRef to targetRef on the last PR`() {
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

            waitForChecksToConclude("one", "two", "three", "four")

            merge(RefSpec("development", "main"))
            // After merging, all PRs should be closed/merged
            assertEventuallyEquals(emptyList()) { gitHub.getPullRequests().map(PullRequest::title) }
        }
    }

    @Merge
    @Test
    fun `merge closes all PRs when entire stack is merged`() {
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

            waitForChecksToConclude("one", "two", "three", "four", "five")

            merge(RefSpec("development", "main"))
            assertEventuallyEquals(emptyList()) { gitHub.getPullRequests().map(PullRequest::title) }
        }
    }

    @Merge
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

            assertThrows<GitJasprException> { merge(RefSpec("development", "main")) }
            assertEquals(
                listOf("one", "two", "three"),
                gitHub.getPullRequests().map(PullRequest::title),
            )
        }
    }

    @Merge
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

    @Merge
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
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name),
            )
        }
    }

    @Merge
    @Test
    fun `merge rebases remaining PRs after merge`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            remoteRefs += buildRemoteRef("1")
                            willPassVerification = true
                        }
                        commit {
                            title = "2"
                            remoteRefs += buildRemoteRef("2")
                            willPassVerification = true
                        }
                        commit {
                            title = "3"
                            localRefs += "dev1"
                            remoteRefs += buildRemoteRef("3")
                            willPassVerification = true
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("1")
                        baseRef = "main"
                        title = "1"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("2")
                        baseRef = buildRemoteRef("1")
                        title = "2"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("3")
                        baseRef = buildRemoteRef("2")
                        title = "3"
                        willBeApprovedByUserKey = "michael"
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
                gitHub
                    .getPullRequests()
                    .filter { it.headRefName == buildRemoteRef("c") }
                    .map { it.headRefName to it.baseRefName }
                    .toSet(),
            )
        }
    }

    @Merge
    @Test
    fun `merge rebases PRs targeting any merged branch not just the last`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            remoteRefs += buildRemoteRef("a")
                            willPassVerification = true
                        }
                        commit {
                            title = "b"
                            remoteRefs += buildRemoteRef("b")
                            willPassVerification = true
                        }
                        commit {
                            title = "c"
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("c")
                            willPassVerification = true
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
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            push()

            // Create a second stack with a single PR "d" whose base is commit "a"'s branch
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "a"
                            remoteRefs += buildRemoteRef("a")
                            willPassVerification = true
                        }
                        commit {
                            title = "d"
                            remoteRefs += buildRemoteRef("d")
                            willPassVerification = true
                        }
                        commit {
                            title = "d_HEAD"
                            localRefs += "dev2"
                            remoteRefs += buildRemoteRef("d_HEAD")
                            willPassVerification = true
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("d")
                        baseRef = buildRemoteRef("a")
                        title = "d"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("d_HEAD")
                        baseRef = buildRemoteRef("d")
                        title = "d_HEAD"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            localGit.checkout("dev2")
            push()

            waitForChecksToConclude("a", "b", "c", "d", "d^1", timeout = Long.MAX_VALUE)
            merge(RefSpec("development", "main"))

            // PR "d" targeted commit "a"'s branch. After merging the full stack (a, b, c),
            // PR "d" should be rebased to "main".
            assertEquals(
                "main",
                gitHub
                    .getPullRequests()
                    .single { it.headRefName == buildRemoteRef("d") }
                    .baseRefName,
            )
        }
    }

    @Merge
    @Test
    fun `merge with out of date commit fails`() {
        withTestSetup(useFakeRemote) {
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
            assertThrows<GitJasprException> { merge(RefSpec("development", "main")) }
        }
    }

    // endregion

    // region clean tests
    @Clean
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
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name),
            )
        }
    }

    @Clean
    @Test
    fun `getOrphanedBranches prunes stale tracking branches`() {
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

            // This could be cleaner. We need the remote branches gone so we can verify that
            // getOrphanedBranches does a fetch w/prune before returning results. The mechanism to
            // remove the remote branches depends on whether we're using a fake remote.
            val remoteBranchesToRemove = listOf(buildRemoteRef("c"), buildRemoteRef("c_01"))
            if (useFakeRemote) {
                remoteGit.deleteBranches(remoteBranchesToRemove, force = true)
            } else {
                localGit.push(
                    remoteBranchesToRemove.map { name -> RefSpec(FORCE_PUSH_PREFIX, name) },
                    remoteName,
                )
            }

            assertEquals(
                listOf(buildRemoteRef("b"), buildRemoteRef("b_01")),
                gitJaspr.getOrphanedBranches().sorted(),
            )
        }
    }

    @Clean
    @Test
    fun `clean dry run reports empty named stack branches`() {
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
                            localRefs += "dev"
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

            // Push two named stacks
            gitJaspr.push(stackName = "stack-one")

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
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )
            gitJaspr.push(stackName = "stack-two")

            // Merge all commits into main to make both stacks empty
            waitForChecksToConclude("one", "two", "three", "four")
            merge(RefSpec("dev", "main"))

            // Run clean with dry run
            gitJaspr.getCleanPlan()
            assertEquals(
                CleanPlan(
                    emptyNamedStackBranches =
                        sortedSetOf(
                            RemoteNamedStackRef("stack-one").name(),
                            RemoteNamedStackRef("stack-two").name(),
                        )
                ),
                gitJaspr.getCleanPlan(),
            )
        }
    }

    @Clean
    @Test
    fun `clean deletes empty named stack branches`() {
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
                            localRefs += "dev"
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
                }
            )

            // Push a first named stack
            gitJaspr.push(stackName = "stack-one")

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
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            // Push a second named stack
            gitJaspr.push(stackName = "stack-two")

            // Merge the first two stacks into the main branch (making them empty)
            waitForChecksToConclude("one", "two", "three")
            merge(RefSpec("dev", "main"))

            // Create one more commit and push a third stack that is NOT empty
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
                            localRefs += "dev"
                        }
                    }
                }
            )
            gitJaspr.push(stackName = "stack-three")

            // Verify all three stacks exist before clean
            val namedStackBranchesBeforeClean =
                localGit.getRemoteBranches(remoteName).filter { isNamedStackBranch(it) }

            assertEquals(3, namedStackBranchesBeforeClean.size)

            // Now run clean (not dry run)
            gitJaspr.clean(dryRun = false)

            // Verify only stack-three remains (stack-one and stack-two were deleted)
            val namedStackBranchesAfterClean =
                localGit
                    .getRemoteBranches(remoteName)
                    .filter { isNamedStackBranch(it) }
                    .map { it.name }

            assertEquals(1, namedStackBranchesAfterClean.size)
            assertTrue(namedStackBranchesAfterClean.single().contains("stack-three"))
        }
    }

    @Clean
    @Test
    fun `clean with abandoned PRs dry run reports them`() {
        withTestSetup(useFakeRemote) {
            // Create a named stack and merge it, so it's empty
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "will_merge_a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("will_merge_a")
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("will_merge_a")
                        baseRef = "main"
                        title = "will_merge_a"
                        willBeApprovedByUserKey = "michael"
                    }
                    checkout = "dev"
                }
            )

            gitJaspr.push(stackName = "empty_stack")
            waitForChecksToConclude("will_merge_a")
            merge(RefSpec("dev", "main"))

            // Create an orphaned commit (no PR)
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "will_orphan_a"
                            remoteRefs += buildRemoteRef("will_orphan_a")
                        }
                    }
                }
            )

            // Push the same stack twice, abandoning commit D the second time
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit { title = "D" }
                        commit {
                            title = "E"
                            localRefs += "dev"
                        }
                    }
                }
            )

            gitJaspr.push(stackName = "my-stack")

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        // D is dropped - its PR will be abandoned (unreachable by any named stack)
                        commit {
                            title = "E"
                            localRefs += "dev"
                        }
                    }
                }
            )

            gitJaspr.push(stackName = "my-stack")

            // Get the clean plan with cleanAbandonedPrs enabled
            val gitJasprWithCleanAbandoned =
                gitJaspr.clone { config -> config.copy(cleanAbandonedPrs = true) }
            assertEquals(
                CleanPlan(
                    orphanedBranches = sortedSetOf(buildRemoteRef("will_orphan_a")),
                    emptyNamedStackBranches =
                        sortedSetOf(RemoteNamedStackRef("empty_stack").name()),
                    abandonedBranches = sortedSetOf(buildRemoteRef("D")),
                ),
                gitJasprWithCleanAbandoned.getCleanPlan(),
            )

            // Run clean with dry run to ensure nothing is actually deleted
            gitJasprWithCleanAbandoned.clean(dryRun = true)

            // Verify PRs are still open (dry run doesn't close them)
            val prsAfterClean = gitHub.getPullRequests()
            assertEquals(5, prsAfterClean.size)

            // Verify D branch still exists
            val jasprBranchesAfterClean =
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot { isNamedStackBranch(it) }
                    .map { it.name }
            assertTrue(jasprBranchesAfterClean.contains(buildRemoteRef("D")))
        }
    }

    @Clean
    @Test
    fun `clean with abandoned PRs closes and deletes them`() {
        withTestSetup(useFakeRemote) {
            // Create a named stack and merge it, so it's empty
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "will_merge_a"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("will_merge_a")
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("will_merge_a")
                        baseRef = "main"
                        title = "will_merge_a"
                        willBeApprovedByUserKey = "michael"
                    }
                    checkout = "dev"
                }
            )

            gitJaspr.push(stackName = "empty_stack")
            waitForChecksToConclude("will_merge_a")
            merge(RefSpec("dev", "main"))

            // Create an orphaned commit (no PR)
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "will_orphan_a"
                            remoteRefs += buildRemoteRef("will_orphan_a")
                        }
                    }
                }
            )

            // Push the same stack twice, abandoning commit D the second time
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "will_merge_a" }
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit { title = "D" }
                        commit {
                            title = "E"
                            localRefs += "dev"
                        }
                    }
                }
            )

            gitJaspr.push(stackName = "my-stack")

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "will_merge_a"
                            willPassVerification = true
                        }
                        commit {
                            title = "A"
                            willPassVerification = true
                        }
                        commit {
                            title = "B"
                            willPassVerification = true
                        }
                        commit {
                            title = "C"
                            willPassVerification = true
                        }
                        // D is dropped - its PR will be abandoned (unreachable by any named stack)
                        commit {
                            title = "E"
                            willPassVerification = true
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("A")
                        baseRef = "main"
                        title = "A"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("B")
                        baseRef = buildRemoteRef("A")
                        title = "B"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("C")
                        baseRef = buildRemoteRef("B")
                        title = "C"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("E")
                        baseRef = buildRemoteRef("C")
                        title = "E"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            gitJaspr.push(stackName = "my-stack")
            assertEquals(
                listOf(
                        RemoteNamedStackRef("empty_stack").name(),
                        RemoteNamedStackRef("my-stack").name(),
                        buildRemoteRef("A"),
                        buildRemoteRef("B"),
                        buildRemoteRef("C"),
                        buildRemoteRef("D"),
                        buildRemoteRef("E"),
                        buildRemoteRef("E_01"),
                        buildRemoteRef("will_orphan_a"),
                        "main",
                    )
                    .toSet(),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name).toSet(),
            )

            val gitJasprWithCleanAbandoned =
                gitJaspr.clone { config -> config.copy(cleanAbandonedPrs = true) }
            gitJasprWithCleanAbandoned.clean(dryRun = false)

            assertEquals(
                listOf(
                        RemoteNamedStackRef("my-stack").name(),
                        buildRemoteRef("A"),
                        buildRemoteRef("B"),
                        buildRemoteRef("C"),
                        buildRemoteRef("E"),
                        buildRemoteRef("E_01"),
                        "main",
                    )
                    .toSet(),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name).toSet(),
            )

            waitForChecksToConclude("A", "B", "C", "E")
            merge(RefSpec("dev", "main"))

            assertEquals(
                listOf(RemoteNamedStackRef("my-stack").name(), "main").toSet(),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name).toSet(),
            )

            gitJasprWithCleanAbandoned.clean(dryRun = false)

            assertEquals(
                listOf("main"),
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name),
            )
        }
    }

    @Clean
    @Test
    fun `clean only considers jaspr branches as abandoned`() {
        withTestSetup(useFakeRemote) {
            // Create a jaspr branch with an open PR
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "jaspr_commit"
                            remoteRefs += buildRemoteRef("jaspr_commit")
                            localRefs += "dev"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("jaspr_commit")
                        baseRef = "main"
                        title = "jaspr_commit"
                    }
                    checkout = "dev"
                }
            )

            // Push a named stack so we have something to track
            gitJaspr.push(stackName = "my-stack")

            // Create a non-jaspr branch with an open PR manually (not through jaspr)
            localGit.checkout("main")
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "non_jaspr_commit"
                            remoteRefs += "non-jaspr-branch"
                            localRefs += "non-jaspr-branch"
                        }
                    }
                }
            )

            // Create a PR for the non-jaspr branch
            gitHub.createPullRequest(
                PullRequest(
                    id = null,
                    commitId = null,
                    number = null,
                    headRefName = "non-jaspr-branch",
                    baseRefName = "main",
                    title = "Non-Jaspr PR",
                    body = "This is a body",
                )
            )

            // Push another stack that doesn't include the jaspr commit (making it abandoned)
            localGit.checkout("dev")
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "new_commit"
                            localRefs += "dev"
                        }
                    }
                }
            )

            gitJaspr.push(stackName = "my-stack")

            // Get the clean plan with cleanAbandonedPrs enabled
            val gitJasprWithCleanAbandoned =
                gitJaspr.clone { config -> config.copy(cleanAbandonedPrs = true) }
            val cleanPlan = gitJasprWithCleanAbandoned.getCleanPlan()

            // The jaspr branch should be in abandonedBranches
            assertTrue(
                cleanPlan.abandonedBranches.contains(buildRemoteRef("jaspr_commit")),
                "Jaspr branch should be considered abandoned",
            )

            // The non-jaspr branch should NOT be in abandonedBranches
            assertFalse(
                cleanPlan.abandonedBranches.contains("non-jaspr-branch"),
                "Non-jaspr branch should NOT be considered abandoned",
            )

            // Verify both PRs still exist
            val allPrs = gitHub.getPullRequests()
            assertEquals(3, allPrs.size) // jaspr_commit, new_commit, and non-jaspr-branch
        }
    }

    @Clean
    @Test
    fun `clean respects commit ownership for orphaned branches`() {
        withTestSetup(useFakeRemote) {
            // Create commits with the default user
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                            remoteRefs += buildRemoteRef("A")
                        }
                        commit {
                            title = "B"
                            remoteRefs += buildRemoteRef("B")
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            // Create commits with the other user
            localGit.setConfigValue("user.name", "Other User")
            localGit.setConfigValue("user.email", "other@example.com")
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "X"
                            remoteRefs += buildRemoteRef("X")
                            committer {
                                name = "Other User"
                                email = "other@example.com"
                            }
                        }
                        commit {
                            title = "Y"
                            remoteRefs += buildRemoteRef("Y")
                            localRefs += "dev"
                            committer {
                                name = "Other User"
                                email = "other@example.com"
                            }
                        }
                    }
                    checkout = "dev"
                }
            )

            // Switch back to the original user
            localGit.setConfigValue("user.name", DEFAULT_COMMITTER.name)
            localGit.setConfigValue("user.email", DEFAULT_COMMITTER.email)

            // Get orphaned branches - should return ALL orphaned branches regardless of ownership
            val orphanedBranches = gitJaspr.getOrphanedBranches()
            assertEquals(
                setOf(
                    buildRemoteRef("A"),
                    buildRemoteRef("B"),
                    buildRemoteRef("X"),
                    buildRemoteRef("Y"),
                ),
                orphanedBranches.toSet(),
            )

            // Get clean plan - should only include branches owned by the current user
            val cleanPlan = gitJaspr.getCleanPlan()
            assertEquals(
                setOf(buildRemoteRef("A"), buildRemoteRef("B")),
                cleanPlan.orphanedBranches,
            )
        }
    }

    @Clean
    @Test
    fun `clean with cleanAllCommits true ignores commit ownership`() {
        withTestSetup(useFakeRemote) {
            // Create commits with the default user
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                            remoteRefs += buildRemoteRef("A")
                        }
                        commit {
                            title = "B"
                            remoteRefs += buildRemoteRef("B")
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            // Create commits with another user
            localGit.setConfigValue("user.name", "Other User")
            localGit.setConfigValue("user.email", "other@example.com")
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "B" }
                        commit {
                            title = "X"
                            remoteRefs += buildRemoteRef("X")
                            committer {
                                name = "Other User"
                                email = "other@example.com"
                            }
                        }
                        commit {
                            title = "Y"
                            remoteRefs += buildRemoteRef("Y")
                            localRefs += "dev"
                            committer {
                                name = "Other User"
                                email = "other@example.com"
                            }
                        }
                    }
                    checkout = "dev"
                }
            )

            // Switch back to the original user
            localGit.setConfigValue("user.name", DEFAULT_COMMITTER.name)
            localGit.setConfigValue("user.email", DEFAULT_COMMITTER.email)

            // Get orphaned branches - should return ALL regardless of the cleanAllCommits setting
            val orphanedBranches = gitJaspr.getOrphanedBranches()
            assertEquals(
                setOf(
                    buildRemoteRef("A"),
                    buildRemoteRef("B"),
                    buildRemoteRef("X"),
                    buildRemoteRef("Y"),
                ),
                orphanedBranches.toSet(),
            )

            // Get the clean plan with cleanAllCommits = false - should only include owned branches
            val cleanPlan = gitJaspr.getCleanPlan()
            assertEquals(
                setOf(buildRemoteRef("A"), buildRemoteRef("B")),
                cleanPlan.orphanedBranches,
            )

            // Get the clean plan with cleanAllCommits = true - should include all branches
            val gitJasprWithCleanAll =
                gitJaspr.clone { config -> config.copy(cleanAllCommits = true) }
            val cleanPlanAll = gitJasprWithCleanAll.getCleanPlan()
            assertEquals(
                setOf(
                    buildRemoteRef("A"),
                    buildRemoteRef("B"),
                    buildRemoteRef("X"),
                    buildRemoteRef("Y"),
                ),
                cleanPlanAll.orphanedBranches,
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
    // the header, I want these tests to break so that any such changes are very deliberate. This is
    // a compromise between referencing the same value from both tests and prod and the other
    // extreme of repeating this header text manually in every test.
    private fun String.toStatusString(
        actual: String,
        namedStackInfo: NamedStackInfo? = null,
    ): String {
        // Extract commit hashes and URLs from the actual string and put them into the expected. I
        // can't predict what they will be, so I only want to validate that they are present.
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
            } catch (_: MissingFormatArgumentException) {
                logger.error(
                    "toStatusString: format string doesn't have enough arguments, should have {}",
                    extracts.size,
                )
                this
            }
        val namedStackInfoString = buildString {
            // As above, this duplicates the string building logic defined in GitJaspr, but this is
            // so any changes to the rendering is done very deliberately.
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
            } catch (_: MissingFormatArgumentException) {
                logger.error(
                    "toPrBodyString: format string doesn't have enough arguments, should have {}",
                    list.size,
                )
                this
            }
        return "$formattedString\n" +
            "⚠️ *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). " +
            "Do not merge manually using the UI - doing so may have unexpected results.*\n"
    }

    // region dont-push tests
    @DontPush
    @Test
    fun `push excludes commits matching dont-push pattern`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "DONT PUSH: three"
                            id = "three"
                        }
                        commit {
                            title = "four"
                            localRefs += "main"
                        }
                    }
                }
            )
            push()

            // Only commits one and two should be pushed
            assertEquals(
                listOf("one", "two").map { buildRemoteRef(it) },
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @DontPush
    @Test
    fun `push excludes all commits when all match dont-push pattern`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "dont-push one"
                            id = "one"
                        }
                        commit {
                            title = "DONT PUSH two"
                            id = "two"
                        }
                        commit {
                            title = "dont push: three"
                            id = "three"
                            localRefs += "main"
                        }
                    }
                }
            )
            push()

            // No commits should be pushed
            assertEquals(
                emptyList(),
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @DontPush
    @Test
    fun `push excludes all commits when base commit matches dont-push pattern`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Dont push this"
                            id = "one"
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "main"
                        }
                    }
                }
            )
            push()

            // No commits should be pushed
            assertEquals(
                emptyList(),
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @DontPush
    @Test
    fun `push named stack points to topmost non-excluded commit`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "dont-push three"
                            id = "three"
                        }
                        commit {
                            title = "four"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )
            gitJaspr.push(stackName = "my-stack")

            // Named stack should point to commit "two"
            val namedStackBranch =
                localGit.getRemoteBranches(remoteName).first {
                    it.name ==
                        "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/my-stack"
                }
            val twoCommit = localGit.log().first { it.shortMessage.startsWith("two") }
            assertEquals(twoCommit.hash, namedStackBranch.commit.hash)
        }
    }

    @DontPush
    @Test
    fun `merge excludes commits matching dont-push pattern`() {
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
                            title = "dont push three"
                            id = "three"
                        }
                        commit {
                            title = "four"
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
                }
            )

            waitForChecksToConclude("one", "two")
            merge(RefSpec("development", "main"))

            // Only commits one and two should be merged
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(2, stack.size)
            assertTrue(stack.any { it.shortMessage.startsWith("dont push three") })
            assertTrue(stack.any { it.shortMessage.startsWith("four") })
        }
    }

    @DontPush
    @Test
    fun `merge with explicit refspec excludes commits matching dont-push pattern`() {
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
                            title = "Dont-push two"
                            id = "two"
                            localRefs += "development"
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

            // Commit one should be merged, but "Dont-push two" should remain
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(1, stack.size)
            assertTrue(stack.any { it.shortMessage.startsWith("Dont-push two") })
        }
    }

    @DontPush
    @Test
    fun `autoMerge with explicit refspec excludes commits matching dont-push pattern`() {
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
                            title = "DONT PUSH: three"
                            id = "three"
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
                }
            )

            waitForChecksToConclude("one", "two")
            autoMerge(RefSpec("development", "main"))

            // Commits one and two should be merged, but "DONT PUSH: three" should remain
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(1, stack.size)
            assertTrue(stack.any { it.shortMessage.startsWith("DONT PUSH: three") })
        }
    }

    @DontPush
    @Test
    fun `push respects custom dont-push regex pattern`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "WIP: two"
                            id = "two"
                        }
                        commit {
                            title = "three"
                            localRefs += "main"
                        }
                    }
                }
            )
            gitJaspr.clone { config -> config.copy(dontPushRegex = "^(wip)\\b.*$") }.push()

            // Only commit one should be pushed ("WIP: two" and "three" are excluded)
            assertEquals(
                listOf("one").map { buildRemoteRef(it) },
                localGit
                    .getRemoteBranches(remoteName)
                    .filterNot(::isNamedStackBranch)
                    .map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @DontPush
    @Test
    fun `autoMerge merges mergeable commits and stops at draft commit in middle of stack`() {
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
                            title = "draft: three"
                            id = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
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
                        title = "draft: three"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                }
            )

            waitForChecksToConclude("one", "two", "three", "four")
            autoMerge(RefSpec("development", "main"))

            // Commits one and two should be merged, but draft:three and four should remain
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(2, stack.size)
            assertTrue(stack.any { it.shortMessage.startsWith("draft: three") })
            assertTrue(stack.any { it.shortMessage.startsWith("four") })
        }
    }

    @DontPush
    @Test
    fun `autoMerge merges up to last mergeable commit when isDraft is true`() {
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
                            title = "WIP: three"
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
                        title = "WIP: three"
                    }
                }
            )

            waitForChecksToConclude("one", "two", "three")
            autoMerge(RefSpec("development", "main"))

            // Commits one and two should be merged, but WIP:three should remain
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(1, stack.size)
            assertTrue(stack.any { it.shortMessage.startsWith("WIP: three") })
        }
    }

    // endregion

    // region multiple prs tests
    // These tests verify that jaspr ignores PRs with base refs that don't match the target ref
    // encoded in the jaspr branch name. This can happen when someone manually creates a PR
    // outside jaspr using the same jaspr branch as the head ref.

    // Note that each test in this region has a different test tag. This is intentional.

    @Status
    @Test
    fun `status ignores PR with non-matching base ref`() {
        withTestSetup(useFakeRemote) {
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
                            localRefs += "development"
                        }
                    }
                    // Normal jaspr PR targeting main
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
                    // Extra PR created outside jaspr with different base ref
                    // This simulates someone manually creating a PR from the jaspr branch
                    // to a different target branch
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "some-other-branch"
                        title = "two - manual PR to other branch"
                    }
                }
            )

            waitForChecksToConclude("one", "two")

            // Status should succeed without throwing SinglePullRequestPerCommitConstraintViolation
            val actual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(actual),
                actual,
            )
        }
    }

    @Push
    @Test
    fun `push ignores PR with non-matching base ref`() {
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
                            remoteRefs += buildRemoteRef("two")
                            localRefs += "development"
                        }
                    }
                    // Normal jaspr PR
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
                    // Extra PR created outside jaspr with different base ref
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "some-other-branch"
                        title = "two - manual PR to other branch"
                    }
                }
            )

            // Push should succeed without throwing SinglePullRequestPerCommitConstraintViolation
            push()

            // Verify the stack was pushed correctly
            assertEquals(
                setOf("jaspr/main/one -> main", "jaspr/main/two -> jaspr/main/one"),
                gitHub
                    .getPullRequests()
                    .filter { it.baseRefName == "main" || it.baseRefName.startsWith("jaspr/main/") }
                    .map(PullRequest::headToBaseString)
                    .toSet(),
            )
        }
    }

    @Push
    @Test
    fun `push with positive count limits commits pushed`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit {
                            title = "four"
                            localRefs += "main"
                        }
                    }
                }
            )
            push(count = 2)

            assertEquals(
                setOf(buildRemoteRef("one"), buildRemoteRef("two")),
                (localGit
                        .getRemoteBranches(remoteName)
                        .filterNot(::isNamedStackBranch)
                        .map(RemoteBranch::name) - DEFAULT_TARGET_REF)
                    .toSet(),
            )
        }
    }

    @Push
    @Test
    fun `push with negative count excludes commits from top`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit { title = "three" }
                        commit {
                            title = "four"
                            localRefs += "main"
                        }
                    }
                }
            )
            push(count = -1)

            assertEquals(
                setOf(buildRemoteRef("one"), buildRemoteRef("two"), buildRemoteRef("three")),
                (localGit
                        .getRemoteBranches(remoteName)
                        .filterNot(::isNamedStackBranch)
                        .map(RemoteBranch::name) - DEFAULT_TARGET_REF)
                    .toSet(),
            )
        }
    }

    @Push
    @Test
    fun `push with count exceeding stack size fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                }
            )
            assertThrows<IllegalArgumentException> { push(count = 5) }
        }
    }

    @Push
    @Test
    fun `push with negative count resulting in zero fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                }
            )
            assertThrows<IllegalArgumentException> { push(count = -2) }
        }
    }

    @Push
    @Test
    fun `push with zero count fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                }
            )
            assertThrows<IllegalArgumentException> { push(count = 0) }
        }
    }

    @Merge
    @Test
    fun `merge ignores PR with non-matching base ref`() {
        withTestSetup(useFakeRemote) {
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
                            localRefs += "development"
                        }
                    }
                    // Normal jaspr PRs
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
                    // Extra PR created outside jaspr with different base ref
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "some-other-branch"
                        title = "two - manual PR to other branch"
                    }
                }
            )

            waitForChecksToConclude("one", "two")

            // Merge should succeed without throwing SinglePullRequestPerCommitConstraintViolation
            merge(RefSpec("development", "main"))

            // Verify the commits were merged
            val stack = localGit.getLocalCommitStack(remoteName, "development", DEFAULT_TARGET_REF)
            assertEquals(0, stack.size)
        }
    }

    @Clean
    @Test
    fun `clean ignores PR with non-matching base ref`() {
        withTestSetup(useFakeRemote) {
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
                            localRefs += "development"
                        }
                    }
                    // Normal jaspr PRs
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
                    // Extra PR created outside jaspr with different base ref
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "some-other-branch"
                        title = "two - manual PR to other branch"
                    }
                }
            )

            // Push to create named stack
            gitJaspr.push(stackName = "my-stack")

            waitForChecksToConclude("one", "two")

            // Merge to make the stack empty
            merge(RefSpec("development", "main"))

            // Clean should succeed without errors
            // The PR with non-matching base ref should be ignored (not closed)
            val cleanPlan = gitJaspr.getCleanPlan()

            // The clean plan should only include the empty named stack branch
            // and the orphaned jaspr branches, but NOT consider the foreign PR as abandoned
            assertEquals(
                sortedSetOf(RemoteNamedStackRef("my-stack").name()),
                cleanPlan.emptyNamedStackBranches,
            )
        }
    }

    // endregion

    // region checkout tests

    @Checkout
    @Test
    fun `checkout - push then checkout by name`() {
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
                    checkout = "main"
                }
            )

            val stackName = "my-stack"
            gitJaspr.push(stackName = stackName)

            // Switch to a different branch
            localGit.checkout("main")

            // Checkout the named stack
            checkout(stackName)

            assertEquals(stackName, localGit.getCurrentBranchName())
            val upstream = localGit.getUpstreamBranch(remoteName)
            assertEquals(RemoteNamedStackRef(stackName).name(), upstream?.name)
        }
    }

    @Checkout
    @Test
    fun `checkout - checkout non-existent stack fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            gitJaspr.push(stackName = "real-stack")

            assertThrows<IllegalStateException> { checkout("nonexistent") }
        }
    }

    @Checkout
    @Test
    fun `checkout - checkout with conflicting local branch fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            val stackName = "my-stack"
            gitJaspr.push(stackName = stackName)

            // Create a local branch with the same name but no upstream
            localGit.branch(stackName)

            assertThrows<GitJasprException> { checkout(stackName) }
        }
    }

    // endregion

    private fun isNamedStackBranch(branch: RemoteBranch): Boolean {
        return branch.name.startsWith(DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX)
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
