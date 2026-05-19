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
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
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
    fun `local ahead of remote by 1 commit`(testInfo: TestInfo) {
        withTestSetup {
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
    fun `local behind remote by 1 commit with new commit date`(testInfo: TestInfo) {
        withTestSetup {
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

            val stackName = "preview-stack"
            gitJaspr.push(stackName = stackName)
            markAllPullRequestsHealthy()

            localGit.checkout("behind")
            localGit.setUpstreamBranch(
                remoteName,
                "$DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX/$DEFAULT_TARGET_REF/$stackName",
            )

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(4)
    fun `local has newer amended version of commit`(testInfo: TestInfo) {
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

            // Ensure the rewritten local commit lands in a later second so its commit date is
            // strictly greater than the remote's. The date determines whether the divergent
            // indicator renders as ⏫ AHEAD_DIVERGENT, ⏬ BEHIND_DIVERGENT, or 🔀 DIVERGENT
            // (equal-date fallback).
            delay(1200)

            // Rewrite the local stack so its tip has the same commit-id as the remote "two" but a
            // different SHA + subject. After this, ahead/behind by SHA are both 1, but the remote
            // stack has no commit-ids missing from local, so the "remote-only" section is empty.
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

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(5)
    fun `remote has newer amended version of commit`(testInfo: TestInfo) {
        withTestSetup {
            // Pass 1: build [one, two] locally and push to all the remote refs we care about (the
            // per-commit ref and the named-stack ref). PRs are wired up declaratively so they
            // render fully passed without a separate `markAllPullRequestsHealthy` call.
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

            // Ensure the remote-side amendment lands in a later second so its commit date is
            // strictly greater than the local "two". The date determines whether the divergent
            // indicator renders as ⏫ AHEAD_DIVERGENT, ⏬ BEHIND_DIVERGENT, or 🔀 DIVERGENT
            // (equal-date fallback).
            delay(1200)

            // Pass 2: create a sibling of "two" (also a child of "one") with the same commit-id
            // but a fresher SHA + content. Force-pushed to the per-commit ref and the named-stack
            // ref to simulate another contributor amending the commit and pushing while we still
            // have the older local version. Local "development" stays at the original "two", so
            // the per-commit row shows ⏬ BEHIND_DIVERGENT and the headline reads "diverged".
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

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(6)
    fun `diverged with newer remote-only commit`(testInfo: TestInfo) {
        withTestSetup {
            // Build the divergence in a single DSL pass so we can interleave a local-only fork
            // ("three") and a remote-only continuation ("two") that lands at the named-stack ref.
            // Creation order is "one" -> "three" (inside the branch) -> "two", which guarantees
            // date(two) > date(three) so the remote-only section renders in the warning style
            // (not "likely stale").
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
    @Order(7)
    fun `diverged with older remote-only commit (likely stale)`(testInfo: TestInfo) {
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

            // Git commit times are stored at the second resolution. The "(likely stale)" rendering
            // depends on localMaxDate > remoteOnlyMaxDate (strict), so we delay long enough to
            // guarantee the next commits land in a later second.
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
                            title = "three"
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
    @Order(8)
    fun `local has rebased but identical commit`(testInfo: TestInfo) {
        withTestSetup {
            // Build [one, two, three] locally, push everything, all PRs healthy.
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
            markAllPullRequestsHealthy()

            // Push date-ordering past the remote. The test harness cherry-picks the "three" commit
            // below, and the new local "three" must land in a later second than the remote "three"
            // so the per-commit indicator renders as ⬆️ AHEAD (a content-equivalent rebase) instead
            // of falling back to ❗ WARNING on equal dates.
            delay(1200)

            // Rewrite locally as [one, three] (drop "two"). The harness recognizes "three" exists
            // already, but its parent ("two") no longer matches HEAD, so it cherry-picks it onto
            // "one". The result has a fresh SHA but identical content, which is exactly the "I
            // rebased my stack locally" scenario.
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
                        }
                    }
                    checkout = "development"
                }
            )

            renderAndAppendStatus(testInfo)
        }
    }

    @Test
    @Order(9)
    fun `compare highlight - arrows`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.ARROWS)
    }

    @Test
    @Order(10)
    fun `compare highlight - asterisk on newer`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.ASTERISK)
    }

    @Test
    @Order(11)
    fun `compare highlight - dim older`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.DIM_OLDER)
    }

    @Test
    @Order(12)
    fun `compare highlight - bold newer`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.BOLD_NEWER)
    }

    @Test
    @Order(13)
    fun `compare highlight - asterisk + dim older`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.ASTERISK_AND_DIM)
    }

    @Test
    @Order(14)
    fun `compare highlight - bold newer + asterisk + dim older`(testInfo: TestInfo) {
        appendComparePreview(testInfo, CompareHighlight.BOLD_ASTERISK_DIM)
    }

    private fun appendComparePreview(testInfo: TestInfo, highlight: CompareHighlight) {
        val rows =
            listOf(
                CompareRow(
                    index = 1,
                    local = CompareCommit("a000001", "dev-00"),
                    remote = CompareCommit("b000001", "dev-00"),
                    relation = CompareRelation.IDENTICAL,
                ),
                CompareRow(
                    index = 2,
                    local = CompareCommit("a000002", "dev-01-amended"),
                    remote = CompareCommit("b000002", "dev-01"),
                    relation = CompareRelation.DIVERGED_LOCAL_NEWER,
                ),
                CompareRow(
                    index = 3,
                    local = CompareCommit("a000003", "dev-02"),
                    remote = CompareCommit("b000003", "dev-02-amended"),
                    relation = CompareRelation.DIVERGED_REMOTE_NEWER,
                ),
                CompareRow(
                    index = 4,
                    local = CompareCommit("a000004", "dev-03"),
                    remote = CompareCommit("b000004", "dev-03"),
                    relation = CompareRelation.IDENTICAL,
                ),
                CompareRow(
                    index = 5,
                    local = CompareCommit("a000005", "dev-04-experimental"),
                    remote = null,
                    relation = CompareRelation.LOCAL_ONLY,
                ),
                CompareRow(
                    index = 6,
                    local = null,
                    remote = CompareCommit("b000006", "dev-05-from-coworker"),
                    relation = CompareRelation.REMOTE_ONLY,
                ),
            )
        val rendered =
            renderComparePreview(
                rows = rows,
                stackName = "preview-stack",
                remoteName = "origin",
                highlight = highlight,
                theme = DefaultTheme,
            )
        val name = testInfo.testMethod.get().name
        val banner = "\u001B[1;35m══ $name ══\u001B[0m"
        sharedOutputFile.appendText("\n$banner\n\n$rendered")
        logger.info(
            "Appended {} ({} bytes) to {}",
            name,
            rendered.length,
            sharedOutputFile.absolutePath,
        )
    }

    private suspend fun GitHubTestHarness.renderAndAppendStatus(
        testInfo: TestInfo,
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
    ) {
        val ansi = gitJaspr.getStatusString(refSpec, DefaultTheme)
        val name = testInfo.testMethod.get().name
        val banner = "\u001B[1;35m══ $name ══\u001B[0m"
        sharedOutputFile.appendText("\n$banner\n\n$ansi")
        logger.info(
            "Appended {} ({} bytes) to {}",
            name,
            ansi.length,
            sharedOutputFile.absolutePath,
        )
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
