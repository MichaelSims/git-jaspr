package sims.michael.gitjaspr

import java.util.MissingFormatArgumentException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
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
import sims.michael.gitjaspr.testing.Compare
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import sims.michael.gitjaspr.testing.DontPush
import sims.michael.gitjaspr.testing.Graph
import sims.michael.gitjaspr.testing.Merge
import sims.michael.gitjaspr.testing.Nav
import sims.michael.gitjaspr.testing.PrBody
import sims.michael.gitjaspr.testing.Pull
import sims.michael.gitjaspr.testing.Push
import sims.michael.gitjaspr.testing.Stack
import sims.michael.gitjaspr.testing.Status
import sims.michael.gitjaspr.testing.Sync

interface GitJasprTest {

    val logger: Logger
    val useFakeRemote: Boolean
        get() = true

    suspend fun GitHubTestHarness.push(stackName: String? = "test-stack", count: Int? = null) =
        gitJaspr.push(stackName = stackName, count = count)

    suspend fun GitHubTestHarness.getAndPrintStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ) = gitJaspr.getStatusString(refSpec).also(::print)

    suspend fun GitHubTestHarness.getAndPrintCompareString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ) = gitJaspr.getCompareString(refSpec).also(::print)

    fun GitHubTestHarness.pull(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theirs: Boolean = false,
    ) = gitJaspr.pull(refSpec, theirs).also(::print)

    fun GitHubTestHarness.graphRefs(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ) = gitJaspr.graphRefs(refSpec)

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

    fun GitHubTestHarness.renameStack(oldName: String, newName: String) =
        gitJaspr.renameStack(oldName, newName, DEFAULT_TARGET_REF)

    fun GitHubTestHarness.deleteStack(name: String) = gitJaspr.deleteStack(name, DEFAULT_TARGET_REF)

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

    // region nav state tests
    @Nav
    @Test
    fun `nav state round-trips through write and read`() {
        withTestSetup(useFakeRemote) {
            val state =
                NavState(
                    headBeforeDetach = "my-feature",
                    stack =
                        listOf(
                            StackEntry(sha = "abc123", commitId = "id-1"),
                            StackEntry(sha = "def456", commitId = "id-2"),
                        ),
                    cursorIndex = 0,
                )
            gitJaspr.writeNavState(state)
            assertEquals(state, gitJaspr.readNavState())
        }
    }

    @Nav
    @Test
    fun `readNavState returns null when no state file exists`() {
        withTestSetup(useFakeRemote) { assertNull(gitJaspr.readNavState()) }
    }

    @Nav
    @Test
    fun `clearNavState removes state file`() {
        withTestSetup(useFakeRemote) {
            val state =
                NavState(
                    headBeforeDetach = "my-feature",
                    stack =
                        listOf(
                            StackEntry(sha = "abc123", commitId = "id-1"),
                            StackEntry(sha = "def456", commitId = "id-2"),
                        ),
                    cursorIndex = 0,
                )
            gitJaspr.writeNavState(state)
            gitJaspr.clearNavState()
            assertNull(gitJaspr.readNavState())
        }
    }

    // endregion

    // region navigation tests
    @Nav
    @Test
    fun `down detaches HEAD and writes nav state`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(3, stack.size)

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)

            assertTrue(localGit.isHeadDetached())
            assertEquals(stack[1].hash, localGit.log(GitClient.HEAD, 1).single().hash)

            val state = gitJaspr.readNavState()
            assertNotNull(state)
            assertEquals("development", state.headBeforeDetach)
            assertEquals(3, state.stack.size)
            assertEquals(1, state.cursorIndex)
            assertEquals(stack[1].hash, state.stack[1].sha)
        }
    }

    @Nav
    @Test
    fun `down N navigates multiple commits`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            assertEquals(stack[0].hash, localGit.log(GitClient.HEAD, 1).single().hash)
        }
    }

    @Nav
    @Test
    fun `down past bottom of stack fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            assertThrows<IllegalArgumentException> { gitJaspr.navigateDown(DEFAULT_TARGET_REF, 3) }
        }
    }

    @Nav
    @Test
    fun `down with commit missing jaspr commit ID throws GitJasprException`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            id = "" // no commit-id footer
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            val exception =
                assertThrows<GitJasprException> { gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1) }
            assertContains(exception.message, "has no jaspr commit ID")
            assertContains(exception.message, "Run jaspr push")
        }
    }

    @Nav
    @Test
    fun `bottom navigates to first commit in stack`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            gitJaspr.navigateToBottom(DEFAULT_TARGET_REF)

            assertTrue(localGit.isHeadDetached())
            assertEquals(stack.first().hash, localGit.log(GitClient.HEAD, 1).single().hash)
        }
    }

    @Nav
    @Test
    fun `down within active nav session updates cursor index`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            val firstState = gitJaspr.readNavState()

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            val secondState = gitJaspr.readNavState()

            // Source branch and stack should be preserved from first navigation
            assertNotNull(firstState)
            assertNotNull(secondState)
            assertEquals(firstState.headBeforeDetach, secondState.headBeforeDetach)
            assertEquals(
                firstState.stack.map(StackEntry::commitId),
                secondState.stack.map(StackEntry::commitId),
            )
            // Cursor should have moved down
            assertEquals(1, firstState.cursorIndex)
            assertEquals(0, secondState.cursorIndex)
            assertEquals(stack[0].hash, localGit.log(GitClient.HEAD, 1).single().hash)
        }
    }

    @Test
    fun `up checks out next existing commit when no amend has occurred`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            // Navigate down 2, then up 1
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals(stack[0].hash, localGit.log(GitClient.HEAD, 1).single().hash)

            gitJaspr.navigateUp(1)

            // HEAD should be the original "two" SHA -- a plain checkout, not a cherry-pick
            assertTrue(localGit.isHeadDetached())
            assertEquals(stack[1].hash, localGit.log(GitClient.HEAD, 1).single().hash)
            assertNotNull(gitJaspr.readNavState())
        }
    }

    @Test
    fun `top checks out remaining commits when no amend has occurred`() {
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
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val originalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            // Navigate to bottom, then back to top
            gitJaspr.navigateToBottom(DEFAULT_TARGET_REF)
            gitJaspr.navigateToTop()

            // Should be back on the branch with all commits replayed
            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertNull(gitJaspr.readNavState())

            // Stack should still have the same 3 commits at the SAME SHAs (plain checkouts,
            // not cherry-picks) since nothing was amended.
            val newStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(originalStack.map(Commit::hash), newStack.map(Commit::hash))
        }
    }

    @Test
    fun `up with no active session is a no-op`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            assertFalse(gitJaspr.navigateUp(1))
            assertEquals("development", localGit.getCurrentBranchName())
        }
    }

    @Test
    fun `top with no active session is a no-op`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            assertFalse(gitJaspr.navigateToTop())
            assertEquals("development", localGit.getCurrentBranchName())
        }
    }

    @Nav
    @Test
    fun `new commit during nav session is inserted into stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Create a new commit E on top of B
            localRepo.resolve("new_file.txt").writeText("inserted commit\n")
            localGit.add("new_file.txt")
            localGit.commit("E", footerLines = mapOf(COMMIT_ID_LABEL to "E"))

            // Nav up 1: reconciliation detects E, cherry-picks C
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)
            assertEquals("C", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav up 1: cherry-picks D, restores branch
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)
            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertNull(gitJaspr.readNavState())

            // Final stack should be A -> B -> E -> C -> D
            val finalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "B", "E", "C", "D"), finalStack.map(Commit::shortMessage))
        }
    }

    @Nav
    @Test
    fun `hard reset during nav session moves removed commits to replay queue`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Hard reset to A (removing B from materialized commits)
            localGit.reset("HEAD~1")

            // Nav up 1: reconciliation detects B is missing, prepends to replay queue.
            // Replay queue was [C, D], now [B, C, D]. Cherry-picks B.
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav to top: cherry-picks C and D, restores branch
            gitJaspr.navigateToTop(DEFAULT_TARGET_REF)
            assertFalse(localGit.isHeadDetached())

            // Final stack should be A -> B -> C -> D (same order, new SHAs)
            val finalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "B", "C", "D"), finalStack.map(Commit::shortMessage))
        }
    }

    @Nav
    @Test
    fun `amend during nav session updates SHA in stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 1: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)

            // Amend B (fix a typo — same commit ID, different SHA)
            localRepo.resolve("amend_fix.txt").writeText("amended content\n")
            localGit.add("amend_fix.txt")
            localGit.commit("B", footerLines = mapOf(COMMIT_ID_LABEL to "B"), amend = true)

            // Nav down 1: reconciliation should detect the amended SHA
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            assertEquals("A", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav up 1: should check out the amended B' (its git parent A still matches HEAD,
            // so no cherry-pick is required)
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)
            val replayedB = localGit.log(GitClient.HEAD, 1).single()
            assertEquals("B", replayedB.shortMessage)

            // The replayed B should contain the amended content
            assertTrue(localRepo.resolve("amend_fix.txt").readText().contains("amended content"))
        }
    }

    @Nav
    @Test
    fun `up cherry-picks remaining commits when amend exists below the cursor`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            val originalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            // Bottom -> HEAD at A
            gitJaspr.navigateToBottom(DEFAULT_TARGET_REF)
            assertEquals(originalStack[0].hash, localGit.log(GitClient.HEAD, 1).single().hash)

            // Amend A: changes its SHA, so subsequent entries' stored parents (the original A)
            // no longer match HEAD. Replay must cherry-pick B and C onto the amended chain.
            localRepo.resolve("amend.txt").writeText("amended\n")
            localGit.add("amend.txt")
            localGit.commit("A", footerLines = mapOf(COMMIT_ID_LABEL to "A"), amend = true)

            // Up 2 reaches the top and ends the session, restoring "development" to the new tip.
            gitJaspr.navigateUp(2, DEFAULT_TARGET_REF)

            val newStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "B", "C"), newStack.map(Commit::shortMessage))
            for (i in originalStack.indices) {
                assertNotEquals(
                    originalStack[i].hash,
                    newStack[i].hash,
                    "Entry $i should have a rewritten SHA",
                )
            }
            // Amend propagated through the cherry-picked B and C.
            assertTrue(localRepo.resolve("amend.txt").readText().contains("amended"))
        }
    }

    @Nav
    @Test
    fun `top cherry-picks remaining commits when amend exists below the cursor`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            val originalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            gitJaspr.navigateToBottom(DEFAULT_TARGET_REF)

            // Amend A
            localRepo.resolve("amend.txt").writeText("amended\n")
            localGit.add("amend.txt")
            localGit.commit("A", footerLines = mapOf(COMMIT_ID_LABEL to "A"), amend = true)

            gitJaspr.navigateToTop(DEFAULT_TARGET_REF)

            val newStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "B", "C"), newStack.map(Commit::shortMessage))
            for (i in originalStack.indices) {
                assertNotEquals(
                    originalStack[i].hash,
                    newStack[i].hash,
                    "Entry $i should have a rewritten SHA",
                )
            }
            assertTrue(localRepo.resolve("amend.txt").readText().contains("amended"))
        }
    }

    @Nav
    @Test
    fun `drop during nav session removes commit from stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Drop B (removes from stack entirely, HEAD moves to A)
            gitJaspr.drop(1, DEFAULT_TARGET_REF)
            assertEquals("A", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav state should still exist with B removed
            val state = gitJaspr.readNavState()
            assertNotNull(state)
            assertEquals(3, state.stack.size) // A, C, D (B is gone)
            assertEquals(0, state.cursorIndex) // at A

            // Nav up 1: cherry-picks C (B was dropped, not in replay queue)
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)
            assertEquals("C", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav to top: cherry-picks D, restores branch
            gitJaspr.navigateToTop(DEFAULT_TARGET_REF)
            assertFalse(localGit.isHeadDetached())

            // Final stack should be A -> C -> D (B was dropped)
            val finalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "C", "D"), finalStack.map(Commit::shortMessage))
        }
    }

    @Nav
    @Test
    fun `drop without nav session resets HEAD`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            gitJaspr.drop(1, DEFAULT_TARGET_REF)

            // Should still be on the branch, HEAD at B
            assertFalse(localGit.isHeadDetached())
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)
            assertNull(gitJaspr.readNavState())
        }
    }

    @Nav
    @Test
    fun `active nav session is detectable for edit guard`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Before nav: no session
            assertNull(gitJaspr.readNavState())
            assertFalse(localGit.isHeadDetached())

            // Navigate down: session is active
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            assertNotNull(gitJaspr.readNavState())
            assertTrue(localGit.isHeadDetached())

            // Navigate to top: session ends
            gitJaspr.navigateToTop(DEFAULT_TARGET_REF)
            assertNull(gitJaspr.readNavState())
            assertFalse(localGit.isHeadDetached())
        }
    }

    @Nav
    @Test
    fun `finish ends session keeping only commits below cursor`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B, replay queue is [C, D]
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Finish: discard C and D, update "development" to B
            val discarded = gitJaspr.finishNavSession()

            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertNull(gitJaspr.readNavState())

            // Should report C and D as discarded
            assertEquals(listOf("C", "D"), discarded.map(StackEntry::commitId))

            // Stack should now be just A -> B
            val finalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(listOf("A", "B"), finalStack.map(Commit::shortMessage))
        }
    }

    @Nav
    @Test
    fun `cancel restores original branch and reports orphaned commits`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)
            val originalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            val originalTipHash = originalStack.last().hash

            // Nav down 2: HEAD at A
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            // Create a new commit while navigating
            localRepo.resolve("new_file.txt").writeText("new content\n")
            localGit.add("new_file.txt")
            localGit.commit("NEW", footerLines = mapOf(COMMIT_ID_LABEL to "NEW"))
            val newCommitHash = localGit.log(GitClient.HEAD, 1).single().hash

            // Cancel: restore "development" to its original position
            val orphaned = gitJaspr.cancelNavSession()

            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertNull(gitJaspr.readNavState())

            // Branch should be back at the original tip
            assertEquals(originalTipHash, localGit.log(GitClient.HEAD, 1).single().hash)

            // The new commit should be reported as orphaned
            assertContains(orphaned, newCommitHash)
        }
    }

    @Nav
    @Test
    fun `cancel with no changes reports no orphaned commits`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 1: HEAD at B, no new commits
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)

            val orphaned = gitJaspr.cancelNavSession()

            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertTrue(orphaned.isEmpty())
        }
    }

    @Nav
    @Test
    fun `nav session installs post-checkout hook and removes it on cancel`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val hook = localRepo.resolve(".git/hooks/post-checkout")
            assertFalse(hook.exists())

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            assertTrue(hook.exists())
            assertTrue(hook.canExecute())
            assertContains(hook.readText(), "JASPR-NAV-HOOK-BEGIN")

            gitJaspr.cancelNavSession()
            assertFalse(hook.exists())
        }
    }

    @Nav
    @Test
    fun `nav session preserves pre-existing post-checkout hook content`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val hook = localRepo.resolve(".git/hooks/post-checkout")
            hook.parentFile.mkdirs()
            val userHook =
                """
                #!/bin/sh
                # User's existing post-checkout hook
                echo "user hook ran"
                """
                    .trimIndent() + "\n"
            hook.writeText(userHook)
            hook.setExecutable(true)

            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            val withJaspr = hook.readText()
            assertContains(withJaspr, "user hook ran")
            assertContains(withJaspr, "JASPR-NAV-HOOK-BEGIN")

            gitJaspr.cancelNavSession()
            assertTrue(hook.exists())
            val afterCancel = hook.readText()
            assertContains(afterCancel, "user hook ran")
            assertFalse(afterCancel.contains("JASPR-NAV-HOOK-BEGIN"))
        }
    }

    @Nav
    @Test
    fun `cancel during split discards split state and working tree changes`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            val originalTipHash = localGit.log(GitClient.HEAD, 1).single().hash

            // Nav down 1: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)
            // Split B: working tree now has B's changes; an untracked file is also added
            gitJaspr.split()
            localRepo.resolve("untracked.txt").writeText("scratch\n")
            assertNotNull(gitJaspr.readSplitState())

            gitJaspr.cancelNavSession()

            assertFalse(localGit.isHeadDetached())
            assertEquals("development", localGit.getCurrentBranchName())
            assertNull(gitJaspr.readNavState())
            assertNull(gitJaspr.readSplitState())
            assertEquals(originalTipHash, localGit.log(GitClient.HEAD, 1).single().hash)
            assertFalse(localGit.hasUncommittedChangesToTrackedFiles())
            assertFalse(localRepo.resolve("untracked.txt").exists())
        }
    }

    // region split tests

    @Nav
    @Test
    fun `split resets HEAD commit and leaves changes in working tree`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Split the top commit (no nav session)
            val subject = gitJaspr.split()
            assertEquals("B", subject)

            // HEAD should now be at A
            assertEquals("A", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Split state should exist
            assertNotNull(gitJaspr.readSplitState())

            // B's file should still be in the working tree
            assertTrue(localRepo.resolve("B.txt").exists())
        }
    }

    @Nav
    @Test
    fun `split during nav session removes commit from stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Split B
            gitJaspr.split()

            // HEAD should be at A
            assertEquals("A", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Nav state should have B removed, cursor at 0 (A)
            val state = gitJaspr.readNavState()
            assertNotNull(state)
            assertEquals(0, state.cursorIndex)
            assertEquals(listOf("A", "C", "D"), state.stack.map(StackEntry::commitId))

            // Split state should exist
            assertNotNull(gitJaspr.readSplitState())
        }
    }

    @Nav
    @Test
    fun `split then create commits then top replays remaining stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 1: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)

            // Split B
            gitJaspr.split()

            // Create two new commits from the split
            localGit.add("B.txt")
            localGit.commit("B-part1", footerLines = mapOf(COMMIT_ID_LABEL to "B-part1"))
            localRepo.resolve("extra.txt").writeText("extra\n")
            localGit.add("extra.txt")
            localGit.commit("B-part2", footerLines = mapOf(COMMIT_ID_LABEL to "B-part2"))

            // jaspr top should clear split state and replay C
            gitJaspr.clearSplitState()
            gitJaspr.navigateToTop(DEFAULT_TARGET_REF)

            assertFalse(localGit.isHeadDetached())
            assertNull(gitJaspr.readNavState())
            assertNull(gitJaspr.readSplitState())

            // Final stack should be A -> B-part1 -> B-part2 -> C
            val finalStack =
                localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)
            assertEquals(
                listOf("A", "B-part1", "B-part2", "C"),
                finalStack.map(Commit::shortMessage),
            )
        }
    }

    @Nav
    @Test
    fun `unsplit restores original commit`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Split B, then immediately unsplit
            gitJaspr.split()
            assertEquals("A", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            val subject = gitJaspr.unsplit()
            assertEquals("B", subject)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)
            assertNull(gitJaspr.readSplitState())
        }
    }

    @Nav
    @Test
    fun `unsplit absorbs working tree changes into original commit`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Split B
            gitJaspr.split()

            // Modify the working tree — remove B's file, add a new one
            localRepo.resolve("B.txt").delete()
            localRepo.resolve("new_file.txt").writeText("new content\n")

            // Unsplit: absorb changes back into B
            gitJaspr.unsplit()

            val head = localGit.log(GitClient.HEAD, 1).single()
            assertEquals("B", head.shortMessage)
            assertEquals("B", head.id) // commit-id preserved

            // The working tree should reflect the modifications
            assertFalse(localRepo.resolve("B.txt").exists())
            assertTrue(localRepo.resolve("new_file.txt").exists())
        }
    }

    @Nav
    @Test
    fun `unsplit during nav session re-inserts commit into stack`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            // Split B
            gitJaspr.split()
            val navAfterSplit = gitJaspr.readNavState()
            assertNotNull(navAfterSplit)
            assertEquals(listOf("A", "C", "D"), navAfterSplit.stack.map(StackEntry::commitId))

            // Unsplit B
            gitJaspr.unsplit()
            val navAfterUnsplit = gitJaspr.readNavState()
            assertNotNull(navAfterUnsplit)
            assertEquals(
                listOf("A", "B", "C", "D"),
                navAfterUnsplit.stack.map(StackEntry::commitId),
            )
            assertEquals(1, navAfterUnsplit.cursorIndex)
        }
    }

    // endregion

    // region fold tests

    @Nav
    @Test
    fun `fold down merges current commit into parent`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)
            assertEquals("B", localGit.log(GitClient.HEAD, 1).single().shortMessage)

            // Fold B down into A
            val survivor = gitJaspr.fold("down")
            assertEquals("A", survivor)

            // HEAD should be at A (which now contains B's changes)
            val head = localGit.log(GitClient.HEAD, 1).single()
            assertEquals("A", head.shortMessage)
            assertEquals("A", head.id)

            // A should contain B's file
            assertTrue(localRepo.resolve("B.txt").exists())

            // Nav state: B removed, cursor at 0 (A)
            val state = gitJaspr.readNavState()
            assertNotNull(state)
            assertEquals(listOf("A", "C", "D"), state.stack.map(StackEntry::commitId))
            assertEquals(0, state.cursorIndex)
        }
    }

    @Nav
    @Test
    fun `fold down at top of stack works without nav session`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Fold B into A (no nav session, on branch)
            val survivor = gitJaspr.fold("down")
            assertEquals("A", survivor)

            // Should still be on the branch
            assertFalse(localGit.isHeadDetached())

            // A should contain B's file
            assertTrue(localRepo.resolve("B.txt").exists())
        }
    }

    @Nav
    @Test
    fun `fold up merges current commit into the commit above`() {
        withTestSetup(useFakeRemote) {
            // Stack: A -> B -> C -> D on "development"
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 2: HEAD at B
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            // Fold B up into C (C survives with B's changes absorbed)
            val survivor = gitJaspr.fold("up")
            assertEquals("C", survivor)

            // HEAD should be at C (which now contains B's changes)
            val head = localGit.log(GitClient.HEAD, 1).single()
            assertEquals("C", head.shortMessage)
            assertEquals("C", head.id)

            // C should contain B's file
            assertTrue(localRepo.resolve("B.txt").exists())

            // Nav state: B removed, C at cursor position
            val state = gitJaspr.readNavState()
            assertNotNull(state)
            assertEquals(listOf("A", "C", "D"), state.stack.map(StackEntry::commitId))
            assertEquals(1, state.cursorIndex)
        }
    }

    @Nav
    @Test
    fun `fold down at bottom of stack fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav to bottom: HEAD at A
            gitJaspr.navigateToBottom(DEFAULT_TARGET_REF)
            assertThrows<IllegalArgumentException> { gitJaspr.fold("down") }
        }
    }

    @Nav
    @Test
    fun `fold up at top of replay queue fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // Nav down 1: HEAD at A, B is the only commit above
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 1)

            // Nav up 1: replays B, now at top — nothing above
            gitJaspr.navigateUp(1, DEFAULT_TARGET_REF)

            // Fold up should fail since there's nothing above the cursor
            assertThrows<IllegalArgumentException> { gitJaspr.fold("up") }
        }
    }

    @Nav
    @Test
    fun `fold up without nav session fails`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            // No nav session — fold up should fail
            assertThrows<IllegalArgumentException> { gitJaspr.fold("up") }
        }
    }

    // endregion

    // endregion

    // region sync tests
    @Sync
    @Test
    fun `sync rebases two non-overlapping branches`() {
        withTestSetup(useFakeRemote) {
            // Two independent stacks behind main. Main advances with "advance_main".
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = "" // No jaspr ID for the shared base
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                            branch {
                                commit {
                                    title = "A"
                                    localRefs += "branch_a"
                                }
                            }
                        }
                        commit {
                            title = "B"
                            localRefs += "branch_b"
                        }
                    }
                    checkout = "branch_b"
                }
            )
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            assertTrue(
                results.all { it.success },
                "All branches should sync successfully: $results",
            )
            // Both branches should now be rebased on top of advance_main
            for (branchName in listOf("branch_a", "branch_b")) {
                val stack = localGit.getLocalCommitStack(remoteName, branchName, DEFAULT_TARGET_REF)
                assertTrue(stack.isNotEmpty(), "$branchName should have commits above main")
            }
        }
    }

    @Sync
    @Test
    fun `sync rebases overlapping branches without duplicating commits`() {
        withTestSetup(useFakeRemote) {
            // Stack: fork_point -> A -> B -> C
            // branch_a at A, branch_c at C (checked out)
            // Main advances past fork_point
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = ""
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "A"
                            localRefs += "branch_a"
                        }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "branch_c"
                        }
                    }
                    checkout = "branch_c"
                }
            )
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            assertTrue(
                results.all { it.success },
                "All branches should sync successfully: $results",
            )

            // branch_a should have: advance_main -> A'
            val stackA = localGit.getLocalCommitStack(remoteName, "branch_a", DEFAULT_TARGET_REF)
            assertEquals(1, stackA.size)
            assertEquals("A", stackA[0].shortMessage)

            // branch_c should have: advance_main -> A' -> B' -> C'
            val stackC = localGit.getLocalCommitStack(remoteName, "branch_c", DEFAULT_TARGET_REF)
            assertEquals(3, stackC.size)
            assertEquals(listOf("A", "B", "C"), stackC.map(Commit::shortMessage))

            // Crucially: A' in branch_a should be the SAME commit as A' in branch_c
            assertEquals(stackA[0].hash, stackC[0].hash)
        }
    }

    @Sync
    @Test
    fun `sync skips conflicting non-checked-out branch`() {
        withTestSetup(useFakeRemote) {
            // branch_a will conflict, branch_b (checked out) will not
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = ""
                            localRefs += "fork_ref"
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "B"
                            localRefs += "branch_b"
                        }
                    }
                    checkout = "branch_b"
                }
            )
            // Create branch_a that modifies the same file as advance_main to cause a conflict
            localGit.checkout("fork_ref")
            val conflictFile = localRepo.resolve("advance_main.txt")
            conflictFile.writeText("conflicting content from branch_a\n")
            localGit.add("advance_main.txt")
            localGit.commit(
                "A_conflicting",
                footerLines = mapOf(COMMIT_ID_LABEL to "A_conflicting"),
            )
            localGit.branch("branch_a", force = true)
            localGit.checkout("branch_b")
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            val failed = results.filter { !it.success }
            val succeeded = results.filter { it.success }
            assertTrue(failed.any { it.branch == "branch_a" }, "branch_a should fail: $results")
            assertTrue(
                succeeded.any { it.branch == "branch_b" },
                "branch_b should succeed: $results",
            )
        }
    }

    @Sync
    @Test
    fun `sync handles conflict on checked-out branch`() {
        withTestSetup(useFakeRemote) {
            // branch_a (not checked out) will succeed
            // branch_b (checked out) will conflict
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = ""
                            localRefs += "fork_ref"
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                            branch {
                                commit {
                                    title = "A"
                                    localRefs += "branch_a"
                                }
                            }
                        }
                    }
                }
            )
            // Create branch_b with a conflicting commit
            localGit.checkout("fork_ref")
            val conflictFile = localRepo.resolve("advance_main.txt")
            conflictFile.writeText("conflicting content from branch_b\n")
            localGit.add("advance_main.txt")
            localGit.commit(
                "B_conflicting",
                footerLines = mapOf(COMMIT_ID_LABEL to "B_conflicting"),
            )
            localGit.branch("branch_b", force = true)
            localGit.checkout("branch_b")
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            val failed = results.filter { !it.success }
            val succeeded = results.filter { it.success }
            assertTrue(succeeded.any { it.branch == "branch_a" })
            assertTrue(failed.any { it.branch == "branch_b" })
        }
    }

    @Sync
    @Test
    fun `sync skips dependent branch when ancestor conflicts`() {
        withTestSetup(useFakeRemote) {
            // branch_a at A (will conflict), branch_b at A -> B (depends on A, should be skipped)
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = ""
                            localRefs += "fork_ref"
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                        }
                    }
                }
            )
            // Create conflicting commit A on branch_a
            localGit.checkout("fork_ref")
            val conflictFile = localRepo.resolve("advance_main.txt")
            conflictFile.writeText("conflicting content\n")
            localGit.add("advance_main.txt")
            localGit.commit(
                "A_conflicting",
                footerLines = mapOf(COMMIT_ID_LABEL to "A_conflicting"),
            )
            localGit.branch("branch_a", force = true)
            // Create B on top of A → branch_b
            localGit.commit(
                "B_depends_on_A",
                footerLines = mapOf(COMMIT_ID_LABEL to "B_depends_on_A"),
            )
            localGit.branch("branch_b", force = true)
            localGit.checkout("branch_b")
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            val failed = results.filter { !it.success }
            assertTrue(failed.any { it.branch == "branch_a" }, "branch_a should fail: $results")
            assertTrue(
                failed.any { it.branch == "branch_b" },
                "branch_b should be skipped (depends on branch_a): $results",
            )
        }
    }

    @Sync
    @Test
    fun `sync does nothing when branches are already up to date`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            assertTrue(results.all { it.success })
        }
    }

    @Sync
    @Test
    fun `sync ignores branches without jaspr commit IDs`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "fork_point"
                            id = ""
                            localRefs += "fork_ref"
                            branch {
                                commit {
                                    title = "advance_main"
                                    id = ""
                                    remoteRefs += "main"
                                }
                            }
                        }
                        commit {
                            title = "A"
                            localRefs += "jaspr_branch"
                        }
                    }
                    checkout = "jaspr_branch"
                }
            )
            // Create a non-jaspr branch (commit without footer)
            localGit.checkout("fork_ref")
            localGit.commit("plain commit without id")
            localGit.branch("plain_branch", force = true)
            localGit.checkout("jaspr_branch")
            localGit.fetch(remoteName)

            val results = gitJaspr.sync(DEFAULT_TARGET_REF)

            // Only jaspr_branch should be in results, not plain_branch
            val branchNames = results.map(GitJaspr.SyncBranchResult::branch)
            assertTrue("jaspr_branch" in branchNames)
            assertFalse("plain_branch" in branchNames)
        }
    }

    // endregion

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

    @Push
    @Test
    fun `push succeeds with untracked files in working directory`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "some_commit"
                            localRefs += "development"
                        }
                    }
                }
            )
            // Create an untracked file — this should not block push
            localRepo.resolve("untracked-file.txt").writeText("This file is not tracked by git.\n")
            push()
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

    @Status
    @Test
    fun `status surfaces remote-only commits via summary line pointing at compare`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "behind"
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val stackName = "my-stack-name"
            gitJaspr.push(stackName = stackName)
            waitForChecksToConclude("one", "two")

            // Walk back to a position where the local stack only has "one"; the remote named
            // stack still has both, so "two" is a remote-only commit-id.
            localGit.checkout("behind")
            localGit.setUpstreamBranch(
                remoteName,
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/$stackName",
            )
            val actual = getAndPrintStatusString()

            assertContains(actual, "! 1 remote-only commit. Run `jaspr compare` for details.")
        }
    }

    @Status
    @Test
    fun `status summary line reports both sides when local and remote each have unique commits`() {
        withTestSetup(useFakeRemote) {
            // Build a state where:
            //   local development = [one, three]
            //   remote named stack = [one, two]
            // so local-only = {three} and remote-only = {two}.
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                            branch {
                                commit {
                                    title = "three"
                                    willPassVerification = true
                                    localRefs += "development"
                                }
                            }
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                            remoteRefs += RemoteNamedStackRef(stackName = "my-stack-name").name()
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

            val actual = getAndPrintStatusString()

            assertContains(
                actual,
                "! 1 remote-only commit, 1 local commit not yet on remote. " +
                    "Run `jaspr compare` for details.",
            )
        }
    }

    // endregion

    // region compare tests
    @Compare
    @Test
    fun `compare with up-to-date stack renders all rows as identical`() {
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
                            localRefs += "development"
                        }
                    }
                }
            )
            push()

            val actual = getAndPrintCompareString()
            assertTrue(actual.contains("=="), "Expected '==' marker on every row:\n$actual")
            assertFalse(actual.contains("~~"), "Did not expect '~~' marker:\n$actual")
            assertFalse(actual.contains("[local-only]"), "Did not expect [local-only]:\n$actual")
            assertFalse(actual.contains("[remote-only]"), "Did not expect [remote-only]:\n$actual")
        }
    }

    @Compare
    @Test
    fun `compare with locally amended commit renders ~~ marker on diverged row`() {
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
                            localRefs += "development"
                        }
                    }
                }
            )
            push()

            delay(1200)

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                        }
                        commit {
                            title = "two_amended_locally"
                            id = "two"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                }
            )

            val actual = getAndPrintCompareString()
            assertTrue(actual.contains("~~"), "Expected '~~' marker on diverged row:\n$actual")
            assertTrue(
                actual.contains("two_amended_locally"),
                "Expected local-side subject in output:\n$actual",
            )
        }
    }

    @Compare
    @Test
    fun `compare fails clearly when no remote stack exists`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                }
            )

            val thrown =
                assertFailsWith<GitJasprException> { gitJaspr.getCompareString(theme = MonoTheme) }
            assertTrue(
                thrown.message!!.contains("No remote stack to compare against"),
                "Expected 'no remote stack' message:\n${thrown.message}",
            )
        }
    }

    // endregion

    // region graph tests
    @Graph
    @Test
    fun `graphRefs includes HEAD, remote target, and remote named-stack ref`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "my-stack")

            val refs = graphRefs(RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF))

            assertContains(refs, DEFAULT_LOCAL_OBJECT)
            assertContains(refs, "$remoteName/${DEFAULT_TARGET_REF}")
            assertTrue(
                refs.any { it.startsWith("$remoteName/") && it.endsWith("my-stack") },
                "Expected a remote named-stack ref for 'my-stack' in $refs",
            )
        }
    }

    @Graph
    @Test
    fun `graphRefs omits the named-stack ref when none exists for the local stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val refs = graphRefs(RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF))

            assertContains(refs, DEFAULT_LOCAL_OBJECT)
            assertContains(refs, "$remoteName/${DEFAULT_TARGET_REF}")
            // The local stack hasn't been pushed to a named-stack ref, so the only refs are HEAD
            // and the remote target.
            assertEquals(2, refs.size, "Unexpected refs: $refs")
        }
    }

    // endregion

    // region pull tests
    @Pull
    @Test
    fun `pull is a no-op when local matches remote`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")

            val output = pull()

            assertEquals("Your stack is up to date with the remote.\n", output)
        }
    }

    @Pull
    @Test
    fun `pull is a no-op when local has unpushed commits`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Add an unpushed local commit on top of the pushed stack.
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
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val output = pull()

            assertTrue(
                output.contains("Your stack has local commits not yet on the remote"),
                "Expected LOCAL_HAS_UNPUSHED message, got: $output",
            )
        }
    }

    @Pull
    @Test
    fun `pull hard-resets when remote has new commits on top`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Push a new commit "three" on top, then rewind local to before that push.
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
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Roll local back to the two-commit state so it's "behind" remote.
            localGit.reset("HEAD~1")

            val output = pull()

            assertEquals("Pulled; your stack now matches remote.\n", output)
            assertEquals("three", localGit.log("HEAD", 1).single().shortMessage)
        }
    }

    @Pull
    @Test
    fun `pull punts when both sides have unique commits`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Replace the top of local with "three", so local has "three" and remote has "two".
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            val output = pull()

            assertTrue(
                output.contains("each have unique commits"),
                "Expected MIXED_UNIQUE_WORK punt, got: $output",
            )
        }
    }

    @Pull
    @Test
    fun `pull is idempotent after a hard-reset pull`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
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
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            localGit.reset("HEAD~1")

            val first = pull()
            val second = pull()

            assertEquals("Pulled; your stack now matches remote.\n", first)
            assertEquals("Your stack is up to date with the remote.\n", second)
        }
    }

    @Pull
    @Test
    fun `pull refuses when a cherry-pick is in progress`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Drop a CHERRY_PICK_HEAD sentinel into the worktree's git dir.
            localGit.workingDirectory.resolve(".git/CHERRY_PICK_HEAD").writeText("dummy\n")

            val thrown = assertFailsWith<GitJasprException> { pull() }
            assertTrue(
                thrown.message!!.contains("cherry-pick is in progress"),
                "Expected cherry-pick precondition message, got: ${thrown.message}",
            )
        }
    }

    @Pull
    @Test
    fun `pull --theirs resolves content divergence by adopting remote's version`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")

            // Amend "two" locally with content that conflicts with remote's version.
            // Remote still has the original "two"; local now has a divergent "two".
            localGit.workingDirectory.resolve("two.txt").writeText("locally amended content\n")
            localGit.add("two.txt")
            localGit.commit("two", footerLines = mapOf(COMMIT_ID_LABEL to "two"), amend = true)

            val output = pull(theirs = true)

            assertTrue(
                output.contains("refs/jaspr-backup/pre-pull-"),
                "Expected backup ref message; got: $output",
            )
            assertTrue(
                output.contains("Adopted remote's version of 1 diverged commit"),
                "Expected resolution summary; got: $output",
            )
            // Local's "two.txt" should now match remote's version, not the locally amended one.
            assertEquals("Title: two\n", localGit.workingDirectory.resolve("two.txt").readText())
        }
    }

    @Pull
    @Test
    fun `pull refuses when a rebase is in progress`() {
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
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "test-stack")
            // Drop a rebase-merge sentinel directory.
            localGit.workingDirectory.resolve(".git/rebase-merge").mkdirs()

            val thrown = assertFailsWith<GitJasprException> { pull() }
            assertTrue(
                thrown.message!!.contains("rebase is in progress"),
                "Expected rebase precondition message, got: ${thrown.message}",
            )
        }
    }

    // endregion

    // region push tests
    @Push
    @Test
    fun `push installs commit-id hook`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "main"
                            remoteRefs += "main"
                        }
                    }
                }
            )

            val hook = localRepo.resolve(".git").resolve("hooks").resolve("commit-msg")
            assertFalse(hook.exists())

            push()

            assertTrue(hook.exists())
            assertTrue(hook.canExecute())
        }
    }

    @Push
    @Test
    fun `push handles empty commits without commit IDs`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += "main"
                        }
                        commit {
                            title = "empty"
                            id = "" // no commit-id footer
                            empty = true
                            localRefs += "development"
                        }
                    }
                }
            )

            push()

            val stack = localGit.getLocalCommitStack(remoteName, "HEAD", DEFAULT_TARGET_REF)
            assertTrue(stack.all { it.id != null })
        }
    }

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

            gitJaspr.push(stackName = "test-stack")

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
            gitJaspr.push(stackName = "test-stack")
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
            // ambiguous — a warning is displayed with the conflicting stack names
            val detachedHeadActual = getAndPrintStatusString()
            assertEquals(
                """
                |[✅✅✅✅✅✅] %s : %s : two
                |[✅✅✅✅✅✅] %s : %s : one
                """
                    .trimMargin()
                    .toStatusString(
                        detachedHeadActual,
                        ambiguousStackNames = listOf(secondStackName, stackName),
                    ),
                detachedHeadActual,
            )
        }
    }

    @Push
    @Test
    fun `push aborts when onAbandonedPrs returns false`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            gitJaspr.push(stackName = "my-stack")

            // Re-push without commit B, which would abandon its PR
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            val exception =
                assertThrows<GitJasprException> {
                    gitJaspr.push(stackName = "my-stack", onAbandonedPrs = { false })
                }
            assertContains(exception.message, "abandon")

            // Succeeds with default (permits abandoning)
            gitJaspr.push(stackName = "my-stack")
        }
    }

    @Push
    @Test
    fun `push detects abandoned PRs when commits are dropped`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            gitJaspr.push(stackName = "my-stack")

            // Re-push without commit B, which will abandon its PR
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        // B is dropped
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            val remoteBranches = localGit.getRemoteBranches(remoteName)
            val prefixedStackName = RemoteNamedStackRef("my-stack").name()
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            val abandonedPrs =
                gitJaspr.findPrsAbandonedByPush(
                    remoteBranches,
                    prefixedStackName,
                    DEFAULT_TARGET_REF,
                    stack,
                )

            assertEquals(1, abandonedPrs.size, "Expected one abandoned PR")
            assertEquals("B", abandonedPrs.single().title)
        }
    }

    @Push
    @Test
    fun `push does not detect abandoned PRs for a new stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            val remoteBranches = localGit.getRemoteBranches(remoteName)
            val prefixedStackName = RemoteNamedStackRef("my-stack").name()
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            val abandonedPrs =
                gitJaspr.findPrsAbandonedByPush(
                    remoteBranches,
                    prefixedStackName,
                    DEFAULT_TARGET_REF,
                    stack,
                )

            assertTrue(abandonedPrs.isEmpty(), "Expected no abandoned PRs for a new stack")
        }
    }

    @Push
    @Test
    fun `push does not detect abandoned PRs when no commits are dropped`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            gitJaspr.push(stackName = "my-stack")

            // Push again with an additional commit (no drops)
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            val remoteBranches = localGit.getRemoteBranches(remoteName)
            val prefixedStackName = RemoteNamedStackRef("my-stack").name()
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            val abandonedPrs =
                gitJaspr.findPrsAbandonedByPush(
                    remoteBranches,
                    prefixedStackName,
                    DEFAULT_TARGET_REF,
                    stack,
                )

            assertTrue(
                abandonedPrs.isEmpty(),
                "Expected no abandoned PRs when no commits are dropped",
            )
        }
    }

    @Push
    @Test
    fun `push does not abandon PRs for commits still owned by another named stack`() {
        withTestSetup(useFakeRemote) {
            // stack-A owns [A, B]; stack-B owns [A, B, C]. They overlap on A and B.
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "stack-A")

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "stack-B")

            // Drop A locally, leaving [B, C], and re-evaluate stack-B.
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "B" }
                        commit {
                            title = "C"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )

            val remoteBranches = localGit.getRemoteBranches(remoteName)
            val prefixedStackName = RemoteNamedStackRef("stack-B").name()
            val stack = localGit.getLocalCommitStack(remoteName, GitClient.HEAD, DEFAULT_TARGET_REF)

            val abandonedPrs =
                gitJaspr.findPrsAbandonedByPush(
                    remoteBranches,
                    prefixedStackName,
                    DEFAULT_TARGET_REF,
                    stack,
                )

            // A is still reachable from stack-A, so it must not be reported as abandoned.
            assertTrue(
                abandonedPrs.isEmpty(),
                "Expected no abandoned PRs since the dropped commit is still owned by stack-A",
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

            gitJaspr.push(stackName = "stack-3")

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
                listOf("stack-1", "stack-2", "stack-3")
                    .map { RemoteNamedStackRef(it).name() }
                    .toSet()
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
    fun `suggestStackNames returns suggested names for new stack`() {
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

            val suggested = gitJaspr.suggestStackNames()
            assertEquals(listOf("one", "two"), suggested.candidates)
        }
    }

    @Push
    @Test
    fun `suggestStackNames returns empty list for existing stack`() {
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

            // suggestStackNames should return empty since the stack already has a name
            val suggested = gitJaspr.suggestStackNames()
            assertEquals(emptyList(), suggested.candidates)
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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

                    To pull this stack into your working copy (triple click to select):
                    <kbd>jaspr checkout -n test-stack</kbd>

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

            gitJaspr.executeCleanPlan(
                gitJaspr.getCleanPlan(cleanAbandonedPrs = false, cleanAllCommits = false)
            )
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
    fun `clean also removes matching local branches`() {
        withTestSetup(useFakeRemote) {
            // Set up orphaned remote branches (no open PRs pointing to them)
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("b")
                        }
                    }
                }
            )

            // Create a local branch tracking one of the orphaned remote branches
            val orphanedRef = buildRemoteRef("b")
            localGit.branch("my-local-branch", startPoint = "$remoteName/$orphanedRef")
            localGit.setUpstreamBranchForLocalBranch("my-local-branch", remoteName, orphanedRef)

            // Verify the local branch exists before clean
            assertTrue("my-local-branch" in localGit.getBranchNames())

            gitJaspr.executeCleanPlan(
                gitJaspr.getCleanPlan(cleanAbandonedPrs = false, cleanAllCommits = false)
            )

            // The local branch should have been removed along with the remote
            assertFalse("my-local-branch" in localGit.getBranchNames())
        }
    }

    @Clean
    @Test
    fun `clean does not remove local branch with divergent tip`() {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("b")
                        }
                    }
                }
            )

            // Create a local branch tracking the orphaned remote, then add a local commit
            val orphanedRef = buildRemoteRef("b")
            localGit.branch("my-diverged-branch", startPoint = "$remoteName/$orphanedRef")
            localGit.setUpstreamBranchForLocalBranch("my-diverged-branch", remoteName, orphanedRef)
            localGit.checkout("my-diverged-branch")
            localGit.commit("local-only commit")
            localGit.checkout("development")

            gitJaspr.executeCleanPlan(
                gitJaspr.getCleanPlan(cleanAbandonedPrs = false, cleanAllCommits = false)
            )

            // The diverged local branch should NOT be deleted (tip doesn't match remote)
            assertTrue("my-diverged-branch" in localGit.getBranchNames())
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
            gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            assertEquals(
                CleanPlan(
                    emptyNamedStackBranches =
                        sortedSetOf(
                            RemoteNamedStackRef("stack-one").name(),
                            RemoteNamedStackRef("stack-two").name(),
                        )
                ),
                gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false),
            )
        }
    }

    @Clean
    @Test
    fun `clean reports abandoned named stack branches when underlying jaspr branches are gone`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit {
                            title = "B"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "my-stack")

            // Manually delete every jaspr ID branch on the remote, leaving the named stack
            // branch behind. Mirrors what GitHub's "delete branch on close" does when the
            // user closes PRs without running jaspr clean.
            val jasprIdBranches =
                localGit.getRemoteBranches(remoteName).map(RemoteBranch::name).filter { name ->
                    RemoteRefEncoding.RemoteRef.parse(
                        name,
                        RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
                    ) != null
                }
            if (useFakeRemote) {
                remoteGit.deleteBranches(jasprIdBranches, force = true)
            } else {
                localGit.push(
                    jasprIdBranches.map { name -> RefSpec(FORCE_PUSH_PREFIX, name) },
                    remoteName,
                )
            }

            val plan = gitJaspr.getCleanPlan(cleanAbandonedPrs = false, cleanAllCommits = false)
            assertEquals(
                sortedSetOf(RemoteNamedStackRef("my-stack").name()),
                plan.abandonedNamedStackBranches,
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
            gitJaspr.executeCleanPlan(
                gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            )

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
            assertEquals(
                CleanPlan(
                    orphanedBranches = sortedSetOf(buildRemoteRef("will_orphan_a")),
                    emptyNamedStackBranches =
                        sortedSetOf(RemoteNamedStackRef("empty_stack").name()),
                    abandonedBranches = sortedSetOf(buildRemoteRef("D")),
                ),
                gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false),
            )

            // Get the plan again to ensure it doesn't change anything (no side effects)
            gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)

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
    fun `clean does not flag PR head as abandoned when commit-id reachable from another named stack`() {
        withTestSetup(useFakeRemote) {
            // Push stack-A with [X]; PR head jaspr/main/X is at the original X hash.
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "X"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "stack-A")

            // Push stack-B with [X', Y] (X amended, same commit-id, different hash);
            // PR head jaspr/main/X is force-pushed to X'.
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "X" }
                        commit {
                            title = "Y"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "stack-B")

            // Drop X from stack-B and re-push; PR head jaspr/main/X is NOT updated by this push,
            // leaving its hash unreachable from stack-B's named-stack ref.
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "Y"
                            localRefs += "dev"
                        }
                    }
                    checkout = "dev"
                }
            )
            gitJaspr.push(stackName = "stack-B")

            // X's PR head must not be flagged as abandoned because stack-A still owns
            // commit-id X (even though the hashes diverge).
            val plan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            assertFalse(
                plan.abandonedBranches.contains(buildRemoteRef("X")),
                "X's PR head should not be abandoned: stack-A still references its commit-id",
            )
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

            val plan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            val finalPlan =
                gitJaspr.closeAbandonedPrsAndRecalculate(
                    plan,
                    cleanAbandonedPrs = true,
                    cleanAllCommits = false,
                )
            gitJaspr.executeCleanPlan(finalPlan)

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

            val plan2 = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            val finalPlan2 =
                gitJaspr.closeAbandonedPrsAndRecalculate(
                    plan2,
                    cleanAbandonedPrs = true,
                    cleanAllCommits = false,
                )
            gitJaspr.executeCleanPlan(finalPlan2)

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
            val cleanPlan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)

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
            val cleanPlan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
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
            val cleanPlan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)
            assertEquals(
                setOf(buildRemoteRef("A"), buildRemoteRef("B")),
                cleanPlan.orphanedBranches,
            )

            // Get the clean plan with cleanAllCommits = true - should include all branches
            val cleanPlanAll =
                gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = true)
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
        ambiguousStackNames: List<String> = emptyList(),
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
            if (ambiguousStackNames.isNotEmpty()) {
                appendLine()
                appendLine(
                    "Stack name could not be determined: commits exist in multiple stacks: " +
                        ambiguousStackNames.joinToString(", ")
                )
            }
            if (namedStackInfo != null) {
                appendLine()
                appendLine("Stack name: ${namedStackInfo.name}")
                with(namedStackInfo) {
                    appendLine(
                        if (numCommitsBehind == 0 && numCommitsAhead == 0) {
                            "Your stack is up to date with the remote stack in '$remoteName'."
                        } else if (numCommitsBehind > 0 && numCommitsAhead == 0) {
                            "Your stack is behind the remote stack in '$remoteName' by " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)}. " +
                                "Run `jaspr pull` to incorporate them."
                        } else if (numCommitsBehind == 0) { // && numCommitsAhead > 0
                            "Your stack is ahead of the remote stack in '$remoteName' by " +
                                "$numCommitsAhead ${commitOrCommits(numCommitsAhead)}. " +
                                "Run `jaspr push` to publish them."
                        } else { // numCommitsBehind > 0 && numCommitsAhead > 0
                            "Your stack and the remote stack in '$remoteName' have diverged " +
                                "($numCommitsAhead ${commitOrCommits(numCommitsAhead)} ahead, " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)} behind). " +
                                "Run `jaspr compare` to see what's different."
                        }
                    )
                    // For these test scenarios (no rebase / divergence), behind == RO count and
                    // ahead == LO count, so the unique-commits summary line maps cleanly onto
                    // numCommitsBehind / numCommitsAhead.
                    val remoteOnly = numCommitsBehind
                    val localOnly = numCommitsAhead
                    if (remoteOnly > 0 || localOnly > 0) {
                        val parts = buildList {
                            if (remoteOnly > 0) {
                                add("$remoteOnly remote-only ${commitOrCommits(remoteOnly)}")
                            }
                            if (localOnly > 0) {
                                add(
                                    "$localOnly local ${commitOrCommits(localOnly)} not yet on remote"
                                )
                            }
                        }
                        appendLine()
                        appendLine(
                            "! ${parts.joinToString(", ")}. Run `jaspr compare` for details."
                        )
                    }
                }
            }
        }
        // The "Remote stack has N commits not in your local stack" section depends on test data
        // we can't easily predict (hashes, dates). Tests that don't explicitly verify it should
        // still pass when it appears, so we extract it from `actual` and tack it onto expected.
        val remoteOnlyExtract =
            "(?m)^Remote stack has \\d+ commits? not in your local stack[^\\n]*\\n(?:  ⬇️  [^\\n]*\\n)+"
                .toRegex(RegexOption.MULTILINE)
                .find(actual)
                ?.value
                ?.let { "\n$it" }
                .orEmpty()
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
            .trimMargin() + namedStackInfoString + remoteOnlyExtract
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
            gitJaspr
                .clone { config -> config.copy(dontPushRegex = "^(wip)\\b.*$") }
                .push(stackName = "test-stack")

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
            val cleanPlan = gitJaspr.getCleanPlan(cleanAbandonedPrs = true, cleanAllCommits = false)

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

    // region stack tests

    @Stack
    @Test
    fun `getAllNamedStacks with mineOnly filters by current user`() {
        withTestSetup(useFakeRemote) {
            val otherAuthor = Ident("Other Person", "other@example.com")

            // Push a stack as the default committer (Frank Grimes)
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "my_commit"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "my-stack")

            // Push a stack with a different committer
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "other_commit"
                            committer {
                                name = otherAuthor.name
                                email = otherAuthor.email
                            }
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "other-stack")

            // Without filter: both stacks
            val allStacks = gitJaspr.getAllNamedStacks()
            assertEquals(2, allStacks.size)

            // With mineOnly: only the stack authored by the current user (DEFAULT_COMMITTER)
            val myStacks = gitJaspr.getAllNamedStacks(mineOnly = true)
            assertEquals(1, myStacks.size)
            assertEquals("my-stack", myStacks.single().stackName)
        }
    }

    @Stack
    @Test
    fun `rename stack changes remote branch name`() {
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

            val oldName = "my-stack"
            gitJaspr.push(stackName = oldName)

            renameStack(oldName, "new-stack")

            localGit.fetch(remoteName)
            val remoteBranches = localGit.getRemoteBranches(remoteName).map(RemoteBranch::name)
            val oldRef = RemoteNamedStackRef(oldName).name()
            val newRef = RemoteNamedStackRef("new-stack").name()
            assertFalse(oldRef in remoteBranches, "Old remote branch should not exist")
            assertTrue(newRef in remoteBranches, "New remote branch should exist")
        }
    }

    @Stack
    @Test
    fun `rename stack updates upstream tracking for local branch`() {
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

            val oldName = "my-stack"
            gitJaspr.push(stackName = oldName)

            // Checkout the stack to create a local tracking branch
            checkout(oldName)
            assertEquals(oldName, localGit.getCurrentBranchName())

            renameStack(oldName, "new-stack")

            // Local branch name should be unchanged
            assertEquals(oldName, localGit.getCurrentBranchName())
            // But its upstream should point to the new remote ref
            val upstreamName = localGit.getUpstreamBranchName(oldName, remoteName)
            assertEquals(RemoteNamedStackRef("new-stack").name(), upstreamName)
        }
    }

    @Stack
    @Test
    fun `rename stack fails if old name not found`() {
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

            assertThrows<GitJasprException> { renameStack("nonexistent", "new-name") }
        }
    }

    @Stack
    @Test
    fun `rename stack fails if new name already exists`() {
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

            gitJaspr.push(stackName = "stack-a")
            // Create another stack to collide with
            localGit.checkout("main")
            gitJaspr.push(stackName = "stack-b")

            assertThrows<GitJasprException> { renameStack("stack-a", "stack-b") }
        }
    }

    @Stack
    @Test
    fun `delete stack removes remote branch`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            localRefs += "main"
                        }
                    }
                    checkout = "main"
                }
            )

            gitJaspr.push(stackName = "my-stack")
            localGit.fetch(remoteName)
            val stackRef = RemoteNamedStackRef("my-stack").name()
            assertTrue(
                stackRef in localGit.getRemoteBranches(remoteName).map(RemoteBranch::name),
                "Stack should exist before delete",
            )

            deleteStack("my-stack")

            localGit.fetch(remoteName, prune = true)
            val remoteBranches = localGit.getRemoteBranches(remoteName).map(RemoteBranch::name)
            assertFalse(stackRef in remoteBranches, "Stack should not exist after delete")
        }
    }

    @Stack
    @Test
    fun `delete non-empty stack removes remote branch`() {
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

            gitJaspr.push(stackName = "my-stack")

            deleteStack("my-stack")

            localGit.fetch(remoteName, prune = true)
            val remoteBranches = localGit.getRemoteBranches(remoteName).map(RemoteBranch::name)
            val stackRef = RemoteNamedStackRef("my-stack").name()
            assertFalse(stackRef in remoteBranches, "Stack should not exist after delete")
        }
    }

    @Stack
    @Test
    fun `delete stack fails if name not found`() {
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

            assertThrows<GitJasprException> { deleteStack("nonexistent") }
        }
    }

    @Stack
    @Test
    fun `delete stack also removes matching local branch`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "my-stack")

            // Use checkout to create a local branch tracking the named stack
            checkout("my-stack")
            // Switch back to development so my-stack isn't the current branch
            localGit.checkout("development")

            assertTrue("my-stack" in localGit.getBranchNames())

            deleteStack("my-stack")

            assertFalse("my-stack" in localGit.getBranchNames())
        }
    }

    @Stack
    @Test
    fun `delete stack preserves diverged local branch and unsets upstream`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "my-stack")

            // Create a local branch tracking the stack, then add a local-only commit
            checkout("my-stack")
            localGit.commit("local-only commit")
            localGit.checkout("development")

            assertTrue("my-stack" in localGit.getBranchNames())

            deleteStack("my-stack")

            // Branch should still exist (diverged tip) but upstream should be unset
            assertTrue("my-stack" in localGit.getBranchNames())
            assertNull(localGit.getUpstreamBranchName("my-stack", remoteName))
        }
    }

    @Stack
    @Test
    fun `delete stack skips current branch and unsets upstream`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )
            push(stackName = "my-stack")

            // Check out the stack branch so it's the current branch
            checkout("my-stack")

            deleteStack("my-stack")

            // Current branch should still exist but upstream should be unset
            assertEquals("my-stack", localGit.getCurrentBranchName())
            assertNull(localGit.getUpstreamBranchName("my-stack", remoteName))
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
