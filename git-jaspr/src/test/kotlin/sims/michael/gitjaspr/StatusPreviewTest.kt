package sims.michael.gitjaspr

import java.io.File
import kotlinx.coroutines.delay
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.githubtests.GitHubTestHarness
import sims.michael.gitjaspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitjaspr.githubtests.generatedtestdsl.testCase

/**
 * Preview-only tests that render `jaspr status` with the colored [DefaultTheme] for every distinct
 * stack/remote-divergence rendering branch in [GitJaspr.appendNamedStackInfo] and
 * [GitJaspr.appendRemoteOnlyCommits]. Intended for eyeballing the rendered output in a real
 * terminal without setting up a real GitHub remote.
 *
 * All scenarios in a class run append (with a labeled header) to a single shared file at
 * `$TMPDIR/jaspr-status-preview.ansi`. The file is reset at the start of each class run.
 *
 * Workflow:
 * 1. Temporarily remove `@Disabled` on the class (or on a specific method) and run it.
 * 2. `cat "${TMPDIR%/}/jaspr-status-preview.ansi"` in your terminal to see the renderings with
 *    colors. Suggested alias: `alias jsp='cat "${TMPDIR%/}/jaspr-status-preview.ansi"'`.
 *
 * Scenarios cover all visually distinct paths:
 * - [previewUpToDate]: "up-to-date" headline, no remote-only section.
 * - [previewAheadOnly]: "ahead by N" headline, no remote-only section.
 * - [previewBehindOnlyRecent]: "behind by N" headline, remote-only section in warning style.
 * - [previewDivergedNoRemoteOnly]: "diverged" headline, no remote-only section. Local has a newer
 *   SHA than the remote for the same commit-id (rebased locally) and its content has changed, so
 *   the per-commit "pushed" indicator renders as 🔀 DIVERGENT.
 * - [previewDivergedRemoteAmended]: "diverged" headline, no remote-only section. Remote has a newer
 *   SHA than the local for the same commit-id and the content was amended on the remote, so the
 *   per-commit "pushed" indicator renders as 🔀 DIVERGENT.
 * - [previewDivergedLocalOlder]: "diverged" headline; the remote-only commit is newer than the
 *   local fork, so the remote-only section renders in warning style.
 * - [previewDivergedLikelyStale]: "diverged" headline; the local fork is newer than the remote-only
 *   commit, so the remote-only section renders in muted "(likely stale)" style.
 *
 * The behind-only-stale combination is omitted: its remote-only-section styling is already covered
 * by [previewDivergedLikelyStale], and it requires backdating commits to construct.
 */
@Disabled("Preview-only. Remove this annotation (or @Disabled on a specific method) to render.")
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
    fun previewUpToDate(testInfo: TestInfo) {
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
    fun previewAheadOnly(testInfo: TestInfo) {
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
    fun previewBehindOnlyRecent(testInfo: TestInfo) {
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
    fun previewDivergedNoRemoteOnly(testInfo: TestInfo) {
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
            // strictly greater than the remote's. Date order doesn't change the DIVERGENT
            // classification itself, but it keeps the headline rendering consistent across runs.
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
                            title = "two_amended"
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
    fun previewDivergedRemoteAmended(testInfo: TestInfo) {
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
            // strictly greater than the local "two". Date order doesn't change the DIVERGENT
            // classification itself, but it keeps the headline rendering consistent across runs.
            delay(1200)

            // Pass 2: create a sibling of "two" (also a child of "one") with the same commit-id
            // but a fresher SHA + content. Force-pushed to the per-commit ref and the named-stack
            // ref to simulate another contributor amending the commit and pushing while we still
            // have the older local version. Local "development" stays at the original "two", so
            // the per-commit row shows 🔀 DIVERGENT and the headline reads "diverged".
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
                                    title = "two_remote_amended"
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
    fun previewDivergedLocalOlder(testInfo: TestInfo) {
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
    fun previewDivergedLikelyStale(testInfo: TestInfo) {
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
