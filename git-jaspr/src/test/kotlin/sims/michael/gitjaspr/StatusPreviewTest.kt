package sims.michael.gitjaspr

import java.io.File
import kotlinx.coroutines.delay
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

/**
 * Preview-only tests that render `jaspr status` with the colored [DefaultTheme] for every distinct
 * rendering branch. Intended for eyeballing the rendered output in a real terminal without setting
 * up a real GitHub remote. Test names describe each scenario; the rendered output is appended to a
 * single shared file at `$TMPDIR/jaspr-status-preview.ansi` (reset at the start of each class run).
 *
 * Workflow:
 * 1. Run the tests with `STATUS_PREVIEW_TEST_ENABLE=1` set in the environment, e.g.
 *    `STATUS_PREVIEW_TEST_ENABLE=1 ./gradlew :git-jaspr:test --tests "*StatusPreview*"`. In IDEA,
 *    set `STATUS_PREVIEW_TEST_ENABLE=1` under "Environment variables" in the run configuration.
 *    Without that variable the class is skipped, so no source edit is needed to toggle it.
 * 2. `cat "${TMPDIR%/}/jaspr-status-preview.ansi"` in your terminal to see the renderings with
 *    colors. Suggested alias: `alias jsp='cat "${TMPDIR%/}/jaspr-status-preview.ansi"'`.
 */
@EnabledIfEnvironmentVariable(named = "STATUS_PREVIEW_TEST_ENABLE", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
class StatusPreviewTest {

    private val logger = LoggerFactory.getLogger(StatusPreviewTest::class.java)

    private val sharedOutputFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "jaspr-status-preview.ansi")

    @BeforeAll
    fun resetSharedOutput() {
        sharedOutputFile.writeText("")
        logger.info("Preview output will accumulate in: {}", sharedOutputFile.absolutePath)
        logger.info("View with: cat {}", sharedOutputFile.absolutePath)
    }

    @Test
    @Order(1)
    fun `up to date`(testInfo: TestInfo) {
        withTestSetup {
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
                    checkout = "development"
                }
            )

            gitJaspr.push(stackName = "preview-stack")
            markAllPullRequestsHealthy()

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(2)
    fun `local has unpushed commit`(testInfo: TestInfo) {
        withTestSetup {
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
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")
            markAllPullRequestsHealthy()

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
                            title = "three_unpushed"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(3)
    fun `remote has new commit`(testInfo: TestInfo) {
        withTestSetup {
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
                            branch {
                                commit {
                                    title = "three_from_coworker"
                                    willPassVerification = true
                                    remoteRefs += buildRemoteRef("three_from_coworker")
                                    remoteRefs +=
                                        RemoteNamedStackRef(stackName = "preview-stack").name()
                                }
                            }
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = DEFAULT_TARGET_REF
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

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(4)
    fun `local and remote each have unique commits`(testInfo: TestInfo) {
        withTestSetup {
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
                            remoteRefs += RemoteNamedStackRef(stackName = "preview-stack").name()
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = DEFAULT_TARGET_REF
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

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(5)
    fun `compare - up to date`(testInfo: TestInfo) {
        withTestSetup {
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
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")
            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(6)
    fun `compare - local has amended commit`(testInfo: TestInfo) {
        withTestSetup {
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
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")

            delay(1200)

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two_amended_locally"
                            id = "two"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(7)
    fun `compare - remote has amended commit`(testInfo: TestInfo) {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                            branch {
                                commit {
                                    title = "two"
                                    id = "two"
                                    willPassVerification = true
                                    localRefs += "development"
                                    remoteRefs += buildRemoteRef("two")
                                    remoteRefs +=
                                        RemoteNamedStackRef(stackName = "preview-stack").name()
                                }
                            }
                        }
                    }
                    checkout = "development"
                }
            )

            delay(1200)

            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                            branch {
                                commit {
                                    title = "two"
                                    id = "two"
                                    willPassVerification = true
                                    localRefs += "development"
                                }
                            }
                            branch {
                                commit {
                                    title = "two_amended_remotely"
                                    id = "two"
                                    willPassVerification = true
                                    remoteRefs += buildRemoteRef("two")
                                    remoteRefs +=
                                        RemoteNamedStackRef(stackName = "preview-stack").name()
                                }
                            }
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(8)
    fun `compare - local has unpushed commit on top`(testInfo: TestInfo) {
        withTestSetup {
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
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")

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
                            title = "three_unpushed"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(9)
    fun `compare - remote has new commit on top`(testInfo: TestInfo) {
        withTestSetup {
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
                            branch {
                                commit {
                                    title = "three_from_coworker"
                                    willPassVerification = true
                                    remoteRefs += buildRemoteRef("three_from_coworker")
                                    remoteRefs +=
                                        RemoteNamedStackRef(stackName = "preview-stack").name()
                                }
                            }
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(10)
    fun `compare - reordered commit`(testInfo: TestInfo) {
        withTestSetup {
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
                            title = "three"
                            id = "three"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")

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
                            title = "three"
                            id = "three"
                            willPassVerification = true
                        }
                        commit {
                            title = "two"
                            id = "two"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(11)
    fun `compare - reordered and amended commit`(testInfo: TestInfo) {
        withTestSetup {
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
                            title = "three"
                            id = "three"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")

            // Sleep past the second boundary so the amended commit has a strictly later commit
            // date than the original; without this the renderer can't pick a "newer" side and
            // falls through to DIVERGED_EQUAL_DATE, suppressing the bold/asterisk/dim styling.
            delay(1200)

            // Local rewrite: reorder (three swapped with two) and amend the moved "two" commit.
            // Result: local stack = [one, three, two_amended_locally]; remote stack = [one, two,
            // three]. Both reordered AND content-divergent on the "two" commit-id.
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
                            title = "three"
                            id = "three"
                            willPassVerification = true
                        }
                        commit {
                            title = "two_amended_locally"
                            id = "two"
                            willPassVerification = true
                            localRefs += "development"
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(12)
    fun `nav - status cursor mid-stack`(testInfo: TestInfo) {
        withTestSetup {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("five")
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")
            markAllPullRequestsHealthy()

            // 5-commit stack; navigate down 2 so the cursor sits at "three" (the middle).
            // Expected: banner reads "Navigating [3/5]", cursor arrow on the row for "three".
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(13)
    fun `nav - compare cursor mid-stack`(testInfo: TestInfo) {
        withTestSetup {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("five")
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")

            // Same shape as the status mid-stack scenario above, but rendered via compare.
            gitJaspr.navigateDown(DEFAULT_TARGET_REF, 2)

            renderAndAppendCompare(testInfo)
        }
    }

    @Test
    @Order(14)
    fun `compare - long subjects truncate with ellipsis`(testInfo: TestInfo) {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "short subject under 70 chars stays whole"
                            id = "short"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("short")
                        }
                        commit {
                            title =
                                "exactly seventy characters long subject xxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                            id = "seventy"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("seventy")
                        }
                        commit {
                            title =
                                "this subject is deliberately longer than seventy characters so we can see the truncation ellipsis in action"
                            id = "verylong"
                            willPassVerification = true
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("verylong")
                        }
                    }
                    checkout = "development"
                }
            )
            gitJaspr.push(stackName = "preview-stack")
            renderAndAppendCompare(testInfo)
        }
    }

    private fun GitHubTestHarness.renderAndAppendCompare(
        testInfo: TestInfo,
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
    ) {
        val ansi = gitJaspr.getCompareString(refSpec, DefaultTheme)
        val name = testInfo.testMethod.get().name
        val banner = "\u001B[1;35m══ $name ══\u001B[0m"
        sharedOutputFile.appendText("\n$banner\n\n$ansi")
    }

    private suspend fun GitHubTestHarness.renderAndAppendStatus(
        testInfo: TestInfo,
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
    ) {
        val ansi = gitJaspr.getStatusString(refSpec, DefaultTheme)
        val name = testInfo.testMethod.get().name
        val banner = "\u001B[1;35m══ $name ══\u001B[0m"
        sharedOutputFile.appendText("\n$banner\n\n$ansi")
        logger.info("Scratch dir for this scenario: {}", scratchDir.absolutePath)
    }

    /**
     * Update every open PR on the stub GitHub client so it renders as fully passed and approved.
     * Without this, PRs created by [GitJaspr.push] default to `checksPass = null` (pending) and
     * `approved = null` (empty), producing a noisy ⌛/ㄧ rendering that obscures the divergence
     * details we're trying to preview.
     */
    private suspend fun GitHubTestHarness.markAllPullRequestsHealthy() {
        for (pr in gitHub.getPullRequestsById()) {
            gitHub.updatePullRequest(pr.copy(checksPass = true, approved = true))
        }
    }
}
