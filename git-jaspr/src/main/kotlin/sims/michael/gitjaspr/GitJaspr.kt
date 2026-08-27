package sims.michael.gitjaspr

import java.io.File
import java.io.RandomAccessFile
import java.time.ZonedDateTime
import java.util.SortedSet
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitParsers.getSubjectAndBodyFromFullMessage
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.COMMENT
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.EMPTY
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.FAIL
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.PENDING
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.SUCCESS
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.WARNING
import sims.michael.gitjaspr.RemoteRefEncoding.REV_NUM_DELIMITER
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef

/**
 * Result of a `jaspr up` / `jaspr top` invocation. Lets the caller distinguish between "no nav
 * session existed", "cursor moved inside the stack", and "cursor reached the top and the source
 * branch was restored" without having to read state from disk.
 */
sealed interface NavMoveResult {
    data object NoSession : NavMoveResult

    data class MovedWithin(val state: NavState) : NavMoveResult

    /**
     * Cursor reached the top of the stack and the session ended. [restoredName] is the resolved
     * named-stack name when known, falling back to the local branch name; it's the identifier to
     * surface in user-facing "back on `X`" messaging. [finalState] is the fully materialized stack
     * with the cursor pinned at the top, kept only so the caller can render a closing "here's where
     * you landed" position display for a session that has otherwise ended.
     */
    data class ReachedTop(
        val replayedCount: Int,
        val restoredName: String,
        val finalState: NavState,
    ) : NavMoveResult
}

/**
 * Outcome of `jaspr unsplit`. See ADR-0005 for the design rationale.
 *
 * Three variants:
 * - [Restored] : fold mode, or clean replay (cherry-pick). No follow-up messaging needed.
 * - [RestoredWithAutoResolvedConflicts] : replay mode took the `-X theirs` branch on one or more
 *   content conflicts; the caller should warn the operator and surface the backup ref (and stash,
 *   if one was created).
 * - [LeftInProgress] : replay mode's cherry-pick stopped on a path-level conflict the strategy
 *   could not auto-resolve. The cherry-pick is left in progress; the operator resolves manually or
 *   runs `jaspr nav cancel` to abort.
 */
sealed interface UnsplitOutcome {
    /** Fold success or clean replay: nothing extra to surface. */
    data class Restored(val restoredCommit: Commit) : UnsplitOutcome

    /**
     * Replay mode auto-resolved content conflicts using `-X theirs`. [conflictingPaths] lists each
     * file whose content was resolved this way (one entry per path, regardless of how many stages
     * contributed). [backupRef] points at HEAD's value before unsplit ran, suitable for `git reset
     * --hard <ref>` recovery. [stashRef] is the `refs/jaspr-backup/` ref under which the dirty
     * working tree was stashed (recoverable via `git stash apply <ref>`), or null when the working
     * tree was clean.
     */
    data class RestoredWithAutoResolvedConflicts(
        val restoredCommit: Commit,
        val conflictingPaths: List<String>,
        val backupRef: String,
        val stashRef: String?,
    ) : UnsplitOutcome

    /**
     * Replay mode's cherry-pick stopped on a path-level conflict (modify/delete, rename/rename,
     * type-change) that `-X theirs` could not auto-resolve. `.git/CHERRY_PICK_HEAD` is present and
     * the working tree contains conflict markers. Split state is intentionally NOT cleared so
     * `jaspr nav cancel` can find the in-flight session and abort cleanly.
     *
     * [backupRef] points at HEAD's value before unsplit ran. [stashRef] is the `refs/jaspr-backup/`
     * ref under which the dirty working tree was stashed (recoverable via `git stash apply <ref>`),
     * or null when the working tree was clean.
     */
    data class LeftInProgress(
        val originalCommit: Commit,
        val backupRef: String,
        val stashRef: String?,
    ) : UnsplitOutcome
}

class GitJaspr(
    private val ghClient: GitHubClient,
    private val gitClient: GitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
    private val commitIdentOverride: Ident? = null,
    private val renderer: Renderer = NoOpRenderer,
    private val json: Json = Json { prettyPrint = true },
) {

    private val logger = LoggerFactory.getLogger(GitJaspr::class.java)

    /**
     * Abstracts external interactions needed by [getStatusString] so the rendering can be driven by
     * fake data (i.e., for theme previews) without requiring a live git repository.
     *
     * This is basically an intersection of parts of [GitHubClient] and [GitClient].
     */
    interface StatusQueries {
        fun getRemoteBranches(): List<RemoteBranch>

        fun getCommitStack(localRef: String, remoteRef: String): List<Commit>

        fun logRange(since: String, until: String): List<Commit>

        fun getCommitIdsInRange(target: String, refs: List<String>): Map<String, List<String>>

        suspend fun getPullRequests(commits: List<Commit>): List<PullRequest>
    }

    private fun defaultStatusQueries() =
        object : StatusQueries {
            override fun getRemoteBranches() = gitClient.getRemoteBranches(config.remoteName)

            override fun getCommitStack(localRef: String, remoteRef: String) =
                gitClient.getCommitStack(config.remoteName, localRef, remoteRef)

            override fun logRange(since: String, until: String) = gitClient.logRange(since, until)

            override fun getCommitIdsInRange(target: String, refs: List<String>) =
                gitClient.getCommitIdsInRange(target, refs)

            override suspend fun getPullRequests(commits: List<Commit>) =
                ghClient.getPullRequests(commits)
        }

    suspend fun getStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theme: Theme = MonoTheme,
    ): String {
        gitClient.fetch(config.remoteName)
        return getStatusString(refSpec, theme, defaultStatusQueries())
    }

    suspend fun getStatusString(
        refSpec: RefSpec,
        remoteBranches: List<RemoteBranch>,
        theme: Theme = MonoTheme,
    ): String {
        val queries = defaultStatusQueries()
        return getStatusString(
            refSpec,
            theme,
            object : StatusQueries by queries {
                override fun getRemoteBranches() = remoteBranches
            },
        )
    }

    suspend fun getStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theme: Theme = MonoTheme,
        queries: StatusQueries,
    ): String {
        logger.trace("getStatusString {}", refSpec)
        val remoteName = config.remoteName

        // During a nav session HEAD is detached at the cursor, so walking from HEAD only sees
        // the cursor commit and its ancestors -- a prefix of the real stack. Walk from the
        // pre-nav branch instead so divergence math operates on the full local stack.
        val navState = if (isNavSessionActive()) readNavState() else null
        val effectiveRefSpec =
            if (navState != null) refSpec.copy(localRef = navState.headBeforeDetach) else refSpec

        val remoteBranches = queries.getRemoteBranches()
        val stack = queries.getCommitStack(effectiveRefSpec.localRef, effectiveRefSpec.remoteRef)
        if (stack.isEmpty()) return theme.muted("Stack is empty.") + "\n"

        val statuses = getRemoteCommitStatuses(stack, remoteBranches, queries)
        val commitsWithDuplicateIds =
            statuses
                .filter { status -> status.localCommit.id != null }
                .groupingBy { status -> checkNotNull(status.localCommit.id) }
                .aggregate { _, accumulator: List<RemoteCommitStatus>?, element, _ ->
                    accumulator.orEmpty() + element
                }
                .filter { (_, statuses) -> statuses.size > 1 }

        val numCommitsBehindBase =
            queries.logRange(stack.last().hash, "$remoteName/${effectiveRefSpec.remoteRef}").size
        return buildStatusString(
            statuses,
            commitsWithDuplicateIds,
            numCommitsBehindBase,
            effectiveRefSpec,
            stack,
            remoteBranches,
            queries,
            theme,
            navState,
        )
    }

    private fun buildStatusString(
        statuses: List<RemoteCommitStatus>,
        commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>,
        numCommitsBehindBase: Int,
        refSpec: RefSpec,
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        queries: StatusQueries,
        theme: Theme,
        navState: NavState?,
    ): String = buildString {
        if (navState != null) appendLine(navBanner(navState, theme))
        append(theme.heading(HEADER))

        val cursorSha = navState?.stack?.getOrNull(navState.cursorIndex)?.sha

        val stackChecks =
            if (numCommitsBehindBase != 0) {
                // If the stack is out-of-date, no commits are mergeable
                List(statuses.size) { false }
            } else {
                statuses.fold(emptyList()) { currentStack, status ->
                    val allFlagsAreSuccess =
                        status.toStatusList(commitsWithDuplicateIds).all {
                            it == SUCCESS || it == COMMENT
                        }
                    val currentStackIsAllTrue = currentStack.all { it }
                    currentStack + (currentStackIsAllTrue && allFlagsAreSuccess)
                }
            }

        for ((status, stackCheck) in statuses.reversed().zip(stackChecks.reversed())) {
            val isCursor = navState != null && status.localCommit.hash == cursorSha
            val lineContent = buildString {
                append("[")
                val flags = status.toStatusList(commitsWithDuplicateIds)
                val statusList = flags + if (stackCheck) SUCCESS else EMPTY
                // On the cursor row, skip styles that use ANSI intensity (dim) -- their inner
                // resets fight the outer bold wrap and the result reads as visually uneven.
                // Specifically: theme.muted on EMPTY and theme.hash on the SHA both use dim.
                append(
                    statusList.joinToString(separator = "") { flag ->
                        if (isCursor && flag == EMPTY) flag.emoji else flag.styledEmoji(theme)
                    }
                )
                append("] ")
                append(
                    if (isCursor) status.localCommit.hash else theme.hash(status.localCommit.hash)
                )
                append(" : ")
                val permalink = status.pullRequest?.permalink
                if (permalink != null) {
                    append(theme.url(status.pullRequest.permalink))
                    append(" : ")
                }
                val subject = status.localCommit.shortMessage
                val truncatedSubject = truncateSubject(subject, MAX_STATUS_SUBJECT_LENGTH)
                append(theme.value(truncatedSubject))
            }
            appendLine(if (isCursor) theme.emphasis(lineContent) else lineContent)
        }

        appendNamedStackInfo(stack, remoteBranches, theme, queries)

        if (numCommitsBehindBase > 0) {
            appendLine()
            appendLine(
                theme.warning(
                    "Your stack is out-of-date with the base branch " +
                        "($numCommitsBehindBase ${commitOrCommits(numCommitsBehindBase)} behind ${refSpec.remoteRef})."
                )
            )
            append("You'll need to rebase it (")
            append(theme.command("`jaspr rebase`"))
            append(") ")
            appendLine("before your stack will be mergeable.")
        }
        if (commitsWithDuplicateIds.isNotEmpty()) {
            appendLine()
            appendLine(theme.error("Some commits in your local stack have duplicate IDs:"))
            for ((id, statusList) in commitsWithDuplicateIds) {
                appendLine(
                    "- $id: (${statusList.joinToString(", ") { it.localCommit.shortMessage }})"
                )
            }
            appendLine(
                "This is likely because you've based new commit messages off of those from other commits."
            )
            appendLine(
                "Please correct this by amending the commits and deleting the commit-id lines, then retry your operation."
            )
        }
    }

    private fun StringBuilder.appendNamedStackInfo(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        theme: Theme,
        queries: StatusQueries,
    ) {
        val remoteName = config.remoteName
        data class NamedStackInfo(
            val name: String,
            val numCommitsAhead: Int,
            val numCommitsBehind: Int,
        )
        val stackSearchResult = getExistingStackName(stack, remoteBranches, queries)
        if (stackSearchResult is MultipleStacksContainCommit) {
            appendLine()
            appendLine(
                theme.warning(
                    "Stack name could not be determined: commits exist in multiple stacks: " +
                        stackSearchResult.stackNames.joinToString(", ") { theme.entity(it) }
                )
            )
        }
        val stackName = (stackSearchResult as? Found)?.name
        if (stackName != null) {
            val headStackCommit = stack.last().hash
            val trackingBranch = "$remoteName/$stackName"
            val namedStackRef =
                checkNotNull(
                    RemoteNamedStackRef.parse(stackName, config.remoteNamedStackBranchPrefix)
                )
            val namedStackInfo =
                NamedStackInfo(
                    namedStackRef.stackName,
                    numCommitsAhead = queries.logRange(trackingBranch, headStackCommit).size,
                    numCommitsBehind = queries.logRange(headStackCommit, trackingBranch).size,
                )
            with(namedStackInfo) {
                appendLine()
                appendLine("Stack name: ${theme.entity(name)}")
                appendLine(
                    if (numCommitsBehind == 0 && numCommitsAhead == 0) {
                        theme.success(
                            "Your stack is up to date with the remote stack in '$remoteName'."
                        )
                    } else if (numCommitsBehind > 0 && numCommitsAhead == 0) {
                        theme.warning(
                            "Your stack is behind the remote stack in '$remoteName' by " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)}. " +
                                "Run `jaspr pull` to incorporate them."
                        )
                    } else if (numCommitsBehind == 0) { // && numCommitsAhead > 0
                        theme.warning(
                            "Your stack is ahead of the remote stack in '$remoteName' by " +
                                "$numCommitsAhead ${commitOrCommits(numCommitsAhead)}. " +
                                "Run `jaspr push` to publish them."
                        )
                    } else { // numBehind > 0 && numCommitsAhead > 0
                        theme.warning(
                            "Your stack and the remote stack in '$remoteName' have diverged " +
                                "($numCommitsAhead ${commitOrCommits(numCommitsAhead)} ahead, " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)} behind). " +
                                "Run `jaspr compare` to see what's different."
                        )
                    }
                )
            }
            appendUniqueCommitsSummary(stack, stackName, theme, queries)
        }
    }

    /**
     * Emits a single-line summary pointing at `jaspr compare` when the local and remote stacks have
     * commit-ids unique to one side. Counts are commit-id set differences (not SHA-level); a
     * content-divergent amendment with the same commit-id on both sides doesn't trigger output. Use
     * `jaspr compare` to see commit-level divergence detail.
     */
    private fun StringBuilder.appendUniqueCommitsSummary(
        localStack: List<Commit>,
        stackName: String,
        theme: Theme,
        queries: StatusQueries,
    ) {
        val remoteName = config.remoteName
        val targetRef =
            RemoteNamedStackRef.parse(stackName, config.remoteNamedStackBranchPrefix)?.targetRef
                ?: return
        val remoteStack =
            try {
                queries.getCommitStack("$remoteName/$stackName", targetRef)
            } catch (e: Exception) {
                logger.debug("Failed to walk remote stack '{}': {}", stackName, e.message)
                return
            }
        val localIds = localStack.mapNotNull(Commit::id).toSet()
        val remoteIds = remoteStack.mapNotNull(Commit::id).toSet()
        val remoteOnly = (remoteIds - localIds).size
        val localOnly = (localIds - remoteIds).size
        if (remoteOnly == 0 && localOnly == 0) return

        val parts = buildList {
            if (remoteOnly > 0) add("$remoteOnly remote-only ${commitOrCommits(remoteOnly)}")
            if (localOnly > 0) {
                add("$localOnly local ${commitOrCommits(localOnly)} not yet on remote")
            }
        }
        appendLine()
        appendLine(theme.warning("! ${parts.joinToString(", ")}. Run `jaspr compare` for details."))
    }

    /**
     * Banner shown at the top of nav-aware command output. Position counts from the top (so the tip
     * is `[1/N]` and the base is `[N/N]`) to match the row labels in `jaspr compare`. Rendered with
     * [Theme.entity] (cyan in [DefaultTheme]) to contrast with the bold flag-key / column header
     * without being a warning color.
     *
     * The stack identifier is the resolved named-stack name when available, falling back to the
     * local branch name. Once the stack has been pushed, the fallback should rarely trigger.
     */
    private fun navBanner(state: NavState, theme: Theme): String {
        val positionFromTop = state.stack.size - state.cursorIndex
        val name = state.stackName ?: state.headBeforeDetach
        return theme.entity("Navigating $name [$positionFromTop/${state.stack.size}]")
    }

    /**
     * Lightweight position display printed after a nav move. Reads commit messages from local git
     * (no network, no PR fetch). Use this instead of [getStatusString] when the caller only needs
     * to confirm "where am I in the stack" rather than full PR/check status.
     *
     * Rows are top-first to match `jaspr compare`. Three visual zones:
     * - The cursor row is prefixed with `→` and wrapped in [Theme.emphasis] (bold).
     * - Above-cursor rows are wrapped in [Theme.muted] (dim) to signal that they're unreachable
     *   from HEAD; they'll be cherry-picked back in on `jaspr up` / `jaspr top`.
     * - Below-cursor (already-traversed) rows are unstyled.
     *
     * The SHA is never styled with [Theme.hash] here so each row reads at a single intensity level.
     * This is a deliberate departure from [getStatusString], which uses [Theme.hash] to signal
     * "secondary info" -- the position display is a "where am I" view where per-zone consistency
     * matters more than the SHA-as-secondary convention.
     *
     * [banner] overrides the leading line. It defaults to the live "Navigating name [pos/total]"
     * header; callers rendering the closing view after a session has ended (reached the top) pass a
     * "back on `branch`" header instead, since "Navigating" would be misleading there.
     */
    fun getNavPositionString(
        state: NavState,
        theme: Theme,
        banner: String = navBanner(state, theme),
    ): String = buildString {
        appendLine(banner)
        for (originalIndex in state.stack.lastIndex downTo 0) {
            val entry = state.stack[originalIndex]
            val commit = gitClient.log(entry.sha, 1).single()
            val displayIndex = state.stack.size - originalIndex
            val isCursor = originalIndex == state.cursorIndex
            val isAboveCursor = originalIndex > state.cursorIndex
            val prefix = if (isCursor) "→ " else "  "
            val sha7 = entry.sha.take(7)
            val line = "$prefix[$displayIndex]  $sha7  ${commit.shortMessage}"
            appendLine(
                when {
                    isCursor -> theme.emphasis(line)
                    isAboveCursor -> theme.muted(line)
                    else -> line
                }
            )
        }
    }

    data class StackNameSuggestions(
        val candidates: List<String>,
        val ambiguousStackNames: List<String> = emptyList(),
    )

    /**
     * Snapshot of state required to execute [push]. Produced by [getPushPlan] so that the CLI flow,
     * which calls [getPushPlan] once to drive the stack-name prompt and again would otherwise
     * re-derive the same state inside [push], can pay the cost once and reuse the result.
     */
    data class PushPlan(
        val refSpec: RefSpec,
        val count: Int?,
        val stack: List<Commit>,
        val excludedCommits: List<Commit>,
        val remoteBranches: List<RemoteBranch>,
        val stackSearchResult: NamedStackSearchResult,
        val stackNameSuggestions: StackNameSuggestions,
    )

    sealed class NamedStackSearchResult

    data class Found(val name: String) : NamedStackSearchResult()

    data class MultipleStacksContainCommit(val stackNames: List<String>) : NamedStackSearchResult()

    data object NotFound : NamedStackSearchResult()

    /**
     * Returns the full name (including the named stack prefix) of an existing named stack that
     * "owns" the given [stack], or null.
     *
     * Ownership is defined by a commit in the given [stack] being contained in exactly one named
     * stack. A commit contained in multiple stacks has ambiguous ownership, and this returns null
     * for such stacks.
     */
    private fun getExistingStackName(stack: List<Commit>): NamedStackSearchResult =
        getExistingStackName(stack, gitClient.getRemoteBranches(config.remoteName))

    private fun getExistingStackName(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
    ): NamedStackSearchResult = getExistingStackName(stack, remoteBranches, defaultStatusQueries())

    private fun getExistingStackName(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        queries: StatusQueries,
    ): NamedStackSearchResult {
        logger.trace("getExistingStackName")
        require(stack.isNotEmpty())

        val remoteName = config.remoteName
        val existingNamedStacks = remoteBranches.mapNotNull { branch ->
            RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)?.let { ref
                ->
                branch to ref
            }
        }

        // Build a map of commit-id to a list of named-stack branches that contain the commit with
        // that ID. Named stacks are grouped by their target ref so each group can be walked in a
        // single git invocation (one shared JGit repo open, no per-commit Commit-object
        // allocation).
        val branchesByCommitId: Map<String, List<RemoteBranch>> =
            existingNamedStacks
                .groupBy({ (_, ref) -> ref.targetRef }, { (branch, _) -> branch })
                .flatMap { (targetRef, branches) ->
                    val branchByRefKey = branches.associateBy { "$remoteName/${it.name}" }
                    queries
                        .getCommitIdsInRange("$remoteName/$targetRef", branchByRefKey.keys.toList())
                        .flatMap { (refKey, commitIds) ->
                            val branch = checkNotNull(branchByRefKey[refKey])
                            commitIds.map { commitId -> commitId to branch }
                        }
                }
                .groupBy({ (commitId, _) -> commitId }, { (_, branch) -> branch })

        // Find the first commit (walking from stack tip) that is contained in exactly one named
        // stack and return its name.
        val result =
            stack
                .reversed()
                .filter { commit -> commit.id != null }
                .firstNotNullOfOrNull { commit ->
                    val stacksWithCommit = branchesByCommitId[checkNotNull(commit.id)].orEmpty()
                    if (stacksWithCommit.size == 1) {
                        Found(stacksWithCommit.single().name)
                    } else if (stacksWithCommit.size > 1) {
                        // Because of `firstNotNullOfOrNull`, hitting this will abort the search.
                        // Any remaining commits are also contained in multiple named stacks
                        MultipleStacksContainCommit(
                            stacksWithCommit.mapNotNull { branch ->
                                RemoteNamedStackRef.parse(
                                        branch.name,
                                        config.remoteNamedStackBranchPrefix,
                                    )
                                    ?.stackName
                            }
                        )
                    } else {
                        null // Continue searching
                    }
                }

        return (result ?: NotFound).also { result ->
            logger.trace("getExistingStackName: {}", result)
        }
    }

    fun getCompareString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theme: Theme = MonoTheme,
    ): String {
        logger.trace("getCompareString {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val remoteBranches = gitClient.getRemoteBranches(remoteName)

        // During a nav session HEAD is detached at the cursor, which would collapse the LOCAL
        // column to just the cursor's prefix. Walk from the pre-nav branch instead.
        val navState = if (isNavSessionActive()) readNavState() else null
        val effectiveLocalRef = navState?.headBeforeDetach ?: refSpec.localRef
        val cursorSha = navState?.stack?.getOrNull(navState.cursorIndex)?.sha

        val localStack = gitClient.getCommitStack(remoteName, effectiveLocalRef, refSpec.remoteRef)
        if (localStack.isEmpty()) return theme.muted("Stack is empty.") + "\n"

        val stackName =
            when (val result = getExistingStackName(localStack, remoteBranches)) {
                is Found -> result.name
                is MultipleStacksContainCommit ->
                    throw GitJasprException(
                        "Cannot compare: commits exist in multiple stacks: " +
                            result.stackNames.joinToString(", ")
                    )
                NotFound ->
                    throw GitJasprException(
                        "No remote stack to compare against. Push first with `jaspr push`."
                    )
            }
        val namedStackRef =
            checkNotNull(RemoteNamedStackRef.parse(stackName, config.remoteNamedStackBranchPrefix))
        val remoteStack =
            gitClient.getCommitStack(remoteName, "$remoteName/$stackName", namedStackRef.targetRef)

        return DivergenceClassifier(getJasprDir(), gitClient).use { classifier ->
            val rows = alignStacks(localStack, remoteStack, classifier)
            buildString {
                if (navState != null) appendLine(navBanner(navState, theme))
                append(renderCompare(rows, "$remoteName/$stackName", theme, cursorSha = cursorSha))
            }
        }
    }

    /**
     * Resolves the set of refs `jaspr graph` should pass to `git log`: the local stack tip, the
     * remote target, and the remote named-stack ref when exactly one can be determined. Ambiguous
     * (multi-stack) or absent named-stack refs are skipped silently so callers can use the result
     * unconditionally.
     */
    fun graphRefs(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ): List<String> {
        logger.trace("graphRefs {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val refs = buildList {
            add(refSpec.localRef)
            add("$remoteName/${refSpec.remoteRef}")

            val localStack =
                try {
                    gitClient.getCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
                } catch (e: Exception) {
                    logger.debug("Failed to walk local stack for graph: {}", e.message)
                    return@buildList
                }
            if (localStack.isEmpty()) return@buildList

            val remoteBranches = gitClient.getRemoteBranches(remoteName)
            val stackName =
                (getExistingStackName(localStack, remoteBranches) as? Found)?.name
                    ?: return@buildList
            add("$remoteName/$stackName")
        }
        return refs.distinct()
    }

    /**
     * Computes a [PullPlan] for the local stack and executes the no-op, punt, and reset-to-remote
     * paths. Cherry-pick paths are not yet implemented; they throw [UnsupportedOperationException]
     * and will be filled in by a follow-up commit. Returns the user-facing output string.
     *
     * See `doc/adr/0003-pull-command-scope.md` for the decision tree.
     */
    fun pull(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theirs: Boolean = false,
        theme: Theme = MonoTheme,
    ): String {
        logger.trace("pull {} theirs={}", refSpec, theirs)

        checkNoOperationInProgress()

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val remoteBranches = gitClient.getRemoteBranches(remoteName)
        val localStack = gitClient.getCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (localStack.isEmpty()) return theme.muted("Stack is empty; nothing to pull.") + "\n"

        val stackName =
            when (val result = getExistingStackName(localStack, remoteBranches)) {
                is Found -> result.name
                is MultipleStacksContainCommit ->
                    throw GitJasprException(
                        "Cannot pull: commits exist in multiple stacks: " +
                            result.stackNames.joinToString(", ")
                    )
                NotFound ->
                    throw GitJasprException(
                        "No remote stack to pull from. Push first with `jaspr push`."
                    )
            }
        val namedStackRef =
            checkNotNull(RemoteNamedStackRef.parse(stackName, config.remoteNamedStackBranchPrefix))
        val remoteStackRef = "$remoteName/$stackName"
        val remoteStack =
            gitClient.getCommitStack(remoteName, remoteStackRef, namedStackRef.targetRef)
        val remoteTipSha = gitClient.log(remoteStackRef, 1).single().hash

        val targetRefFull = "$remoteName/${namedStackRef.targetRef}"
        val localBase = gitClient.mergeBase(refSpec.localRef, targetRefFull)
        val remoteBase = gitClient.mergeBase(remoteStackRef, targetRefFull)
        val baseRelation = computeBaseRelation(localBase, remoteBase)

        val divergedCommitIds =
            DivergenceClassifier(getJasprDir(), gitClient).use { classifier ->
                computeDivergedCommitIds(localStack, remoteStack, classifier)
            }

        if (theirs && divergedCommitIds.isNotEmpty()) {
            return resolveTheirsAndPull(
                localStack,
                remoteStack,
                divergedCommitIds,
                baseRelation,
                remoteStackRef,
                refSpec,
                targetRefFull,
                theme,
            )
        }

        val plan =
            getPullPlan(localStack, remoteStack, remoteTipSha, baseRelation, divergedCommitIds)
        return executePullPlan(plan, theme)
    }

    /**
     * Resolves DIVERGED rows by replacing each local diverged commit with the remote's version of
     * that commit-id, then re-dispatches through [getPullPlan]. Probes the cherry-pick queue before
     * any destructive change; if the probe is clean, writes a recovery ref to
     * `refs/jaspr-backup/pre-pull-<unix-timestamp>` and does the work. Rolls back to the backup ref
     * if the re-dispatched plan punts or any subsequent step fails.
     */
    private fun resolveTheirsAndPull(
        localStack: List<Commit>,
        remoteStack: List<Commit>,
        divergedCommitIds: Set<String>,
        baseRelation: BaseRelation,
        remoteStackRef: String,
        refSpec: RefSpec,
        targetRefFull: String,
        theme: Theme,
    ): String {
        requireCleanWorkingTree()

        val remoteById = remoteStack.filter { it.id != null }.associateBy { checkNotNull(it.id) }
        val resolvedQueue = localStack.map { commit ->
            val id = commit.id
            if (id != null && id in divergedCommitIds) {
                checkNotNull(remoteById[id]) { "DIVERGED commit-id $id missing from remote" }
            } else {
                commit
            }
        }

        val localBase =
            gitClient.mergeBase(refSpec.localRef, targetRefFull)
                ?: throw GitJasprException("Could not resolve local base for --theirs resolution.")
        probeCherryPickQueue(resolvedQueue, localBase)

        val backupRef = createPullBackupRef()
        val rollbackMessage = "jaspr pull --theirs: rollback to $backupRef"
        try {
            gitClient.reset(localBase, reflogMessage = "jaspr pull --theirs: reset to base")
            for (commit in resolvedQueue) {
                gitClient.cherryPick(
                    commit,
                    reflogMessage = "jaspr pull --theirs: cherry-pick of ${commit.shortMessage}",
                )
            }
        } catch (e: Exception) {
            rollbackPull(backupRef, rollbackMessage)
            throw e
        }

        val newLocalStack =
            gitClient.getCommitStack(config.remoteName, refSpec.localRef, refSpec.remoteRef)
        val newRemoteTipSha = gitClient.log(remoteStackRef, 1).single().hash
        val newPlan =
            getPullPlan(newLocalStack, remoteStack, newRemoteTipSha, baseRelation, emptySet())

        if (newPlan is PullPlan.Punt) {
            rollbackPull(backupRef, rollbackMessage)
            throw GitJasprException(
                "Resolved divergence with --theirs, but pull still can't complete: " +
                    puntMessage(newPlan.reason) +
                    " Rolled back to $backupRef."
            )
        }

        val executionMessage =
            try {
                executePullPlan(newPlan, theme)
            } catch (e: Exception) {
                rollbackPull(backupRef, rollbackMessage)
                throw e
            }

        val n = divergedCommitIds.size
        return buildString {
            append(executionMessage)
            appendLine(
                theme.success(
                    "Adopted remote's version of $n diverged ${commitOrCommits(n)}. " +
                        "Backup ref saved: $backupRef. " +
                        "Recover with `git reset --hard $backupRef`."
                )
            )
        }
    }

    private fun computeBaseRelation(localBase: String?, remoteBase: String?): BaseRelation {
        if (localBase == null || remoteBase == null) return BaseRelation.UNRELATED
        if (localBase == remoteBase) return BaseRelation.EQUAL
        if (gitClient.isAncestor(localBase, remoteBase)) return BaseRelation.REMOTE_AHEAD
        if (gitClient.isAncestor(remoteBase, localBase)) return BaseRelation.LOCAL_AHEAD
        return BaseRelation.UNRELATED
    }

    private fun computeDivergedCommitIds(
        local: List<Commit>,
        remote: List<Commit>,
        classifier: DivergenceClassifier,
    ): Set<String> {
        val localById = local.filter { it.id != null }.associateBy { checkNotNull(it.id) }
        val remoteById = remote.filter { it.id != null }.associateBy { checkNotNull(it.id) }
        val sharedIds = localById.keys intersect remoteById.keys
        return sharedIds
            .filter { id ->
                val localCommit = checkNotNull(localById[id])
                val remoteCommit = checkNotNull(remoteById[id])
                classifier.classify(localCommit.hash, remoteCommit.hash) ==
                    DivergenceClassifier.Result.DIVERGENT
            }
            .toSet()
    }

    private fun executePullPlan(plan: PullPlan, theme: Theme): String = buildString {
        when (plan) {
            is PullPlan.NoOp -> appendLine(theme.muted(noOpMessage(plan.reason)))
            is PullPlan.Punt -> appendLine(theme.warning(puntMessage(plan.reason)))
            is PullPlan.HardResetToRemoteTip -> {
                requireCleanWorkingTree()
                gitClient.reset(
                    plan.remoteTipSha,
                    reflogMessage = "jaspr pull: reset to remote tip",
                )
                appendLine(theme.success("Pulled; your stack now matches remote."))
            }
            is PullPlan.CherryPickLoOntoRemoteTip -> {
                requireCleanWorkingTree()
                probeCherryPickQueue(plan.commits, plan.remoteTipSha)
                val backupRef = createPullBackupRef()
                val rollbackMessage = "jaspr pull: rollback to $backupRef"
                var skipped = 0
                try {
                    gitClient.reset(
                        plan.remoteTipSha,
                        reflogMessage = "jaspr pull: reset to remote tip",
                    )
                    for (commit in plan.commits) {
                        val result =
                            gitClient.cherryPick(
                                commit,
                                reflogMessage = "jaspr pull: cherry-pick of ${commit.shortMessage}",
                            )
                        if (result == null) skipped++
                    }
                } catch (e: Exception) {
                    rollbackPull(backupRef, rollbackMessage)
                    throw e
                }
                val applied = plan.commits.size - skipped
                appendLine(
                    theme.success(
                        "Adopted remote's version of the shared portion of your stack and " +
                            "replayed $applied local ${commitOrCommits(applied)} on top."
                    )
                )
                if (skipped > 0) {
                    appendLine(
                        theme.muted(
                            "Skipped $skipped ${commitOrCommits(skipped)} already in the target."
                        )
                    )
                }
            }
            is PullPlan.CherryPickRoOntoLocalHead -> {
                requireCleanWorkingTree()
                val headSha = gitClient.log(GitClient.HEAD, 1).single().hash
                probeCherryPickQueue(plan.commits, headSha)
                val backupRef = createPullBackupRef()
                val rollbackMessage = "jaspr pull: rollback to $backupRef"
                var skipped = 0
                try {
                    for (commit in plan.commits) {
                        val result =
                            gitClient.cherryPick(
                                commit,
                                reflogMessage = "jaspr pull: cherry-pick of ${commit.shortMessage}",
                            )
                        if (result == null) skipped++
                    }
                } catch (e: Exception) {
                    rollbackPull(backupRef, rollbackMessage)
                    throw e
                }
                val applied = plan.commits.size - skipped
                appendLine(
                    theme.success(
                        "Pulled $applied ${commitOrCommits(applied)} onto your local stack. " +
                            "Your stack base is ahead of remote's; push to bring remote in sync."
                    )
                )
                if (skipped > 0) {
                    appendLine(
                        theme.muted(
                            "Skipped $skipped ${commitOrCommits(skipped)} already in the target."
                        )
                    )
                }
            }
        }
    }

    /**
     * Probes whether the cherry-pick queue applies cleanly against [startingTreeIsh], using `git
     * merge-tree --write-tree` and threading each result tree forward as the "ours" for the next
     * merge. Throws on the first conflict it encounters. Performs no I/O against the working tree,
     * index, or HEAD; only writes intermediate trees into the object DB.
     */
    private fun probeCherryPickQueue(commits: List<Commit>, startingTreeIsh: String) {
        var currentTreeIsh = startingTreeIsh
        for ((hash, shortMessage) in commits) {
            val result = gitClient.mergeTreeWriteTree("$hash^", currentTreeIsh, hash)
            when (result) {
                is MergeTreeResult.Conflict ->
                    throw GitJasprException(
                        "Pull would conflict applying commit $hash " +
                            "($shortMessage). Resolve manually with " +
                            "`git cherry-pick` or `git rebase`, then re-run `jaspr pull`."
                    )
                is MergeTreeResult.Clean -> currentTreeIsh = result.treeSha
            }
        }
    }

    private fun requireCleanWorkingTree() {
        if (gitClient.hasUncommittedChangesToTrackedFiles()) {
            throw GitJasprException(
                "Your working directory has uncommitted changes to tracked files. " +
                    "Please commit or stash them and re-run the command."
            )
        }
    }

    private fun createPullBackupRef(): String {
        val currentHead = gitClient.log(GitClient.HEAD, 1).single().hash
        val backupRef = "refs/jaspr-backup/pre-pull-${System.currentTimeMillis() / 1000}"
        gitClient.updateRef(backupRef, currentHead)
        return backupRef
    }

    private fun rollbackPull(backupRef: String, reflogMessage: String) {
        if (gitClient.isCherryPickInProgress()) {
            gitClient.cherryPickAbort()
        }
        gitClient.reset(backupRef, reflogMessage = reflogMessage)
    }

    /**
     * Refuses to start `pull` when a cherry-pick, rebase, or merge is in progress. Detection is
     * filesystem-based: each operation drops a sentinel file (or directory) inside the
     * worktree-specific git dir.
     */
    private fun checkNoOperationInProgress() {
        val gitDir = gitClient.gitDir()
        val inProgress =
            when {
                gitDir.resolve("CHERRY_PICK_HEAD").exists() -> "cherry-pick"
                gitDir.resolve("MERGE_HEAD").exists() -> "merge"
                gitDir.resolve("rebase-merge").exists() -> "rebase"
                gitDir.resolve("rebase-apply").exists() -> "rebase"
                else -> null
            }
        if (inProgress != null) {
            throw GitJasprException(
                "Cannot pull: a $inProgress is in progress. " +
                    "Complete or abort it before re-running `jaspr pull`."
            )
        }
    }

    private fun noOpMessage(reason: NoOpReason): String =
        when (reason) {
            NoOpReason.UP_TO_DATE -> "Your stack is up to date with the remote."
            NoOpReason.LOCAL_AHEAD ->
                "Your stack is ahead of the remote. Run `jaspr push` to bring remote in sync."
            NoOpReason.LOCAL_HAS_UNPUSHED ->
                "Your stack has local commits not yet on the remote. " +
                    "Run `jaspr push` to bring remote in sync."
            NoOpReason.PURE_REORDERING ->
                "Your stack and the remote stack contain the same commits in a different " +
                    "order. Nothing for pull to ingest. Run `jaspr compare` to see the " +
                    "reordering."
        }

    private fun puntMessage(reason: PuntReason): String =
        when (reason) {
            PuntReason.DIVERGED ->
                "Pull won't auto-merge divergence (same commit-id, different content or " +
                    "message). Run `jaspr compare` to see what diverges, then use git " +
                    "directly."
            PuntReason.MIXED_UNIQUE_WORK ->
                "Your stack and the remote stack each have unique commits. Pull can't " +
                    "determine the correct ordering. Run `jaspr compare` to inspect, then " +
                    "use git directly."
            PuntReason.UNRELATED_BASES ->
                "Your stack and the remote stack are rooted on unrelated histories. " +
                    "Pull can't handle this. Use git directly to investigate."
        }

    /**
     * Build a [PushPlan] capturing the stack, remote branches, named-stack search result, and
     * stack-name suggestions for a future [push] call. The CLI flow drives the stack-name prompt
     * from the plan's suggestions and then passes the same plan back to [push], avoiding a second
     * round of fetch + stack walks.
     */
    fun getPushPlan(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        count: Int? = null,
    ): PushPlan {
        logger.trace("getPushPlan {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        fun getCommitStack() = gitClient.getCommitStack(remoteName, refSpec.localRef, targetRef)
        val originalStack = resolveCount(getCommitStack(), count)
        val stackWithIds =
            if (addCommitIdsToLocalStack(originalStack)) {
                resolveCount(getCommitStack(), count)
            } else {
                originalStack
            }

        val (stack, excludedCommits) = filterStackByDontPushPattern(stackWithIds)
        val remoteBranches = gitClient.getRemoteBranches(remoteName)
        val stackSearchResult =
            if (stack.isEmpty()) NotFound else getExistingStackName(stack, remoteBranches)
        val stackNameSuggestions = computeStackNameSuggestions(stack, stackSearchResult)
        return PushPlan(
            refSpec = refSpec,
            count = count,
            stack = stack,
            excludedCommits = excludedCommits,
            remoteBranches = remoteBranches,
            stackSearchResult = stackSearchResult,
            stackNameSuggestions = stackNameSuggestions,
        )
    }

    private fun computeStackNameSuggestions(
        stack: List<Commit>,
        stackSearchResult: NamedStackSearchResult,
    ): StackNameSuggestions {
        if (stack.isEmpty() || stackSearchResult is Found) return StackNameSuggestions(emptyList())
        val commitBasedCandidates =
            stack
                .map { commit -> StackNameGenerator.generateName(commit.shortMessage) }
                .filter(String::isNotEmpty)
                .distinct()
        return when (stackSearchResult) {
            is MultipleStacksContainCommit ->
                StackNameSuggestions(
                    candidates = stackSearchResult.stackNames + commitBasedCandidates,
                    ambiguousStackNames = stackSearchResult.stackNames,
                )
            else -> StackNameSuggestions(commitBasedCandidates)
        }
    }

    suspend fun push(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        stackName: String? = null,
        count: Int? = null,
        theme: Theme = MonoTheme,
        onAbandonedPrs: (List<PullRequest>) -> Boolean = { true },
    ) {
        logger.trace("push (plan first) {}", refSpec)
        if (gitClient.hasUncommittedChangesToTrackedFiles()) {
            throw GitJasprException(
                "Your working directory has uncommitted changes to tracked files. " +
                    "Please commit or stash them and re-run the command."
            )
        }
        push(
            plan = getPushPlan(refSpec, count),
            stackName = stackName,
            theme = theme,
            onAbandonedPrs = onAbandonedPrs,
        )
    }

    suspend fun push(
        plan: PushPlan,
        stackName: String? = null,
        theme: Theme = MonoTheme,
        onAbandonedPrs: (List<PullRequest>) -> Boolean = { true },
    ) {
        logger.trace("push (plan) {}", plan.refSpec)

        val refSpec = plan.refSpec
        val remoteName = config.remoteName
        val targetRef = refSpec.remoteRef
        val stack = plan.stack
        val excludedCommits = plan.excludedCommits
        val remoteBranches = plan.remoteBranches
        val stackSearchResult = plan.stackSearchResult

        showExcludedCommitsMessage(excludedCommits)
        if (stack.isEmpty()) {
            if (excludedCommits.isNotEmpty()) {
                renderer.info {
                    "All commits in the stack match the dont-push pattern. Nothing to push."
                }
            } else {
                renderer.info { "Stack is empty. Nothing to push." }
            }
            return
        }

        val filteredRefSpec = refSpec.copy(localRef = stack.last().hash)

        val commitsWithDuplicateIds =
            stack
                .filter { it.id != null }
                .groupingBy { checkNotNull(it.id) }
                .aggregate { _, accumulator: List<Commit>?, element, _ ->
                    accumulator.orEmpty() + element
                }
                .filter { (_, commits) -> commits.size > 1 }
        if (commitsWithDuplicateIds.isNotEmpty()) {
            renderer.run {
                error { "Refusing to push because some commits in your stack have duplicate IDs." }
                error { "Run `jaspr status` to see which commits are affected." }
            }
            return
        }

        val pullRequests =
            checkSinglePullRequestPerCommit(
                ghClient.getPullRequests(stack).filterByMatchingTargetRef()
            )
        val pullRequestsRebased =
            pullRequests.updateBaseRefForReorderedPrsIfAny(stack, filteredRefSpec.remoteRef)

        val remoteRefSpecs = remoteBranches.map { b -> b.toRefSpec() }
        val outOfDateBranches = stack.map { c -> c.toRefSpec() } - remoteRefSpecs.toSet()
        val revisionHistoryRefs =
            getRevisionHistoryRefs(
                stack,
                remoteBranches,
                remoteName,
                outOfDateBranches.map(RefSpec::remoteRef),
            )
        // Convert symbolic refs (i.e., HEAD) to short hash so our comparison matches below
        val localRef = gitClient.log(filteredRefSpec.localRef, 1).single().hash

        // Determine the effective stack name
        val existingStackName =
            (stackSearchResult as? Found)?.name?.let { existingBranchName ->
                checkNotNull(
                    RemoteNamedStackRef.parse(
                        existingBranchName,
                        config.remoteNamedStackBranchPrefix,
                    )
                )
            }

        val effectiveStackName =
            if (stackName != null) {
                RemoteNamedStackRef(stackName, targetRef, config.remoteNamedStackBranchPrefix)
            } else {
                checkNotNull(existingStackName) {
                    if (stackSearchResult is MultipleStacksContainCommit) {
                        "No stack name provided and commits exist in multiple stacks: " +
                            stackSearchResult.stackNames.joinToString(", ") +
                            ". Use --name to specify which stack to push to."
                    } else {
                        "No stack name provided and no existing stack name found on the remote."
                    }
                }
            }

        val prefixedStackName = effectiveStackName.name()

        val namedStackRefSpec = RefSpec(localRef, prefixedStackName)
        val outOfDateNamedStackBranch = listOfNotNull(namedStackRefSpec) - remoteRefSpecs.toSet()

        // Check for PRs that will be abandoned by this push.
        // See also: getAbandonedBranches() for the broader detection used by `clean`.
        val abandonedPrs =
            findPrsAbandonedByPush(remoteBranches, prefixedStackName, targetRef, stack)
        if (abandonedPrs.isNotEmpty() && !onAbandonedPrs(abandonedPrs)) {
            throw GitJasprException(
                "Push aborted: would abandon ${abandonedPrs.size} open pull " +
                    "${requestOrRequests(abandonedPrs.size)}."
            )
        }

        val refSpecs =
            outOfDateBranches.map(RefSpec::forcePush) +
                outOfDateNamedStackBranch.map(RefSpec::forcePush) +
                revisionHistoryRefs
        // Snapshot the pre-push remote values of the refs we're about to write, so we can undo
        // this push if PR creation turns out to be impossible (see the catch around the
        // PR-creation loop below).
        val priorRemoteShaByName = remoteBranches.associate { it.name to it.commit.hash }
        gitClient.push(refSpecs, config.remoteName)
        renderer.info {
            "Pushed %s commit %s, %s named stack %s, and %s history %s"
                .format(
                    outOfDateBranches.size,
                    refOrRefs(outOfDateBranches.size),
                    outOfDateNamedStackBranch.size,
                    refOrRefs(outOfDateNamedStackBranch.size),
                    revisionHistoryRefs.size,
                    refOrRefs(revisionHistoryRefs.size),
                )
        }

        val existingPrsByCommitId = pullRequestsRebased.associateBy(PullRequest::commitId)

        val isDraftRegex = "^(draft|wip)\\b.*$".toRegex(IGNORE_CASE)
        val remoteBranchesAfterPush = gitClient.getRemoteBranches(config.remoteName)
        val remoteBranchNames = remoteBranchesAfterPush.map(RemoteBranch::name)
        val prsToMutate =
            stack
                .windowedPairs()
                .map { (prevCommit, currentCommit) ->
                    val existingPr = existingPrsByCommitId[currentCommit.id]
                    PullRequest(
                        id = existingPr?.id,
                        commitId = currentCommit.id,
                        number = existingPr?.number,
                        headRefName = currentCommit.toRemoteRefName(),
                        // The base ref for the first commit in the stack (prevCommit == null) is
                        // the target branch (the branch the commit will ultimately merge into). The
                        // base ref for each successive commit is the remote ref name (i.e.,
                        // jaspr/<commit-id>) of the previous commit in the stack
                        baseRefName = prevCommit?.toRemoteRefName() ?: filteredRefSpec.remoteRef,
                        title = currentCommit.shortMessage,
                        body =
                            buildPullRequestBody(
                                currentCommit.fullMessage,
                                emptyList(),
                                existingPr,
                                remoteBranchNames,
                            ),
                        checksPass = existingPr?.checksPass,
                        approved = existingPr?.approved,
                        permalink = existingPr?.permalink,
                        isDraft = isDraftRegex.matches(currentCommit.shortMessage),
                    )
                }
                // Second pass to update descriptions with information about the stack
                .updateDescriptionsWithStackInfo(stack)
                .filter { pr -> existingPrsByCommitId[pr.commitId] != pr }

        try {
            for (pr in prsToMutate) {
                if (pr.id == null) {
                    // create the pull request
                    ghClient.createPullRequest(pr)
                } else {
                    // update the pull request
                    ghClient.updatePullRequest(pr)
                }
            }
        } catch (e: GitJasprException) {
            // PR creation/update failed. If the target branch is gone from the live remote, the
            // refs we just pushed cannot anchor a pull request (the first commit's PR is based on
            // the target, so this fails before any PR is created). Undo this push so the failed
            // attempt leaves nothing behind, and surface a clean, actionable error. Any other
            // failure is rethrown untouched: the pushed refs are valid and a re-run reconciles.
            if (gitClient.remoteBranchExists(config.remoteName, targetRef)) throw e
            rollBackPush(refSpecs, priorRemoteShaByName)
            throw GitJasprException(
                "Target branch '$targetRef' does not exist on remote '${config.remoteName}'. " +
                    "It may have been merged and deleted. Rolled back the refs this push " +
                    "created; push to an existing target (for example the default branch)."
            )
        }
        renderer.info { "Updated ${prsToMutate.size} pull ${requestOrRequests(prsToMutate.size)}" }

        // Update pull request descriptions second pass. This is necessary because we don't have the
        // GH-assigned PR numbers for new PRs until after we create them.
        logger.trace("updateDescriptions second pass {} {}", stack, prsToMutate)
        val prs = ghClient.getPullRequests(stack).filterByMatchingTargetRef()
        val prsNeedingBodyUpdate =
            prs.updateDescriptionsWithStackInfo(stack, effectiveStackName.stackName)
        withContext(Dispatchers.IO) {
            for (pr in prsNeedingBodyUpdate) {
                launch { ghClient.updatePullRequest(pr) }
            }
        }
        renderer.info {
            "Updated descriptions for ${prsToMutate.size} pull ${requestOrRequests(prsToMutate.size)}"
        }

        print(getStatusString(refSpec, remoteBranchesAfterPush, theme))
    }

    /**
     * Undo a push by restoring each pushed ref to the value it had on the remote beforehand: refs
     * that existed are reset to their prior SHA, and refs this push created are deleted.
     * [priorRemoteShaByName] is the pre-push snapshot keyed by remote ref name.
     */
    private fun rollBackPush(
        pushedRefSpecs: List<RefSpec>,
        priorRemoteShaByName: Map<String, String>,
    ) {
        val rollbackRefSpecs =
            pushedRefSpecs.map(RefSpec::remoteRef).distinct().map { name ->
                priorRemoteShaByName[name]?.let { sha -> RefSpec(sha, name).forcePush() }
                    ?: RefSpec(FORCE_PUSH_PREFIX, name)
            }
        gitClient.push(rollbackRefSpecs, config.remoteName)
    }

    suspend fun merge(refSpec: RefSpec, count: Int? = null, ref: String? = null) {
        logger.trace("merge {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val numCommitsBehind =
            gitClient.logRange(refSpec.localRef, "$remoteName/${refSpec.remoteRef}").size
        if (numCommitsBehind > 0) {
            showMergeOutOfDateWarning(
                numCommitsBehind,
                if (numCommitsBehind > 1) "commits" else "commit",
                refSpec,
            )
            return
        }

        val fullStack =
            resolveScope(
                gitClient.getCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef),
                count,
                ref,
            )
        if (fullStack.isEmpty()) {
            showStackIsEmptyWarning()
            return
        }

        // Filter stack based on the dont-push pattern
        val (stack, excludedCommits) = filterStackByDontPushPattern(fullStack)
        showExcludedCommitsMessage(excludedCommits)

        if (stack.isEmpty()) {
            renderer.warn {
                "All commits in the stack match the dont-push pattern. Nothing to merge."
            }
            return
        }

        val statuses = getRemoteCommitStatuses(stack)

        if (!statuses.all(RemoteCommitStatus::isMergeable)) {
            throw GitJasprException(
                "Not all commits in the stack are mergeable. " +
                    "Use --count or --local to limit the merge scope, " +
                    "or use auto-merge to wait for all commits to become mergeable."
            )
        }

        val prs = ghClient.getPullRequests().filterByMatchingTargetRef()
        val branchesToDelete = getBranchesToDeleteDuringMerge(stack, refSpec.remoteRef)

        // Capture the named stack that owns these commits now; if the merge empties it we delete it
        // afterwards (scoped to just this stack, never a broad sweep). Ambiguous (multi-stack) or
        // unnamed pushes resolve to null and are left alone.
        val ownedNamedStack = (getExistingStackName(stack) as? Found)?.name

        val lastStatus = statuses.last()
        val lastPr = checkNotNull(lastStatus.pullRequest)
        if (lastPr.baseRefName != refSpec.remoteRef) {
            logger.trace("Rebase {} onto {} in prep for merge", lastPr, refSpec.remoteRef)
            ghClient.updatePullRequest(lastPr.copy(baseRefName = refSpec.remoteRef))
        }

        val mergeRefSpecs = listOf(RefSpec(lastStatus.localCommit.hash, refSpec.remoteRef))
        gitClient.push(mergeRefSpecs, remoteName)
        renderer.info {
            "Merged ${stack.size} ${refOrRefs(stack.size)} to ${entity(refSpec.remoteRef)}"
        }

        val mergedRefs = stack.map { commit -> commit.toRemoteRefName() }.toSet()
        val prsToRebase =
            prs.filter { it.baseRefName in mergedRefs && it.headRefName !in mergedRefs }
                .map { it.copy(baseRefName = refSpec.remoteRef) }
        logger.trace(
            "Rebasing {} {} to {}: {}",
            prsToRebase.size,
            prOrPrs(prsToRebase.size),
            refSpec.remoteRef,
            prsToRebase.map(PullRequest::title),
        )
        for (pr in prsToRebase) {
            ghClient.updatePullRequest(pr)
        }

        val remainingStack =
            gitClient.getCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (remainingStack.isNotEmpty()) {
            val remainingPrs = ghClient.getPullRequests(remainingStack).filterByMatchingTargetRef()
            if (remainingPrs.isNotEmpty()) {
                val stackName = (getExistingStackName(remainingStack) as? Found)?.name
                val prsWithUpdatedBodies =
                    remainingPrs.updateDescriptionsWithStackInfo(remainingStack, stackName)
                withContext(Dispatchers.IO) {
                    for (pr in prsWithUpdatedBodies) {
                        launch { ghClient.updatePullRequest(pr) }
                    }
                }
                renderer.info {
                    "Updated descriptions for ${remainingPrs.size} remaining " +
                        "pull ${requestOrRequests(remainingPrs.size)}"
                }
            }
        }

        // Call this for the benefit of the stub client in case we're running within tests. In
        // production, this does nothing as GitHub will "auto close" PRs that are merged
        ghClient.autoClosePrs()

        // Do this cleanup separately after we've rebased remaining PRs. Otherwise, if we delete a
        // branch that's the base ref for a current PR, GitHub will implicitly close it.
        // Additionally, after a small interval, GitHub will "notice" that PRs we rolled up to be
        // merged can also be considered merged, since they contain the same commit hashes and those
        // are in their target branch. We can delete the original branches, and GH will still show
        // the PRs as merged. However, if we delete the branches too quickly, GH will show them as
        // closed instead. So we wait a bit before cleaning up.
        delay(2_000.milliseconds)
        cleanUpBranches(branchesToDelete)

        if (ownedNamedStack != null) {
            deleteNamedStackIfEmptied(ownedNamedStack)
        }
    }

    /**
     * Deletes [namedStackRef] (a full `jaspr-named/<target>/<name>` ref) if, after a merge, all of
     * its commits are now in the target branch. A partially-merged stack still has commits above
     * the merge point and is left intact. Best-effort: a merge has already succeeded, so cleanup
     * failures are logged rather than propagated.
     */
    private fun deleteNamedStackIfEmptied(namedStackRef: String) {
        val remoteName = config.remoteName
        val parts =
            RemoteNamedStackRef.parse(namedStackRef, config.remoteNamedStackBranchPrefix) ?: return
        // Refresh remote-tracking refs so the target reflects the just-pushed merge before we test
        // whether the named stack is now fully contained in it.
        gitClient.fetch(remoteName)
        val stillHasUnmergedCommits =
            gitClient
                .getCommitStack(remoteName, "$remoteName/$namedStackRef", parts.targetRef)
                .isNotEmpty()
        if (stillHasUnmergedCommits) return
        try {
            gitClient.push(listOf(RefSpec(FORCE_PUSH_PREFIX, namedStackRef)), remoteName)
            val currentBranch = gitClient.getCurrentBranchName()
            val localBranchesToDelete =
                gitClient.getBranchNames().filter { branch ->
                    branch != currentBranch &&
                        gitClient.getUpstreamBranchName(branch, remoteName) == namedStackRef
                }
            if (localBranchesToDelete.isNotEmpty()) {
                gitClient.deleteBranches(localBranchesToDelete, force = true)
            }
        } catch (e: Exception) {
            logger.debug("Failed to delete emptied named stack {} after merge", namedStackRef, e)
            return
        }
        renderer.info {
            "Removed the fully-merged named stack ${entity(parts.stackName)}. " +
                "To reuse this name on your next push: " +
                command("jaspr push --name ${parts.stackName}")
        }
    }

    /**
     * Like [require], but throws [GitJasprException] instead of [IllegalArgumentException] so the
     * CLI renders [lazyMessage] as a user-facing error rather than treating it as an application
     * bug.
     */
    private inline fun requireForUser(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) {
            throw GitJasprException(lazyMessage())
        }
    }

    /**
     * Resolves the scope of a merge/auto-merge to a prefix slice of [stack] (bottom-first). At most
     * one of [count] or [ref] may be given. [ref] is any git revision (hash, branch, tag, `HEAD~2`,
     * etc.); it is resolved to a commit and scopes the slice from the base up to and including it.
     */
    private fun resolveScope(stack: List<Commit>, count: Int?, ref: String?): List<Commit> {
        requireForUser(count == null || ref == null) {
            "The --count option and the commit argument are mutually exclusive."
        }
        if (ref == null) return resolveCount(stack, count)
        val resolvedHash =
            try {
                gitClient.log(ref, 1).single().hash
            } catch (_: Exception) {
                throw GitJasprException("Could not resolve '$ref' to a commit.")
            }
        val index = stack.indexOfFirst { commit -> commit.hash == resolvedHash }
        if (index < 0) {
            throw GitJasprException(
                "Commit '$ref' (${resolvedHash.take(7)}) is not in the current stack."
            )
        }
        return stack.subList(0, index + 1)
    }

    /**
     * Resolves a count parameter to a sublist of the stack. Positive values take that many commits
     * from the bottom of the stack. Negative values exclude that many commits from the top.
     */
    private fun resolveCount(stack: List<Commit>, count: Int?): List<Commit> {
        if (count == null) return stack
        requireForUser(count != 0) { "Count must not be zero." }
        val effective =
            if (count > 0) {
                requireForUser(count <= stack.size) {
                    "Count $count exceeds stack size of ${stack.size}."
                }
                count
            } else {
                val result = stack.size + count
                requireForUser(result >= 1) {
                    "Count $count results in $result commits, which is less than 1."
                }
                result
            }
        return stack.subList(0, effective)
    }

    private fun showExcludedCommitsMessage(excludedCommits: List<Commit>) {
        if (excludedCommits.isNotEmpty()) {
            val firstExcluded = excludedCommits.first()
            val lastExcluded = excludedCommits.last()
            val range =
                if (excludedCommits.size == 1) {
                    firstExcluded.hash
                } else {
                    "${firstExcluded.hash}..${lastExcluded.hash}"
                }
            renderer.info { "Excluding commits matching dont-push pattern: ${hash(range)}" }
        }
    }

    suspend fun autoMerge(
        refSpec: RefSpec,
        pollingIntervalSeconds: Int = 10,
        maxAttempts: Int = Int.MAX_VALUE,
        count: Int? = null,
        ref: String? = null,
        theme: Theme = MonoTheme,
    ) {
        logger.trace("autoMerge {} {}", refSpec, pollingIntervalSeconds)

        // Filter the stack to exclude commits matching the dont-push pattern or draft commits
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val fullStack =
            resolveScope(
                gitClient.getCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef),
                count,
                ref,
            )
        val (filteredStack, excludedCommits) = filterStackByDontPushOrDraft(fullStack)
        showExcludedCommitsMessage(excludedCommits)

        if (filteredStack.isEmpty()) {
            renderer.warn {
                "All commits in the stack are either drafts or match the dont-push pattern. Nothing to auto-merge."
            }
            return
        }

        // Use the topmost non-excluded commit as the localRef for auto-merge
        val adjustedLocalRef = filteredStack.last().hash

        // We'll execute the auto-merge in a cached clone after grabbing the current HEAD ref.
        // This way the user can run this in the background or in another terminal and continue to
        // use their working copy without interfering with the auto-merge process.
        val currentRef = gitClient.log(refSpec.localRef, 1).first().hash
        val autoMergeRefSpec = refSpec.copy(localRef = adjustedLocalRef)
        logger.trace("autoMerge refSpec: {}", autoMergeRefSpec)

        val worktreeDir = getAutoMergeWorktreeDir()
        val lockFile = acquireAutoMergeLock()
        val worktreeGit = DefaultGitClient(worktreeDir, config.remoteBranchPrefix)

        try {
            createAutoMergeWorktree(worktreeDir, currentRef)

            // Run the auto-merge loop
            val worktreeJaspr =
                GitJaspr(
                    ghClient,
                    worktreeGit,
                    config.copy(workingDirectory = worktreeDir),
                    newUuid,
                    commitIdentOverride,
                    renderer,
                )

            var attemptsMade = 0
            while (attemptsMade < maxAttempts) {
                val numCommitsBehind =
                    worktreeGit
                        .logRange(
                            autoMergeRefSpec.localRef,
                            "$remoteName/${autoMergeRefSpec.remoteRef}",
                        )
                        .size
                if (numCommitsBehind > 0) {
                    val commits = if (numCommitsBehind > 1) "commits" else "commit"
                    showMergeOutOfDateWarning(numCommitsBehind, commits, autoMergeRefSpec)
                    break
                }

                val stack =
                    worktreeGit.getCommitStack(
                        remoteName,
                        autoMergeRefSpec.localRef,
                        autoMergeRefSpec.remoteRef,
                    )
                if (stack.isEmpty()) {
                    showStackIsEmptyWarning()
                    break
                }

                val statuses = worktreeJaspr.getRemoteCommitStatuses(stack)
                if (statuses.all(RemoteCommitStatus::isMergeable)) {
                    worktreeJaspr.merge(autoMergeRefSpec)

                    // The merge happened in the worktree; the user's main checkout still has
                    // pre-merge tracking refs, so refresh them.
                    gitClient.fetch(remoteName)
                    break
                }
                print(worktreeJaspr.getStatusString(autoMergeRefSpec, theme))

                if (statuses.any { status -> status.checksPass == false }) {
                    renderer.warn { "Checks are failing. Aborting auto-merge." }
                    break
                }
                if (statuses.any { status -> status.approved == false }) {
                    renderer.warn { "PRs are not approved. Aborting auto-merge." }
                    break
                }

                attemptsMade++
                renderer.info {
                    "Delaying for $pollingIntervalSeconds seconds... (CTRL-C to cancel)"
                }
                delay(pollingIntervalSeconds.seconds)
                // Fetch the latest changes before we try again
                worktreeGit.fetch(remoteName)
            }
        } catch (e: Exception) {
            logger.error(
                "Auto-merge failed with exception. Worktree: {}",
                worktreeDir.absolutePath,
                e,
            )
            throw e
        } finally {
            removeAutoMergeWorktree(worktreeDir)
            // Closing the file releases the lock acquired above
            withContext(Dispatchers.IO) { lockFile.close() }
        }
    }

    /**
     * Closes abandoned PRs from the given [plan] and returns an updated plan. Closing PRs may
     * orphan additional branches, so the plan is recalculated after closing.
     */
    suspend fun closeAbandonedPrsAndRecalculate(
        plan: CleanPlan,
        cleanAbandonedPrs: Boolean,
        cleanAllCommits: Boolean,
    ): CleanPlan {
        logger.trace("closeAbandonedPrsAndRecalculate")
        return if (plan.abandonedBranches.isNotEmpty()) {
            val allPrs = ghClient.getPullRequests().filterByMatchingTargetRef()
            val prsToClose = allPrs.filter { pr -> pr.headRefName in plan.abandonedBranches }
            for (pr in prsToClose) {
                ghClient.closePullRequest(pr)
            }
            (plan + getCleanPlan(cleanAbandonedPrs, cleanAllCommits)).also { updatedPlan ->
                logger.trace(
                    "closeAbandonedPrsAndRecalculate updated plan after closing {} abandoned PRs: {}",
                    prsToClose.size,
                    updatedPlan,
                )
            }
        } else {
            plan
        }
    }

    /** Deletes all branches in the given [plan] from the remote via force push. */
    fun executeCleanPlan(plan: CleanPlan) {
        logger.trace("executeCleanPlan")
        val branchesToDelete = plan.allBranches()

        // Identify matching local branches before deleting remotes (we need tracking refs intact)
        val localBranchesToDelete = findMatchingLocalBranches(branchesToDelete.toSet())

        renderer.info {
            "Deleting ${branchesToDelete.size} remote ${branchOrBranches(branchesToDelete.size)}"
        }
        gitClient.push(
            branchesToDelete.map { name -> RefSpec(FORCE_PUSH_PREFIX, name) },
            config.remoteName,
        )

        if (localBranchesToDelete.isNotEmpty()) {
            // Force-delete: the remote was already removed above and the user explicitly opted
            // into cleaning these branches. JGit's default merged-into-HEAD check would refuse
            // for abandoned named-stack branches (which by definition carry unique commits).
            gitClient.deleteBranches(localBranchesToDelete, force = true)
            renderer.info {
                "Removed ${localBranchesToDelete.size} local " +
                    "${branchOrBranches(localBranchesToDelete.size)}: " +
                    localBranchesToDelete.joinToString(", ")
            }
        }
    }

    /**
     * Finds local branches whose upstream matches any of the given remote branch names and whose
     * tip equals the remote tracking ref tip. Skips the current branch.
     */
    private fun findMatchingLocalBranches(remoteBranchNames: Set<String>): List<String> {
        val remoteName = config.remoteName
        val currentBranch = gitClient.getCurrentBranchName()
        return buildList {
            for (localBranch in gitClient.getBranchNames()) {
                if (localBranch == currentBranch) continue
                val upstream = gitClient.getUpstreamBranchName(localBranch, remoteName) ?: continue
                if (upstream !in remoteBranchNames) continue
                val localTip = gitClient.log(localBranch, 1).singleOrNull()?.hash ?: continue
                val trackingRef = "$remoteName/$upstream"
                val remoteTip = gitClient.log(trackingRef, 1).singleOrNull()?.hash
                if (localTip == remoteTip) {
                    add(localBranch)
                }
            }
        }
    }

    /** Returns short commit messages for the given branch names, prefixed with the remote name. */
    fun getShortMessagesForBranches(branches: List<String>): Map<String, String?> {
        return gitClient
            .getShortMessages(branches.map { name -> "${config.remoteName}/$name" })
            .mapKeys { (key, _) -> key.removePrefix("${config.remoteName}/") }
    }

    suspend fun getOrphanedBranches(): List<String> {
        gitClient.fetch(config.remoteName, prune = true)
        val remoteBranches = gitClient.getRemoteBranches(config.remoteName)
        val pullRequestHeadRefs =
            ghClient
                .getPullRequests()
                .filterByMatchingTargetRef()
                .map(PullRequest::headRefName)
                .toSet()
        return getOrphanedBranches(remoteBranches, pullRequestHeadRefs)
    }

    private fun getOrphanedBranches(
        remoteBranches: List<RemoteBranch>,
        pullRequestHeadRefs: Set<String>,
    ): List<String> {
        logger.trace("getOrphanedBranches")
        return remoteBranches.map(RemoteBranch::name).filter { name ->
            val remoteRef = RemoteRef.parse(name, config.remoteBranchPrefix)
            remoteRef != null && remoteRef.copy(revisionNum = null).name() !in pullRequestHeadRefs
        }
    }

    private fun getEmptyNamedStackBranches(remoteBranches: List<RemoteBranch>): List<String> {
        logger.trace("getEmptyNamedStackBranches")
        return remoteBranches.map(RemoteBranch::name).filter { branchName ->
            val parts = RemoteNamedStackRef.parse(branchName, config.remoteNamedStackBranchPrefix)
            if (parts != null) {
                // Named stack branch - check if it has commits not in its target
                val stack =
                    gitClient.getCommitStack(
                        config.remoteName,
                        "${config.remoteName}/$branchName",
                        parts.targetRef,
                    )

                // If the stack is empty, the named branch is fully merged and can be cleaned
                stack.isEmpty()
            } else {
                // Not a named stack branch
                false
            }
        }
    }

    /**
     * Returns named stack branches whose commits no longer have any corresponding jaspr ID branches
     * on the remote. This happens when the user (or GitHub on PR close) deletes the jaspr ID
     * branches but the named stack branch is left behind.
     *
     * Empty named stack branches (already-merged stacks) are not included here; those are reported
     * by [getEmptyNamedStackBranches].
     */
    private fun getAbandonedNamedStackBranches(remoteBranches: List<RemoteBranch>): List<String> {
        logger.trace("getAbandonedNamedStackBranches")
        val remoteJasprCommitIds =
            remoteBranches
                .mapNotNull { branch ->
                    RemoteRef.parse(branch.name, config.remoteBranchPrefix)?.commitId
                }
                .toSet()

        return remoteBranches.mapNotNull { branch ->
            val parts =
                RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
                    ?: return@mapNotNull null
            val stack =
                gitClient.getCommitStack(
                    config.remoteName,
                    "${config.remoteName}/${branch.name}",
                    parts.targetRef,
                )
            if (stack.isEmpty()) return@mapNotNull null
            val stackCommitIds = stack.mapNotNull(Commit::id).toSet()
            if (stackCommitIds.none { it in remoteJasprCommitIds }) branch.name else null
        }
    }

    /**
     * Returns open PRs that will be abandoned by pushing the given [stack] to the named stack
     * identified by [prefixedStackName]. A PR is "abandoned" when its commit ID was reachable from
     * the named stack before the push but is absent from the new stack.
     *
     * @see getAbandonedBranches for the broader detection used by `clean`
     */
    suspend fun findPrsAbandonedByPush(
        remoteBranches: List<RemoteBranch>,
        prefixedStackName: String,
        targetRef: String,
        stack: List<Commit>,
    ): List<PullRequest> {
        val remoteName = config.remoteName
        val namedStackExists = remoteBranches.any { it.name == prefixedStackName }
        if (!namedStackExists) return emptyList()

        val oldCommitIds =
            gitClient
                .logRange("$remoteName/$targetRef", "$remoteName/$prefixedStackName")
                .mapNotNull(Commit::id)
                .toSet()
        val newCommitIds = stack.mapNotNull(Commit::id).toSet()

        // A commit dropped from this stack may still be owned by another named stack;
        // those should not be flagged as abandoned.
        val reachableFromOtherNamedStacks =
            remoteBranches
                .filter { it.name != prefixedStackName }
                .mapNotNull { branch ->
                    RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
                        ?.let { ref -> branch.name to ref.targetRef }
                }
                .flatMap { (branchName, ref) ->
                    gitClient
                        .logRange("$remoteName/$ref", "$remoteName/$branchName")
                        .mapNotNull(Commit::id)
                }
                .toSet()

        val droppedIds = oldCommitIds - newCommitIds - reachableFromOtherNamedStacks
        if (droppedIds.isEmpty()) return emptyList()

        return ghClient.getPullRequestsById(droppedIds.toList()).filterByMatchingTargetRef()
    }

    /** Returns a list of jaspr branches with open PRs that are not reachable by any named stack. */
    private fun getAbandonedBranches(
        remoteBranches: List<RemoteBranch>,
        pullRequestHeadRefs: Set<String>,
    ): List<String> {
        logger.trace("getAbandonedBranches")
        val namedStackBranches = remoteBranches.filter { branch ->
            RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix) != null
        }
        val remoteJasprBranches = remoteBranches.filter { branch ->
            RemoteRef.parse(branch.name, config.remoteBranchPrefix) != null
        }

        // Compare by commit-id rather than git hash. After rebases the same logical commit
        // may have multiple hashes on the remote (one per push), but its commit-id stays
        // stable. A PR head branch and its named-stack branch can therefore point to
        // different hashes of the same commit-id; matching on hash falsely flags it as
        // abandoned.
        val unmergedCommitIdsReachableFromNamedStacks =
            namedStackBranches
                .mapNotNull { branch ->
                    RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
                        ?.let { namedStackRef -> branch.name to namedStackRef.targetRef }
                }
                .flatMap { (branchName, targetRef) ->
                    gitClient
                        .logRange(
                            "${config.remoteName}/${targetRef}",
                            "${config.remoteName}/${branchName}",
                        )
                        .mapNotNull(Commit::id)
                }
                .toSet()

        // Return abandoned branches (those with open PRs not reachable by any of our named stacks)
        val branchesWithPrs = remoteJasprBranches.filter { branch ->
            branch.name in pullRequestHeadRefs
        }
        val refsToCheck = branchesWithPrs.map { "${config.remoteName}/${it.name}" }
        val commits = gitClient.getCommits(refsToCheck)
        return branchesWithPrs
            .filter { branch ->
                val ref = "${config.remoteName}/${branch.name}"
                commits[ref]?.id !in unmergedCommitIdsReachableFromNamedStacks
            }
            .map(RemoteBranch::name)
    }

    data class CleanPlan(
        /** A list of jaspr branches for which no open PR exists (user closed it manually) */
        val orphanedBranches: SortedSet<String> = sortedSetOf(),
        /** A list of named stack branches that are empty (already merged into their target) */
        val emptyNamedStackBranches: SortedSet<String> = sortedSetOf(),
        /** A list of jaspr branches that are not orphaned but are unreachable by any named stack */
        val abandonedBranches: SortedSet<String> = sortedSetOf(),
        /**
         * A list of named stack branches whose underlying jaspr ID branches no longer exist on the
         * remote. The named stack is left over after the underlying work has been cleaned.
         */
        val abandonedNamedStackBranches: SortedSet<String> = sortedSetOf(),
    ) {
        operator fun plus(other: CleanPlan): CleanPlan {
            return CleanPlan(
                (orphanedBranches + (other.orphanedBranches - abandonedBranches)).toSortedSet(),
                (emptyNamedStackBranches + other.emptyNamedStackBranches).toSortedSet(),
                (abandonedBranches + other.abandonedBranches).toSortedSet(),
                (abandonedNamedStackBranches + other.abandonedNamedStackBranches).toSortedSet(),
            )
        }

        fun allBranches() =
            (orphanedBranches +
                    emptyNamedStackBranches +
                    abandonedBranches +
                    abandonedNamedStackBranches)
                .sorted()
    }

    suspend fun getCleanPlan(cleanAbandonedPrs: Boolean, cleanAllCommits: Boolean): CleanPlan {
        logger.trace("getCleanPlan")
        gitClient.fetch(config.remoteName, prune = true)
        val remoteBranches = gitClient.getRemoteBranches(config.remoteName)
        val pullRequestHeadRefs =
            ghClient
                .getPullRequests()
                .filterByMatchingTargetRef()
                .map(PullRequest::headRefName)
                .toSet()

        val allOrphanedBranches = getOrphanedBranches(remoteBranches, pullRequestHeadRefs)
        val emptyNamedStackBranches = getEmptyNamedStackBranches(remoteBranches)
        val abandonedNamedStackBranches = getAbandonedNamedStackBranches(remoteBranches)
        val allAbandonedBranches =
            if (cleanAbandonedPrs) {
                getAbandonedBranches(remoteBranches, pullRequestHeadRefs)
            } else {
                emptyList()
            }

        // Filter orphaned and abandoned branches by ownership unless cleanAllCommits is true
        val remoteBranchesById = remoteBranches.associateBy(RemoteBranch::name)

        val userIdent = getCurrentUserIdent()

        val orphanedBranches =
            if (cleanAllCommits) {
                allOrphanedBranches
            } else {
                allOrphanedBranches.filter { branchName ->
                    userIdent == remoteBranchesById[branchName]?.commit?.author
                }
            }

        val abandonedBranches =
            if (cleanAllCommits) {
                allAbandonedBranches
            } else {
                allAbandonedBranches.filter { branchName ->
                    userIdent == remoteBranchesById[branchName]?.commit?.author
                }
            }

        return CleanPlan(
            orphanedBranches.toSortedSet(),
            emptyNamedStackBranches.toSortedSet(),
            abandonedBranches.toSortedSet(),
            abandonedNamedStackBranches.toSortedSet(),
        )
    }

    fun installCommitIdHook() {
        logger.trace("installCommitIdHook")
        val hooksDir = gitClient.gitCommonDir().resolve("hooks")
        require(hooksDir.isDirectory) { "Hooks directory not found at $hooksDir" }
        val hook = hooksDir.resolve(COMMIT_MSG_HOOK)
        val bundledContent =
            checkNotNull(javaClass.getResourceAsStream("/$COMMIT_MSG_HOOK")).use { it.readBytes() }
        if (hook.exists() && hook.canExecute() && hook.readBytes().contentEquals(bundledContent)) {
            logger.trace("Commit-msg hook is already up-to-date")
            return
        }
        renderer.info {
            "Installing/overwriting ${entity(COMMIT_MSG_HOOK)} to ${entity(hook.toString())} and setting the executable bit"
        }
        hook.writeBytes(bundledContent)
        check(hook.setExecutable(true)) { "Failed to set the executable bit on $hook" }
    }

    /**
     * Idempotently install (or refresh) the jaspr-managed section of the post-checkout hook so the
     * user gets a warning when a checkout invalidates their nav session. Coexists with any
     * pre-existing post-checkout hook by appending a clearly delimited block.
     */
    private fun installNavSessionHook() {
        val hooksDir = gitClient.gitCommonDir().resolve("hooks")
        if (!hooksDir.isDirectory) return
        val hook = hooksDir.resolve(POST_CHECKOUT_HOOK)
        val ourSection =
            checkNotNull(javaClass.getResourceAsStream("/$POST_CHECKOUT_HOOK_RESOURCE")).use {
                it.bufferedReader().readText()
            }

        val newContent =
            if (!hook.exists()) {
                "#!/bin/sh\n\n$ourSection"
            } else {
                val withoutOurs = stripNavHookSection(hook.readText()).trimEnd('\n')
                if (withoutOurs.isBlank()) "#!/bin/sh\n\n$ourSection"
                else "$withoutOurs\n\n$ourSection"
            }

        if (!hook.exists() || hook.readText() != newContent) {
            hook.writeText(newContent)
        }
        if (!hook.canExecute()) {
            hook.setExecutable(true)
        }
    }

    /**
     * Remove the jaspr-managed section from the post-checkout hook. If the hook becomes empty (only
     * contained our section), delete it entirely. Otherwise leave the user's portion untouched.
     */
    private fun removeNavSessionHook() {
        val hooksDir = gitClient.gitCommonDir().resolve("hooks")
        if (!hooksDir.isDirectory) return
        val hook = hooksDir.resolve(POST_CHECKOUT_HOOK)
        if (!hook.exists()) return
        val existing = hook.readText()
        if (NAV_HOOK_BEGIN_MARKER !in existing) return

        val cleaned = stripNavHookSection(existing).trimEnd('\n')
        val onlyShebangAndBlank =
            cleaned.lines().all { line -> line.isBlank() || line.startsWith("#!") }
        if (onlyShebangAndBlank) {
            hook.delete()
        } else {
            hook.writeText("$cleaned\n")
        }
    }

    private fun stripNavHookSection(content: String): String {
        val begin = content.indexOf(NAV_HOOK_BEGIN_MARKER)
        if (begin == -1) return content
        val endMarker = content.indexOf(NAV_HOOK_END_MARKER, startIndex = begin)
        if (endMarker == -1) return content
        val newlineAfterEnd = content.indexOf('\n', startIndex = endMarker)
        val end = if (newlineAfterEnd == -1) content.length else newlineAfterEnd + 1
        return content.removeRange(begin, end)
    }

    private fun RemoteCommitStatus.toStatusList(
        commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>
    ) =
        StatusBits(
                commitIsPushed =
                    when {
                        commitsWithDuplicateIds.containsKey(localCommit.id) -> WARNING
                        remoteCommit == null -> EMPTY
                        remoteCommit.hash == localCommit.hash -> SUCCESS
                        else -> WARNING
                    },
                pullRequestExists = if (pullRequest != null) SUCCESS else EMPTY,
                checksPass =
                    when {
                        pullRequest == null -> EMPTY
                        checksPass == null -> PENDING
                        checksPass -> SUCCESS
                        else -> FAIL
                    },
                readyForReview = if (pullRequest != null && isDraft != true) SUCCESS else EMPTY,
                approved =
                    when {
                        pullRequest == null -> EMPTY
                        approved == null -> EMPTY
                        approved && (unresolvedReviewThreadCount ?: 0) > 0 -> COMMENT
                        approved -> SUCCESS
                        else -> FAIL
                    },
            )
            .toList()

    private fun List<PullRequest>.updateDescriptionsWithStackInfo(
        stack: List<Commit>,
        stackName: String? = null,
    ): List<PullRequest> {
        val prsById = associateBy { checkNotNull(it.commitId) }
        val stackById = stack.associateBy(Commit::id)
        val stackPrsReordered =
            stack.fold(emptyList<PullRequest>()) { prs, commit ->
                prs + checkNotNull(prsById[checkNotNull(commit.id)])
            }
        val remoteBranchNames =
            gitClient.getRemoteBranches(config.remoteName).map(RemoteBranch::name)
        val prsNeedingBodyUpdate = stackPrsReordered.map { existingPr ->
            val commit =
                checkNotNull(stackById[existingPr.commitId]) {
                    "Couldn't find commit for PR with commitId ${existingPr.commitId}"
                }
            val newBody =
                buildPullRequestBody(
                    fullMessage = commit.fullMessage,
                    pullRequests = stackPrsReordered.reversed(),
                    existingPr,
                    remoteBranchNames,
                    stackName,
                )
            existingPr.copy(body = newBody)
        }
        logger.debug("{}", stack)
        return prsNeedingBodyUpdate
    }

    private fun buildPullRequestBody(
        fullMessage: String,
        pullRequests: List<PullRequest> = emptyList(),
        existingPr: PullRequest? = null,
        remoteBranchNames: List<String>,
        stackName: String? = null,
    ): String {
        val jasprStartComment = "<!-- jaspr start -->"
        return buildString {
            if (existingPr != null && existingPr.body.contains(jasprStartComment)) {
                append(existingPr.body.substringBefore(jasprStartComment))
            }
            appendLine(jasprStartComment)
            val fullMessageWithoutFooters = CommitParsers.trimFooters(fullMessage)
            val (subject, body) = getSubjectAndBodyFromFullMessage(fullMessageWithoutFooters)
            // Render subject with an H3 header
            append("### ")
            appendLine(subject)
            if (body != null) {
                appendLine()
                appendLine(body)
            }
            appendLine()
            if (pullRequests.isNotEmpty()) {
                if (stackName != null) {
                    appendLine(
                        "To pull this stack into your working copy (triple click to select):"
                    )
                    appendLine("<kbd>jaspr checkout -n $stackName</kbd>")
                    appendLine()
                }
                appendLine("**Stack**:")
                for (pr in pullRequests) {
                    append("- #${pr.number}")
                    if (pr.commitId == existingPr?.commitId) {
                        append(" ⬅")
                    }
                    appendLine()
                    appendHistoryLinksIfApplicable(pr, remoteBranchNames)
                }
                appendLine()
            }

            append(
                "⚠\uFE0F *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). "
            )
            appendLine(
                "Do not merge manually using the UI - doing so may have unexpected results.*"
            )
        }
    }

    private fun StringBuilder.appendHistoryLinksIfApplicable(
        pr: PullRequest,
        remoteBranches: List<String>,
    ) {
        val (host, owner, name) = config.gitHubInfo
        val regex = "^${pr.headRefName}_(\\d+)".toRegex()
        val historyRefs =
            remoteBranches.filter { regex.matchEntire(it) != null }.sorted().reversed()
        if (historyRefs.isNotEmpty()) {
            append("  - ")
            val historyPairs = listOf(pr.headRefName) + historyRefs
            append(
                historyPairs.windowed(2).joinToString(", ") { (new, old) ->
                    fun String.toRevisionDescription() =
                        checkNotNull(regex.matchEntire(this)).groupValues[1]
                    val oldDescription = old.toRevisionDescription()
                    val newDescription =
                        if (new == pr.headRefName) "Current" else new.toRevisionDescription()
                    "[%s..%s](https://%s/%s/%s/compare/%s..%s)"
                        .format(oldDescription, newDescription, host, owner, name, old, new)
                }
            )
            appendLine()
        }
    }

    suspend fun getRemoteCommitStatuses(stack: List<Commit>): List<RemoteCommitStatus> =
        getRemoteCommitStatuses(stack, gitClient.getRemoteBranches(config.remoteName))

    private suspend fun getRemoteCommitStatuses(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
    ): List<RemoteCommitStatus> =
        getRemoteCommitStatuses(stack, remoteBranches, defaultStatusQueries())

    private suspend fun getRemoteCommitStatuses(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        queries: StatusQueries,
    ): List<RemoteCommitStatus> {
        logger.trace("getRemoteCommitStatuses")
        val remoteBranchesById =
            remoteBranches
                .mapNotNull { branch ->
                    RemoteRef.parse(branch.name, config.remoteBranchPrefix)
                        ?.takeIf { parts -> parts.revisionNum == null }
                        ?.let { it.commitId to branch }
                }
                .toMap()
        val prsById =
            if (stack.isNotEmpty()) {
                queries
                    .getPullRequests(stack.filter { commit -> commit.id != null })
                    .filterByMatchingTargetRef()
                    .associateBy(PullRequest::commitId)
            } else {
                emptyMap()
            }
        return stack.map { commit ->
            val pr = prsById[commit.id]
            RemoteCommitStatus(
                localCommit = commit,
                remoteCommit = remoteBranchesById[commit.id]?.commit,
                pullRequest = pr,
                checksPass = pr?.checksPass,
                isDraft = pr?.isDraft,
                approved = pr?.approved,
                unresolvedReviewThreadCount = pr?.unresolvedReviewThreadCount,
            )
        }
    }

    private fun getBranchesToDeleteDuringMerge(
        stackBeingMerged: List<Commit>,
        targetRef: String,
    ): List<RefSpec> {
        logger.trace("getBranchesToDeleteDuringMerge {} {}", stackBeingMerged, targetRef)
        data class TargetRefToCommitId(val targetRef: String, val commitId: String)

        val deletionCandidates =
            stackBeingMerged
                .asSequence()
                .map { commit -> checkNotNull(commit.id) }
                .map { id -> RemoteRef(id, targetRef, config.remoteBranchPrefix).name() }
                .mapNotNull { remoteRef -> RemoteRef.parse(remoteRef, config.remoteBranchPrefix) }
                .map { ref -> TargetRefToCommitId(ref.targetRef, ref.commitId) }
                .toList()

        logger.trace("Deletion candidates {}", deletionCandidates)

        val branchesToDelete =
            gitClient
                .getRemoteBranches(config.remoteName)
                .map(RemoteBranch::name)
                .filter { branchName ->
                    RemoteRef.parse(branchName, config.remoteBranchPrefix)?.let { ref ->
                        TargetRefToCommitId(ref.targetRef, ref.commitId) in deletionCandidates
                    } == true
                }
                .map { branchName -> RefSpec(FORCE_PUSH_PREFIX, branchName) }
        logger.trace("Deletion list {}", branchesToDelete)
        return branchesToDelete
    }

    private suspend fun cleanUpBranches(branchesToDelete: List<RefSpec>) {
        renderer.info {
            "Cleaning up ${branchesToDelete.size} ${branchOrBranches(branchesToDelete.size)}."
        }
        val maxTries = 3
        val delayBetweenTries = 500L
        var tries = 0
        while (true) {
            try {
                gitClient.push(branchesToDelete, config.remoteName)
                tries++
                if (tries > 1) {
                    renderer.info { "Successfully deleted branches after $tries tries." }
                }
                break
            } catch (e: Exception) {
                tries++
                logger.debug("Failed to delete branches (attempt $tries of $maxTries)", e)
                if (tries < maxTries) {
                    logger.debug("Retrying in {} ms...", delayBetweenTries)
                    delay(delayBetweenTries.milliseconds)
                } else {
                    throw e
                }
            }
        }
    }

    class SinglePullRequestPerCommitConstraintViolation(override val message: String) :
        RuntimeException(message)

    private fun checkSinglePullRequestPerCommit(
        pullRequests: List<PullRequest>
    ): List<PullRequest> {
        logger.trace("checkSinglePullRequestPerCommit")
        val commitsWithMultiplePrs =
            pullRequests
                .groupBy { pr -> checkNotNull(pr.commitId) }
                .filterValues { prs -> prs.size > 1 }
        if (commitsWithMultiplePrs.isNotEmpty()) {
            throw SinglePullRequestPerCommitConstraintViolation(
                "Some commits have multiple open PRs; please correct this and retry your operation: " +
                    commitsWithMultiplePrs.toString()
            )
        }
        return pullRequests
    }

    /**
     * Filters PRs to only include those where the base ref matches the target ref encoded in the
     * head ref. This handles the case where someone manually creates a PR from a jaspr branch to a
     * different target branch outside jaspr.
     *
     * For a PR with head ref `jaspr/main/<commit-id>`, valid base refs are:
     * - main (the target ref itself)
     * - Any jaspr/main/ branch (another jaspr branch for the same target)
     */
    private fun List<PullRequest>.filterByMatchingTargetRef(): List<PullRequest> {
        logger.trace("filterByMatchingTargetRef")
        return filter { pr ->
            val headRef = RemoteRef.parse(pr.headRefName, config.remoteBranchPrefix)
            if (headRef == null) {
                // Not a jaspr branch, include it
                true
            } else {
                val targetRef = headRef.targetRef
                val baseRef = pr.baseRefName
                // Base ref must be either the target ref itself or another jaspr branch for the
                // same target
                val baseRefMatches =
                    baseRef == targetRef ||
                        RemoteRef.parse(baseRef, config.remoteBranchPrefix)?.targetRef == targetRef
                if (!baseRefMatches) {
                    logger.trace(
                        "Ignoring PR {} because base ref {} doesn't match target ref {}",
                        pr.headRefName,
                        baseRef,
                        targetRef,
                    )
                }
                baseRefMatches
            }
        }
    }

    private fun getRevisionHistoryRefs(
        stack: List<Commit>,
        branches: List<RemoteBranch>,
        remoteName: String,
        outOfDateBranches: List<String>,
    ): List<RefSpec> {
        logger.trace("getRevisionHistoryRefs")
        val branchNames = branches.map(RemoteBranch::name).toSet()
        val nextRevisionById =
            branchNames
                .mapNotNull { branchName ->
                    RemoteRef.parse(branchName, config.remoteBranchPrefix)?.let { ref ->
                        ref.commitId to (ref.revisionNum ?: 0) + 1
                    }
                }
                .sortedBy { (_, revisionNumber) -> revisionNumber }
                .toMap()

        return stack
            .mapNotNull { commit ->
                nextRevisionById[commit.id]?.let { revision ->
                    val refName = commit.toRemoteRefName()
                    RefSpec(
                            "$remoteName/$refName",
                            "%s%s%02d".format(refName, REV_NUM_DELIMITER, revision),
                        )
                        .takeIf { refName in outOfDateBranches }
                }
            }
            .also { refSpecs -> logger.trace("getRevisionHistoryRefs: {}", refSpecs) }
    }

    private fun addCommitIdsToLocalStack(commits: List<Commit>): Boolean {
        logger.trace("addCommitIdsToLocalStack {}", commits)
        val indexOfFirstCommitMissingId = commits.indexOfFirst { it.id == null }
        if (indexOfFirstCommitMissingId == -1) {
            logger.trace("No commits are missing IDs")
            return false
        }

        renderer.warn {
            "Some commits in your local stack are missing commit IDs and are being amended to add them."
        }
        val missing = commits.slice(indexOfFirstCommitMissingId until commits.size)
        val refName = "${missing.first().hash}^"
        gitClient.reset(refName)
        for (commit in missing) {
            checkNotNull(gitClient.cherryPick(commit, commitIdentOverride)) {
                "Cherry-pick of ${commit.shortMessage} produced no changes while adding commit IDs"
            }
            if (commit.id == null) {
                val commitId = newUuid()
                gitClient.setCommitId(commitId, commitIdentOverride)
            }
        }
        return true
    }

    /**
     * Update any of the given pull requests whose commits have since been reordered so that their
     * [PullRequest.baseRefName] is equal to [remoteRef], and return a potentially updated list.
     *
     * This is necessary because there is no way to atomically force push the PR branches AND update
     * their baseRefs. We have to do one or the other first, and if at any point a PR's
     * `baseRefName..headRefName` is empty, GitHub will implicitly close that PR and make it
     * impossible for us to update in the future. To avoid this, we temporarily update the
     * [PullRequest.baseRefName] of any moved PR to point to [remoteRef] (which should be the
     * ultimate target of the PR and therefore guaranteed to be non-empty). These PRs will be
     * updated again after we force push the branches.
     */
    private suspend fun List<PullRequest>.updateBaseRefForReorderedPrsIfAny(
        commitStack: List<Commit>,
        remoteRef: String,
    ): List<PullRequest> {
        logger.trace("updateBaseRefForReorderedPrsIfAny")

        val commitMap =
            commitStack.windowedPairs().associateBy { (_, commit) -> checkNotNull(commit.id) }
        val updatedPullRequests = mapNotNull { pr ->
            val commitPair = commitMap[checkNotNull(pr.commitId)]
            if (commitPair == null) {
                null
            } else {
                val (prevCommit, _) = commitPair
                val newBaseRef = prevCommit?.toRemoteRefName() ?: remoteRef
                if (pr.baseRefName == newBaseRef) {
                    pr
                } else {
                    pr.copy(baseRefName = remoteRef)
                }
            }
        }

        for (pr in updatedPullRequests.toSet() - toSet()) {
            ghClient.updatePullRequest(pr)
        }

        return updatedPullRequests
    }

    /**
     * Filters a stack to exclude commits matching the dont-push pattern and all commits above them.
     * Returns the filtered stack and the list of excluded commits. The stack is ordered from bottom
     * (oldest, furthest from HEAD) to top (newest, closest to HEAD).
     */
    private fun filterStackByDontPushPattern(stack: List<Commit>): FilteredStack {
        val pattern = config.dontPushRegex.toRegex(IGNORE_CASE)

        // Find the first commit (from bottom to top) that matches the pattern
        val firstMatchIndex = stack.indexOfFirst { commit -> pattern.matches(commit.shortMessage) }

        return if (firstMatchIndex == -1) {
            // No matches. Include the entire stack
            FilteredStack(included = stack, excluded = emptyList())
        } else {
            // Split the stack at the match point
            val included = stack.subList(0, firstMatchIndex)
            val excluded = stack.subList(firstMatchIndex, stack.size)
            FilteredStack(included, excluded)
        }
    }

    /**
     * Filters a stack to exclude commits matching the dont-push pattern or draft commits and all
     * commits above them. Returns the filtered stack and the list of excluded commits. The stack is
     * ordered from bottom (oldest, furthest from HEAD) to top (newest, closest to HEAD).
     */
    private suspend fun filterStackByDontPushOrDraft(stack: List<Commit>): FilteredStack {
        val dontPushPattern = config.dontPushRegex.toRegex(IGNORE_CASE)
        val statuses = getRemoteCommitStatuses(stack)

        val firstMatchIndex = statuses.indexOfFirst { status ->
            dontPushPattern.matches(status.localCommit.shortMessage) || status.isDraft == true
        }

        return if (firstMatchIndex == -1) {
            // No matches. Include the entire stack
            FilteredStack(included = stack, excluded = emptyList())
        } else {
            // Split the stack at the match point
            val included = stack.subList(0, firstMatchIndex)
            val excluded = stack.subList(firstMatchIndex, stack.size)
            FilteredStack(included, excluded)
        }
    }

    private data class FilteredStack(val included: List<Commit>, val excluded: List<Commit>)

    private fun showStackIsEmptyWarning() = renderer.warn { "Stack is empty." }

    private fun showMergeOutOfDateWarning(
        numCommitsBehind: Int,
        commits: String,
        refSpec: RefSpec,
    ) = renderer.warn {
        "Cannot merge because your stack is out-of-date with the base branch " +
            "($numCommitsBehind $commits behind ${refSpec.remoteRef})."
    }

    private fun Commit.toRefSpec(): RefSpec = RefSpec(hash, toRemoteRefName())

    private fun Commit.toRemoteRefName(): String =
        RemoteRef(commitId = checkNotNull(id), prefix = config.remoteBranchPrefix).name()

    private data class StatusBits(
        val commitIsPushed: Status,
        val pullRequestExists: Status,
        val checksPass: Status,
        val readyForReview: Status,
        val approved: Status,
    ) {
        fun toList(): List<Status> =
            listOf(commitIsPushed, pullRequestExists, checksPass, readyForReview, approved)

        @Suppress("unused")
        enum class Status(val emoji: String) {
            SUCCESS("✅"),
            FAIL("❌"),
            PENDING("⌛"),
            UNKNOWN("❓"),
            EMPTY("ㄧ"),
            WARNING("❗"),
            COMMENT("💬");

            fun styledEmoji(theme: Theme) =
                when (this) {
                    SUCCESS -> theme.success(emoji)
                    FAIL -> theme.error(emoji)
                    PENDING,
                    UNKNOWN -> theme.warning(emoji)
                    EMPTY -> theme.muted(emoji)
                    WARNING,
                    COMMENT -> theme.warning(emoji)
                }
        }
    }

    /** Get the current user's commit author identity that would be used for new commits. */
    fun getCurrentUserIdent(): Ident {
        val name = gitClient.getConfigValue("user.name") ?: System.getenv("USER") ?: "unknown"
        val email = gitClient.getConfigValue("user.email") ?: "unknown@unknown.com"
        return Ident(name, email)
    }

    /**
     * Returns a list of suggested stack name candidates for the given [refSpec], or an empty list
     * if the stack already has an existing name on the remote or is empty. Candidates are derived
     * from progressive truncations of each commit's subject, with the first commit's candidates
     * listed first.
     */
    fun suggestStackNames(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ): StackNameSuggestions = getPushPlan(refSpec).stackNameSuggestions

    private fun refOrRefs(count: Int) = if (count == 1) "ref" else "refs"

    private fun requestOrRequests(count: Int) = if (count == 1) "request" else "requests"

    private fun branchOrBranches(count: Int) = if (count == 1) "branch" else "branches"

    private fun commitOrCommits(count: Int) = if (count == 1) "commit" else "commits"

    private fun prOrPrs(count: Int) = if (count == 1) "pr" else "prs"

    /**
     * Returns the list of named stacks on the remote that target [targetRef], sorted by stack name.
     */
    fun getNamedStacks(targetRef: String) = getAllNamedStacks().filter { it.targetRef == targetRef }

    /** Returns all named stacks on the remote, sorted by stack name. */
    fun getAllNamedStacks(mineOnly: Boolean = false): List<RemoteNamedStackRef> {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName, prune = true)
        val allStacks =
            gitClient
                .getRemoteBranches(remoteName)
                .mapNotNull { branch ->
                    RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
                }
                .sortedBy(RemoteNamedStackRef::stackName)
        if (!mineOnly) return allStacks

        val userIdent = getCurrentUserIdent()
        val refs = allStacks.map { ref -> "$remoteName/${ref.name()}" }
        val commits = gitClient.getCommits(refs)
        return allStacks.filter { stack ->
            commits["$remoteName/${stack.name()}"]?.author == userIdent
        }
    }

    data class NamedStackWithStatus(
        val ref: RemoteNamedStackRef,
        /** True when the stack has no commits relative to its target (already merged). */
        val isEmpty: Boolean,
        /**
         * True when the stack has commits but none of them have a corresponding jaspr ID branch on
         * the remote (e.g. PRs were closed and GitHub deleted the per-commit branches). Mutually
         * exclusive with [isEmpty]; an empty stack is reported with [isAbandoned] = false.
         */
        val isAbandoned: Boolean,
    )

    /**
     * Returns all named stacks on the remote with their health flags computed in one pass. Costs an
     * extra [GitClient.getCommitStack] per stack relative to [getAllNamedStacks]; prefer
     * [getAllNamedStacks] when callers don't need the status.
     */
    fun getAllNamedStacksWithStatus(): List<NamedStackWithStatus> {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName, prune = true)
        val remoteBranches = gitClient.getRemoteBranches(remoteName)
        val remoteJasprCommitIds =
            remoteBranches
                .mapNotNull { branch ->
                    RemoteRef.parse(branch.name, config.remoteBranchPrefix)?.commitId
                }
                .toSet()
        return remoteBranches
            .mapNotNull { branch ->
                val ref =
                    RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
                        ?: return@mapNotNull null
                val stack =
                    gitClient.getCommitStack(
                        remoteName,
                        "$remoteName/${branch.name}",
                        ref.targetRef,
                    )
                val isEmpty = stack.isEmpty()
                val isAbandoned =
                    !isEmpty &&
                        stack.mapNotNull(Commit::id).none { id -> id in remoteJasprCommitIds }
                NamedStackWithStatus(ref, isEmpty = isEmpty, isAbandoned = isAbandoned)
            }
            .sortedBy { it.ref.stackName }
    }

    /**
     * Checks out a named stack by creating or switching to a local branch that tracks the remote
     * named stack ref.
     */
    fun checkoutNamedStack(namedStackRef: RemoteNamedStackRef) {
        val localBranchName = namedStackRef.stackName
        val remoteName = config.remoteName
        val remoteTrackingRef = "$remoteName/${namedStackRef.name()}"
        val branchExists = localBranchName in gitClient.getBranchNames()

        val reflogMessage = "jaspr checkout $localBranchName"
        if (!branchExists) {
            gitClient.branch(
                localBranchName,
                startPoint = remoteTrackingRef,
                reflogMessage = reflogMessage,
            )
            checkoutOrThrowUserFacing(localBranchName, reflogMessage)
            gitClient.setUpstreamBranch(remoteName, namedStackRef.name())
            renderer.info {
                "Checked out named stack '${entity(localBranchName)}' on new local branch"
            }
        } else {
            // Branch exists - checkout and verify upstream matches
            val previousRef = gitClient.log(GitClient.HEAD, 1).single().hash
            checkoutOrThrowUserFacing(localBranchName, reflogMessage)
            val upstream = gitClient.getUpstreamBranch(remoteName)
            if (upstream != null && upstream.name == namedStackRef.name()) {
                renderer.info { "Switched to existing local branch '${entity(localBranchName)}'" }
            } else {
                // Restore the previous branch before throwing
                gitClient.checkout(
                    previousRef,
                    reflogMessage = "jaspr checkout: restore previous after mismatch",
                )
                val upstreamDesc = upstream?.name ?: "none"
                throw GitJasprException(
                    "A local branch '$localBranchName' already exists but its upstream " +
                        "($upstreamDesc) does not match the expected named stack ref " +
                        "(${namedStackRef.name()}). It may be an unrelated branch. " +
                        "Please rename or delete it first."
                )
            }
        }
    }

    private fun checkoutOrThrowUserFacing(refName: String, reflogMessage: String?) {
        try {
            gitClient.checkout(refName, reflogMessage = reflogMessage)
        } catch (e: IllegalArgumentException) {
            if (e.message.orEmpty().contains("would be overwritten")) {
                throw GitJasprException(
                    "Cannot check out '$refName' because you have local changes that would " +
                        "be overwritten. Please commit or stash them first."
                )
            }
            throw e
        }
    }

    /**
     * Renames a named stack on the remote and updates the upstream tracking config of any local
     * branch that was tracking the old remote ref.
     */
    fun renameStack(oldName: String, newName: String, targetRef: String) {
        logger.trace("renameStack {} -> {} (target {})", oldName, newName, targetRef)
        val remoteName = config.remoteName
        val prefix = config.remoteNamedStackBranchPrefix
        gitClient.fetch(remoteName, prune = true)

        val oldRef = RemoteNamedStackRef(oldName, targetRef, prefix).name()
        val newRef = RemoteNamedStackRef(newName, targetRef, prefix).name()

        // Verify the old name exists
        val remoteBranches = gitClient.getRemoteBranches(remoteName).map(RemoteBranch::name)
        if (oldRef !in remoteBranches) {
            throw GitJasprException("Named stack '$oldName' not found (looking for $oldRef).")
        }

        // Verify the new name doesn't already exist
        if (newRef in remoteBranches) {
            throw GitJasprException("Named stack '$newName' already exists ($newRef).")
        }

        // Push old content to the new name AND delete the old branch in a single push
        gitClient.push(
            listOf(RefSpec("$remoteName/$oldRef", newRef), RefSpec(FORCE_PUSH_PREFIX, oldRef)),
            remoteName,
        )
        renderer.info { "Renamed remote stack branch ${entity(oldRef)} -> ${entity(newRef)}" }

        // Update tracking config for any local branch that pointed to the old remote ref
        for (localBranch in gitClient.getBranchNames()) {
            val upstreamName = gitClient.getUpstreamBranchName(localBranch, remoteName)
            if (upstreamName == oldRef) {
                gitClient.setUpstreamBranchForLocalBranch(localBranch, remoteName, newRef)
                renderer.info {
                    "Updated upstream for local branch '${entity(localBranch)}': ${entity(oldRef)} -> ${entity(newRef)}"
                }
            }
        }
    }

    /**
     * Deletes a named stack from the remote and unsets upstream tracking for any local branches
     * that were tracking it. Returns the list of local branches whose upstream was unset.
     */
    fun deleteStack(name: String, targetRef: String): List<String> {
        logger.trace("deleteStack {} (target {})", name, targetRef)
        val remoteName = config.remoteName
        val prefix = config.remoteNamedStackBranchPrefix
        gitClient.fetch(remoteName, prune = true)

        val stackRef = RemoteNamedStackRef(name, targetRef, prefix).name()

        // Verify the stack exists
        val remoteBranches = gitClient.getRemoteBranches(remoteName).map(RemoteBranch::name)
        if (stackRef !in remoteBranches) {
            throw GitJasprException("Named stack '$name' not found (looking for $stackRef).")
        }

        // Identify local branches to delete before the remote push (tracking refs must be intact)
        val currentBranch = gitClient.getCurrentBranchName()
        val localBranchesToDelete = buildList {
            for (localBranch in gitClient.getBranchNames()) {
                if (localBranch == currentBranch) continue
                val upstreamName = gitClient.getUpstreamBranchName(localBranch, remoteName)
                if (upstreamName != stackRef) continue
                val localTip = gitClient.log(localBranch, 1).singleOrNull()?.hash
                val remoteTip = gitClient.log("$remoteName/$stackRef", 1).singleOrNull()?.hash
                if (localTip != null && localTip == remoteTip) {
                    add(localBranch)
                }
            }
        }

        // Force-delete the remote branch
        gitClient.push(listOf(RefSpec(FORCE_PUSH_PREFIX, stackRef)), remoteName)
        renderer.info { "Deleted remote stack branch ${entity(stackRef)}" }
        if (localBranchesToDelete.isNotEmpty()) {
            // Force-delete: the remote was already removed above and the user explicitly opted
            // into deleting this stack. The merged-into-HEAD safety check would otherwise
            // block any local branch whose commits aren't reachable from the current HEAD.
            gitClient.deleteBranches(localBranchesToDelete, force = true)
            for (branch in localBranchesToDelete) {
                renderer.info { "Deleted local branch '${entity(branch)}'" }
            }
        }

        // Unset upstream for any remaining local branches that tracked the deleted ref
        // (e.g. current branch, or branches with divergent tips that weren't deleted)
        val remainingBranches = buildList {
            for (localBranch in gitClient.getBranchNames()) {
                val upstreamName = gitClient.getUpstreamBranchName(localBranch, remoteName)
                if (upstreamName == stackRef) {
                    gitClient.setUpstreamBranchForLocalBranch(localBranch, remoteName, null)
                    add(localBranch)
                    renderer.info { "Unset upstream for local branch '${entity(localBranch)}'" }
                }
            }
        }
        return localBranchesToDelete + remainingBranches
    }

    fun clone(transformConfig: (Config) -> Config) =
        GitJaspr(
            ghClient,
            gitClient,
            transformConfig(config),
            newUuid,
            commitIdentOverride,
            renderer,
        )

    /**
     * Repo-wide jaspr state directory, shared across all worktrees. Lives under the common git dir
     * (`git rev-parse --git-common-dir`), so it resolves to `<repo>/.git/jaspr` from both the main
     * checkout and any linked worktree. Use this for state scoped to the repository as a whole: the
     * divergence-probe cache and the auto-merge/sync scratch worktrees and locks. For per-checkout
     * state that follows HEAD, use [getWorktreeJasprDir].
     */
    private fun getJasprDir(): File = gitClient.gitCommonDir().resolve("jaspr").also { it.mkdirs() }

    /**
     * Per-worktree jaspr state directory. Lives under the worktree-specific git dir (`git rev-parse
     * --git-dir`), which is `<repo>/.git` in the main checkout and `<repo>/.git/worktrees/<name>`
     * in a linked worktree. Use this for state coupled to this checkout's HEAD (navigation and
     * split sessions), so each worktree keeps its own. The post-checkout hook resolves the
     * nav-state path the same way (`git rev-parse --git-dir`), so the two must agree.
     */
    private fun getWorktreeJasprDir(): File =
        gitClient.gitDir().resolve("jaspr").also { it.mkdirs() }

    private fun getAutoMergeWorktreeDir(): File = getJasprDir().resolve("automerge-worktree")

    private fun getAutoMergeLockFile(): File = getJasprDir().resolve("automerge.lock")

    /**
     * Acquires an exclusive file lock guarding auto-merge, ensuring only one auto-merge can be
     * running for this repository at a time. Returns the lock file — the caller must close it to
     * release the lock.
     */
    private suspend fun acquireAutoMergeLock(): RandomAccessFile =
        withContext(Dispatchers.IO) {
            RandomAccessFile(getAutoMergeLockFile(), "rw").apply {
                channel.tryLock()
                    ?: throw GitJasprException(
                        "Another auto-merge is already running for this repository. " +
                            "If this is unexpected, check for stale processes."
                    )
            }
        }

    /**
     * Creates a detached-HEAD worktree at [worktreeDir] pointing at [ref]. Removes any pre-existing
     * worktree at the same path first (e.g. left over from a crashed run).
     */
    private fun createAutoMergeWorktree(worktreeDir: File, ref: String) {
        if (worktreeDir.exists()) {
            // Best-effort cleanup of a stale worktree from a prior run
            removeWorktreeQuietly(worktreeDir)
        }
        val setupTime = measureTime {
            gitClient.addWorktree(worktreeDir, ref = ref, detached = true)
        }
        logger.debug("Created auto-merge worktree in {}", setupTime)
    }

    private fun removeAutoMergeWorktree(worktreeDir: File) {
        removeWorktreeQuietly(worktreeDir)
    }

    /**
     * Force-removes the worktree at [path], swallowing failures (logged at debug). Used in cleanup
     * paths where the worktree may not exist or may be in an unexpected state — we don't want a
     * cleanup failure to mask the real error or fail the surrounding operation.
     */
    private fun removeWorktreeQuietly(path: File) {
        try {
            gitClient.removeWorktree(path, force = true)
        } catch (e: Exception) {
            logger.debug("Failed to remove worktree at {}: {}", path, e.message)
        }
    }

    private val navStateFile
        get() = getWorktreeJasprDir().resolve("nav-state.json")

    fun readNavState(): NavState? {
        val file = navStateFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<NavState>(file.readText())
        } catch (e: Exception) {
            // Nav state is ephemeral session state; we don't carry forward
            // schema changes across jaspr versions. Any deserialization
            // failure (missing required field, type mismatch, malformed
            // JSON) drops the session and forces the operator to restart.
            renderer.warn {
                "Navigation state could not be read (likely from a jaspr " +
                    "version change). Clearing it; you'll need to restart " +
                    "your nav session."
            }
            logger.debug("readNavState deserialization failure", e)
            clearNavState()
            null
        }
    }

    fun writeNavState(state: NavState) {
        navStateFile.writeText(json.encodeToString(state))
    }

    fun clearNavState() {
        navStateFile.delete()
        removeNavSessionHook()
    }

    /** Returns true if HEAD is on a branch (not detached) and nav state exists */
    fun isNavStateStale(): Boolean = !gitClient.isHeadDetached() && readNavState() != null

    /**
     * Returns true if a navigation session is currently active (HEAD detached and nav state
     * exists).
     */
    fun isNavSessionActive(): Boolean = gitClient.isHeadDetached() && readNavState() != null

    private val splitStateFile
        get() = getWorktreeJasprDir().resolve("split-state.json")

    fun readSplitState(): SplitState? {
        val file = splitStateFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<SplitState>(file.readText())
        } catch (e: Exception) {
            logger.warn("Failed to read split state, clearing", e)
            clearSplitState()
            null
        }
    }

    fun writeSplitState(state: SplitState) {
        splitStateFile.writeText(json.encodeToString(state))
    }

    fun clearSplitState() {
        splitStateFile.delete()
    }

    fun isSplitInProgress() = readSplitState() != null

    /**
     * Navigate down N commits in the stack (toward the target branch). Detaches HEAD and writes nav
     * state.
     */
    fun navigateDown(targetRef: String, n: Int): NavState {
        val existingState = readNavState()
        val state =
            if (existingState != null && gitClient.isHeadDetached()) {
                reconcile(existingState, targetRef)
            } else {
                initNavState(targetRef)
            }

        val targetIndex = state.cursorIndex - n
        requireForUser(targetIndex >= 0) {
            "Cannot move down $n commit(s) — only ${state.cursorIndex} commit(s) below current position."
        }

        val target = gitClient.log(state.stack[targetIndex].sha, 1).single()
        gitClient.checkout(
            state.stack[targetIndex].sha,
            reflogMessage = "jaspr nav down $n to ${target.shortMessage}",
        )
        val newState = state.copy(cursorIndex = targetIndex)
        writeNavState(newState)
        installNavSessionHook()
        return newState
    }

    /** Navigate to the bottom of the stack (first commit above the target branch). */
    fun navigateToBottom(targetRef: String): NavState {
        val existingState = readNavState()
        val state =
            if (existingState != null && gitClient.isHeadDetached()) {
                reconcile(existingState, targetRef)
            } else {
                initNavState(targetRef)
            }

        requireForUser(state.cursorIndex > 0) { "Already at the bottom of the stack." }

        val target = gitClient.log(state.stack.first().sha, 1).single()
        gitClient.checkout(
            state.stack.first().sha,
            reflogMessage = "jaspr nav bottom to ${target.shortMessage}",
        )
        val newState = state.copy(cursorIndex = 0)
        writeNavState(newState)
        installNavSessionHook()
        return newState
    }

    /**
     * Navigate up N commits from the saved stack above the current HEAD. If all remaining commits
     * are replayed, restores the source branch and ends the session.
     */
    fun navigateUp(n: Int, targetRef: String? = null): NavMoveResult {
        val state = activeNavSessionOrNull(targetRef) ?: return NavMoveResult.NoSession

        val aboveCount = state.stack.size - state.cursorIndex - 1
        requireForUser(aboveCount > 0) { "Already at the top of the stack." }
        requireForUser(n <= aboveCount) {
            "Cannot move up $n commit(s) — only $aboveCount commit(s) above current position."
        }

        val updatedStack =
            replayEntries(
                state.stack,
                (state.cursorIndex + 1)..(state.cursorIndex + n),
                reflogCommand = "nav up",
                navState = state,
            )

        val newCursor = state.cursorIndex + n
        return if (newCursor == updatedStack.lastIndex) {
            val finalCommit = gitClient.log(GitClient.HEAD, 1).single()
            endNavSession(
                state.copy(stack = updatedStack),
                reflogMessage = "jaspr nav up to ${finalCommit.shortMessage}",
            )
            reachedTop(state, updatedStack, replayedCount = n)
        } else {
            val newState = state.copy(stack = updatedStack, cursorIndex = newCursor)
            writeNavState(newState)
            NavMoveResult.MovedWithin(newState)
        }
    }

    /** Navigate to the top of the stack by replaying all remaining commits. */
    fun navigateToTop(targetRef: String? = null): NavMoveResult {
        val state = activeNavSessionOrNull(targetRef) ?: return NavMoveResult.NoSession

        val aboveCount = state.stack.size - state.cursorIndex - 1
        requireForUser(aboveCount > 0) { "Already at the top of the stack." }

        val updatedStack =
            replayEntries(
                state.stack,
                (state.cursorIndex + 1)..state.stack.lastIndex,
                reflogCommand = "nav top",
                navState = state,
            )

        val finalCommit = gitClient.log(GitClient.HEAD, 1).single()
        endNavSession(
            state.copy(stack = updatedStack),
            reflogMessage = "jaspr nav top to ${finalCommit.shortMessage}",
        )
        return reachedTop(state, updatedStack, replayedCount = aboveCount)
    }

    /**
     * Navigate to an absolute position in the stack. Positive [position] is 1-indexed from the
     * bottom (1 = bottom). Negative [position] counts from the top (-1 = top, -2 = second from
     * top). Auto-starts a nav session if HEAD is on a branch. Replays commits when the target is
     * above the current cursor; checks out directly when below. If the target is the top of the
     * stack, ends the session and restores the source branch (mirroring [navigateToTop]).
     */
    fun navigateTo(targetRef: String, position: Int): NavMoveResult =
        navigateToPosition(reconciledOrInitNavState(targetRef), position)

    /**
     * Navigate to a stack [destination] given as either a position (see [navigateTo]) or a
     * commit-ish (hash, abbreviated hash, etc.) that resolves to a commit in the stack. A valid
     * in-range position is honored as a position; anything else is resolved as a commit, so an
     * all-digit abbreviated hash still lands on the right commit rather than being read as a
     * position. Errors cleanly when [destination] is neither.
     */
    fun navigateToDestination(targetRef: String, destination: String): NavMoveResult {
        val state = reconciledOrInitNavState(targetRef)
        val size = state.stack.size
        val asInt = destination.toIntOrNull()
        val inPositionRange = asInt != null && (asInt in 1..size || asInt in -size..-1)

        // A commit match wins only when the argument isn't already a valid in-range position, so
        // ordinary positions (including "-1") are never handed to git for resolution.
        if (!inPositionRange) {
            val commitIndex = tryResolveStackIndex(state.stack, destination)
            if (commitIndex != null) {
                val sha = state.stack[commitIndex].sha.take(7)
                return navigateToTarget(state, commitIndex, sha, "You are already on commit $sha.")
            }
        }
        return if (asInt != null) {
            // Numeric but not a stack commit: position handling gives the range / zero error.
            navigateToPosition(state, asInt)
        } else {
            throw GitJasprException("'$destination' is not a commit in the current stack.")
        }
    }

    private fun reconciledOrInitNavState(targetRef: String): NavState {
        val existingState = readNavState()
        return if (existingState != null && gitClient.isHeadDetached()) {
            reconcile(existingState, targetRef)
        } else {
            initNavState(targetRef)
        }
    }

    /**
     * Resolve [commitish] to the index of the matching entry in [stack], or null when it doesn't
     * resolve to a commit that is present in the stack.
     */
    private fun tryResolveStackIndex(stack: List<StackEntry>, commitish: String): Int? {
        val resolvedHash =
            runCatching { gitClient.log(commitish, 1).single().hash }.getOrNull() ?: return null
        return stack.indexOfFirst { entry -> entry.sha == resolvedHash }.takeIf { it >= 0 }
    }

    private fun navigateToPosition(state: NavState, position: Int): NavMoveResult {
        requireForUser(position != 0) {
            "Position must not be zero. Use positive N (1 = bottom) or negative N (-1 = top)."
        }
        val targetIndex = resolvePosition(position, state.stack.size)
        return navigateToTarget(
            state,
            targetIndex,
            reflogLabel = "$position",
            alreadyThereMessage = "Already at position $position of the stack.",
        )
    }

    private fun navigateToTarget(
        state: NavState,
        targetIndex: Int,
        reflogLabel: String,
        alreadyThereMessage: String,
    ): NavMoveResult {
        val cursor = state.cursorIndex
        requireForUser(targetIndex != cursor) { alreadyThereMessage }

        if (targetIndex < cursor) {
            val target = gitClient.log(state.stack[targetIndex].sha, 1).single()
            gitClient.checkout(
                state.stack[targetIndex].sha,
                reflogMessage = "jaspr nav to $reflogLabel (${target.shortMessage})",
            )
            val newState = state.copy(cursorIndex = targetIndex)
            writeNavState(newState)
            installNavSessionHook()
            return NavMoveResult.MovedWithin(newState)
        }

        val replayedCount = targetIndex - cursor
        val updatedStack =
            replayEntries(
                state.stack,
                (cursor + 1)..targetIndex,
                reflogCommand = "nav to $reflogLabel",
                navState = state,
            )
        return if (targetIndex == updatedStack.lastIndex) {
            val finalCommit = gitClient.log(GitClient.HEAD, 1).single()
            endNavSession(
                state.copy(stack = updatedStack),
                reflogMessage = "jaspr nav to $reflogLabel (${finalCommit.shortMessage})",
            )
            reachedTop(state, updatedStack, replayedCount = replayedCount)
        } else {
            val newState = state.copy(stack = updatedStack, cursorIndex = targetIndex)
            writeNavState(newState)
            installNavSessionHook()
            NavMoveResult.MovedWithin(newState)
        }
    }

    private fun resolvePosition(position: Int, stackSize: Int): Int {
        val index = if (position > 0) position - 1 else stackSize + position
        requireForUser(index in 0 until stackSize) {
            "Position $position is out of range for a stack of size $stackSize."
        }
        return index
    }

    /**
     * Move HEAD up through the entries of [stack] in [range], returning the (possibly updated)
     * stack. For each entry, if its git parent SHA matches the current HEAD, the entry is checked
     * out as-is (preserving its SHA). Otherwise it is cherry-picked onto HEAD and the rewritten SHA
     * replaces the entry in the returned list.
     *
     * Comparing against the entry's git parent (rather than against the previous entry's SHA in the
     * returned list) matters once a cherry-pick has happened earlier in the replay: the previous
     * entry's SHA has been overwritten with the rewritten one, but the next entry (not yet touched)
     * still carries the original commit whose actual git parent is the unmodified previous SHA.
     *
     * When [navState] is provided, the nav state is written after each successful entry so the
     * cursor reflects actual progress. If a cherry-pick fails mid-replay, the persisted state is
     * correct up to the last successful entry, so `jaspr continue` followed by `jaspr top` picks up
     * where the replay left off.
     */
    private fun replayEntries(
        stack: List<StackEntry>,
        range: IntRange,
        reflogCommand: String,
        navState: NavState? = null,
    ): List<StackEntry> {
        val result = stack.toMutableList()
        var removedCount = 0
        for (i in range) {
            val adjustedIndex = i - removedCount
            val entry = result[adjustedIndex]
            val entryCommit = gitClient.log(entry.sha, 1).single()
            val parentSha = gitClient.getParents(entryCommit).singleOrNull()?.hash
            val headSha = gitClient.log(GitClient.HEAD, 1).single().hash
            if (parentSha == headSha) {
                gitClient.checkout(
                    entry.sha,
                    reflogMessage = "jaspr $reflogCommand: ${entryCommit.shortMessage}",
                )
            } else {
                val newCommit =
                    gitClient.cherryPick(
                        entryCommit,
                        commitIdentOverride,
                        reflogMessage =
                            "jaspr $reflogCommand: cherry-pick of ${entryCommit.shortMessage}",
                    )
                if (newCommit != null) {
                    result[adjustedIndex] = entry.copy(sha = newCommit.hash)
                } else {
                    logger.info(
                        "replayEntries: skipping {} (already applied)",
                        entryCommit.shortMessage,
                    )
                    result.removeAt(adjustedIndex)
                    removedCount++
                }
            }
            if (navState != null) {
                writeNavState(navState.copy(stack = result.toList(), cursorIndex = adjustedIndex))
            }
        }
        return result
    }

    /**
     * Build the initial [NavState] from the current branch. Called on the first nav down/bottom
     * when no session exists yet.
     */
    private fun initNavState(targetRef: String): NavState {
        val branchName = gitClient.getCurrentBranchName()
        requireForUser(branchName.isNotEmpty()) { DETACHED_HEAD_NO_NAV_STATE }

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val commits = gitClient.getCommitStack(remoteName, GitClient.HEAD, targetRef)
        requireForUser(commits.isNotEmpty()) { "Stack is empty." }

        val stack = commits.map { commit ->
            StackEntry(
                sha = commit.hash,
                commitId =
                    commit.id
                        ?: throw GitJasprException(
                            "Commit ${commit.hash} (\"${commit.shortMessage}\") has no jaspr " +
                                "commit ID. Run jaspr push to add IDs before navigating."
                        ),
            )
        }
        // Resolve the named stack once at session start; subsequent moves read this for free.
        // Store the parsed leaf name (e.g., "my-feature"), not the full ref
        // ("jaspr-named/main/my-feature"), so it's safe to print directly.
        val stackName =
            (getExistingStackName(commits) as? Found)?.let { found ->
                RemoteNamedStackRef.parse(found.name, config.remoteNamedStackBranchPrefix)
                    ?.stackName
            }
        return NavState(
            headBeforeDetach = branchName,
            stack = stack,
            cursorIndex = stack.lastIndex,
            targetRef = targetRef,
            stackName = stackName,
        )
    }

    /**
     * Reconcile the persisted [NavState] with the actual git state. Detects new commits (inserted
     * by the user), removed commits (hard reset), and amended commits (same Commit-Id, different
     * SHA).
     *
     * - New commits are inserted into the stack at their actual position.
     * - Missing commits are prepended to the replay queue (above cursor) in their original order.
     * - SHA changes for the same Commit-Id are updated in place.
     *
     * Any commit below HEAD lacking a jaspr commit-id trailer has one installed in place via
     * [addCommitIdsToLocalStack] (same mechanism push uses), since stack entries are keyed by
     * commit-id and a missing id would either drop the commit from the stack or break entry
     * matching across rewrites.
     */
    fun reconcile(state: NavState, targetRef: String): NavState {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        // Walk from HEAD to the merge base to get what's actually materialized
        val initialBelow = gitClient.getCommitStack(remoteName, GitClient.HEAD, targetRef)
        val actualBelow =
            if (addCommitIdsToLocalStack(initialBelow)) {
                // Rewriting installed ids and changed HEAD's lineage; re-read.
                gitClient.getCommitStack(remoteName, GitClient.HEAD, targetRef)
            } else {
                initialBelow
            }

        // The expected "below" portion of the stack
        val expectedBelow = state.stack.subList(0, state.cursorIndex + 1)

        // Build a map of actual commits by Commit-Id
        val actualByCommitId = linkedMapOf<String, StackEntry>()
        for ((hash, _, _, id) in actualBelow) {
            val commitId = id ?: continue
            actualByCommitId[commitId] = StackEntry(sha = hash, commitId = commitId)
        }

        // Detect missing commits (were below cursor but no longer in git)
        val missingFromBelow = expectedBelow.filter { it.commitId !in actualByCommitId }

        // Build the new below-cursor portion: actual commits in their git order,
        // keeping only those with Commit-Ids
        val newBelow = actualBelow.mapNotNull { commit ->
            val commitId = commit.id ?: return@mapNotNull null
            StackEntry(sha = commit.hash, commitId = commitId)
        }

        // Build the new above-cursor portion: missing commits first (in original order),
        // then the original above-cursor entries with any SHA updates
        val aboveCursor = state.stack.subList(state.cursorIndex + 1, state.stack.size)
        val newAbove =
            missingFromBelow +
                aboveCursor.map { entry ->
                    // If a commit from the above portion happens to be in the actual below
                    // (e.g., user cherry-picked it), keep the original SHA for replay
                    actualByCommitId[entry.commitId] ?: entry
                }

        val newStack = newBelow + newAbove
        val newCursor = (newBelow.size - 1).coerceAtLeast(0)

        require(newStack.isNotEmpty()) { "Stack is empty after reconciliation." }

        return state.copy(stack = newStack, cursorIndex = newCursor)
    }

    /**
     * Returns the active nav session state (after reconciliation), or throws with an appropriate
     * message. Handles all combinations of detached/attached HEAD and present/absent nav state,
     * including auto-clearing stale state.
     */
    private fun requireActiveNavSession(
        targetRef: String? = null,
        reconcileStack: Boolean = true,
    ): NavState =
        activeNavSessionOrNull(targetRef, reconcileStack)
            ?: throw GitJasprException(
                "No navigation session in progress (already at the top of the stack)."
            )

    /**
     * Returns the active nav session state (after reconciliation), or null when no session is
     * active. Throws only for the invalid "detached HEAD without nav state" case. Auto-clears stale
     * state.
     *
     * Callers can pass [targetRef] to override the persisted one. When null, reconcile runs against
     * the target ref captured in [NavState.targetRef] at session start, so operations like `up` /
     * `top` / `drop` pick up any commits added during the session without the caller having to
     * thread the target ref through.
     *
     * Destructive callers (cancel) pass [reconcileStack] `= false`: reconcile may install
     * commit-ids by running cherry-pick, which can't run if a cherry-pick is already in progress
     * (precisely the state cancel is trying to clean up).
     */
    private fun activeNavSessionOrNull(
        targetRef: String? = null,
        reconcileStack: Boolean = true,
    ): NavState? {
        val state = readNavState()
        val detached = gitClient.isHeadDetached()
        return when {
            detached && state != null ->
                if (reconcileStack) reconcile(state, targetRef ?: state.targetRef) else state
            detached -> throw GitJasprException(DETACHED_HEAD_NO_NAV_STATE)
            else -> {
                if (state != null) clearNavState()
                null
            }
        }
    }

    /**
     * Finish the navigation session, updating the original branch to the current HEAD without
     * replaying any remaining commits from the replay queue.
     *
     * @return the stack entries above the cursor that were discarded (empty if the cursor was at
     *   the top)
     */
    fun finishNavSession(): List<StackEntry> {
        val state = requireActiveNavSession()
        val discarded = state.stack.subList(state.cursorIndex + 1, state.stack.size).toList()
        val finalCommit = gitClient.log(GitClient.HEAD, 1).single()
        endNavSession(
            state,
            reflogMessage = "jaspr nav finish at ${finalCommit.shortMessage}",
        )
        return discarded
    }

    /**
     * Cancel the navigation session, restoring the original branch to its position before the
     * session started. Any commits created during the session that are not reachable from the
     * restored branch become orphaned.
     *
     * This is a hard escape: it always hard-resets to the original branch and removes untracked
     * files. Any uncommitted changes to tracked files, any staged changes, and any untracked files
     * are discarded. Stash with `git stash --include-untracked` before running cancel if you want
     * to preserve them. An in-progress cherry-pick is aborted first so the reset operates on a sane
     * state; an in-progress split has its state file cleared.
     *
     * @return the SHAs of commits that were below the cursor but are not part of the original
     *   branch (i.e., commits created or cherry-picked during the session)
     */
    fun cancelNavSession(): List<String> {
        val state = requireActiveNavSession(reconcileStack = false)

        // Walk from the original branch tip to build the set of SHAs that will remain
        // reachable after we restore the branch. We walk enough commits to cover the stack.
        val originalShas =
            gitClient.log(state.headBeforeDetach, state.stack.size + 1).map(Commit::hash).toSet()

        // Walk from current HEAD to find commits that are NOT in the original branch.
        // These will be orphaned when we restore it. Stop at the first original commit.
        val headLog = gitClient.log(GitClient.HEAD, state.stack.size + 1)
        val orphanedShas = headLog.takeWhile { it.hash !in originalShas }.map(Commit::hash)

        if (gitClient.isCherryPickInProgress()) {
            gitClient.cherryPickAbort()
        }

        val cancelMessage = "jaspr nav cancel to ${state.headBeforeDetach}"
        // Always hard-escape: discard working tree changes (including untracked) and any
        // commits made during the session. This is destructive by contract; the operator
        // stashes beforehand if they want to preserve work.
        gitClient.reset(state.headBeforeDetach, reflogMessage = cancelMessage)
        gitClient.cleanUntracked()
        if (isSplitInProgress()) {
            clearSplitState()
        }

        gitClient.checkout(state.headBeforeDetach, reflogMessage = cancelMessage)
        clearNavState()
        return orphanedShas
    }

    /**
     * End the navigation session: update the original branch to the new tip, check it out, clear
     * the state.
     */
    private fun endNavSession(state: NavState, reflogMessage: String? = null) {
        val newTip = gitClient.log(GitClient.HEAD, 1).single().hash
        gitClient.branch(
            state.headBeforeDetach,
            startPoint = newTip,
            force = true,
            reflogMessage = reflogMessage,
        )
        gitClient.checkout(state.headBeforeDetach, reflogMessage = reflogMessage)
        clearNavState()
    }

    private fun reachedTop(
        state: NavState,
        updatedStack: List<StackEntry>,
        replayedCount: Int,
    ): NavMoveResult.ReachedTop =
        NavMoveResult.ReachedTop(
            replayedCount = replayedCount,
            restoredName = state.stackName ?: state.headBeforeDetach,
            finalState = state.copy(stack = updatedStack, cursorIndex = updatedStack.lastIndex),
        )

    /**
     * Drop [n] commits from the top of the current stack.
     *
     * When a nav session is active, the dropped commits are removed from the nav stack and the
     * cursor is adjusted. When no session is active, this is equivalent to `git reset --hard
     * HEAD~n`.
     */
    fun drop(n: Int, targetRef: String? = null) {
        requireForUser(n > 0) { "Must drop at least 1 commit." }
        requireCleanWorkingTree()

        val state = readNavState()
        if (state != null && gitClient.isHeadDetached()) {
            val reconciled = reconcile(state, targetRef ?: state.targetRef)
            requireForUser(n <= reconciled.cursorIndex + 1) {
                "Cannot drop $n commit(s) — only ${reconciled.cursorIndex + 1} commit(s) at or below current position."
            }

            // Remove the top n entries from below the cursor
            val newStack =
                reconciled.stack.toMutableList().apply {
                    val removeFrom = reconciled.cursorIndex - n + 1
                    repeat(n) { removeAt(removeFrom) }
                }
            val newCursor = reconciled.cursorIndex - n

            gitClient.reset("HEAD~$n", reflogMessage = "jaspr drop $n")

            if (newStack.isEmpty()) {
                // Dropped everything — end the session
                clearNavState()
            } else {
                writeNavState(reconciled.copy(stack = newStack, cursorIndex = newCursor))
            }
        } else {
            // No nav session — just hard reset
            gitClient.reset("HEAD~$n", reflogMessage = "jaspr drop $n")
        }
    }

    /**
     * Split the current HEAD commit by performing a mixed reset. Records the original commit SHA so
     * [unsplit] can restore it later. If a nav session is active, removes the commit from the nav
     * stack so reconciliation won't treat the reset as a missing commit.
     *
     * @return the short message of the split commit (for display)
     */
    fun split(): String {
        requireForUser(readSplitState() == null) {
            "A split is already in progress. Run jaspr unsplit to finish or undo it."
        }

        val head = gitClient.log(GitClient.HEAD, 1).single()
        writeSplitState(SplitState(unsplitSha = head.hash))

        // If in a nav session, remove the commit at the cursor from the stack
        val navState = readNavState()
        if (navState != null && gitClient.isHeadDetached()) {
            val newStack = navState.stack.toMutableList().apply { removeAt(navState.cursorIndex) }
            val newCursor = navState.cursorIndex - 1
            if (newStack.isEmpty()) {
                clearNavState()
            } else {
                writeNavState(navState.copy(stack = newStack, cursorIndex = newCursor))
            }
        }

        gitClient.resetMixed("HEAD~1", reflogMessage = "jaspr split: ${head.shortMessage}")
        return head.shortMessage
    }

    /**
     * Unsplit: restore the original commit from before the split. Two modes, decided by HEAD
     * position (see ADR-0005):
     * - **Fold mode** (HEAD is at the original's parent): stage the working tree and amend so the
     *   restored commit's tree equals the working tree, with the original's message and author.
     *   Identity-restores to the original SHA when there is nothing to fold.
     * - **Replay mode** (HEAD has moved): cherry-pick the original onto HEAD using `-X theirs`. Any
     *   content conflicts are resolved by taking the original's side. A path-level conflict the
     *   strategy cannot auto-resolve (modify/delete, rename/rename, type-change) leaves the
     *   cherry-pick in progress and returns [UnsplitOutcome.LeftInProgress].
     *
     * Both modes record a backup ref at `refs/jaspr-backup/pre-unsplit-<unix-ts>`. Replay mode
     * additionally stashes any dirty working-tree content (with a recognizable message) so the
     * operator can recover it via `git stash pop`.
     *
     * If a nav session is active and the original was restored, re-inserts it into the nav stack.
     * [UnsplitOutcome.LeftInProgress] leaves the nav stack and split state unchanged.
     */
    fun unsplit(): UnsplitOutcome {
        val splitState = requireNotNullForUser(readSplitState()) { "No split in progress." }
        val originalCommit = gitClient.log(splitState.unsplitSha, 1).single()
        val originalParent = gitClient.log("${splitState.unsplitSha}^", 1).single().hash
        val headSha = gitClient.log(GitClient.HEAD, 1).single().hash

        val outcome =
            if (headSha == originalParent) {
                unsplitFold(splitState.unsplitSha)
            } else {
                unsplitReplay(originalCommit)
            }

        if (outcome is UnsplitOutcome.LeftInProgress) return outcome

        val navState = readNavState()
        val finalOutcome =
            if (navState != null && gitClient.isHeadDetached()) {
                // Reconcile rebuilds the stack from git's actual history below HEAD, so any
                // precursor commits the operator made between split and unsplit are picked up
                // alongside the restored commit. Reconcile may auto-install commit-ids on
                // precursors that lack them, which rewrites HEAD's lineage; refresh
                // outcome.restoredCommit afterwards so the operator-facing sha matches HEAD.
                val reconciled = reconcile(navState, navState.targetRef)
                writeNavState(reconciled)
                val currentHead = gitClient.log(GitClient.HEAD, 1).single()
                when (outcome) {
                    is UnsplitOutcome.Restored -> outcome.copy(restoredCommit = currentHead)
                    is UnsplitOutcome.RestoredWithAutoResolvedConflicts ->
                        outcome.copy(restoredCommit = currentHead)
                }
            } else {
                outcome
            }

        clearSplitState()
        return finalOutcome
    }

    private fun unsplitFold(unsplitSha: String): UnsplitOutcome {
        val headSha = gitClient.log(GitClient.HEAD, 1).single().hash
        val backupRef = "refs/jaspr-backup/pre-unsplit-${System.currentTimeMillis() / 1000}"
        gitClient.updateRef(backupRef, headSha)

        val original = gitClient.log(unsplitSha, 1).single()
        val reflogMessage = "jaspr unsplit (fold): ${original.shortMessage}"

        gitClient.resetSoft(unsplitSha, reflogMessage = reflogMessage)
        gitClient.add(".")
        // Skip the amend when nothing actually changed since the split: HEAD is already
        // pointing at the original commit (the resetSoft did that), and amending would
        // produce a new commit object with a fresh committer date for no functional reason.
        // After `add(".")`, hasUncommittedChangesToTrackedFiles compares the staged tree to
        // HEAD's tree -- exactly the condition we want. This also implements the identity
        // restore (ADR-0005): a clean tree at the split point reduces to HEAD = unsplitSha
        // rather than a new SHA.
        if (gitClient.hasUncommittedChangesToTrackedFiles()) {
            gitClient.commit(amend = true, reflogMessage = reflogMessage)
        }
        return UnsplitOutcome.Restored(gitClient.log(GitClient.HEAD, 1).single())
    }

    private fun unsplitReplay(originalCommit: Commit): UnsplitOutcome {
        val headSha = gitClient.log(GitClient.HEAD, 1).single().hash
        val timestamp = System.currentTimeMillis() / 1000
        val backupRef = "refs/jaspr-backup/pre-unsplit-$timestamp"
        gitClient.updateRef(backupRef, headSha)

        val stashRef = "refs/jaspr-backup/pre-unsplit-stash-$timestamp"
        val stashSha =
            gitClient.stashPush(refName = stashRef, message = "jaspr unsplit pre-state $timestamp")
        val recoveredStashRef = if (stashSha != null) stashRef else null

        val reflogMessage = "jaspr unsplit (replay): ${originalCommit.shortMessage}"
        return when (
            val attempt =
                gitClient.tryCherryPick(
                    originalCommit,
                    useTheirs = true,
                    reflogMessage = reflogMessage,
                )
        ) {
            is CherryPickResult.Success -> {
                // Detect post-hoc which paths needed -X theirs to resolve. Re-run the merge
                // *without* the strategy against the pre-cherry-pick state. Anything that comes
                // back as a conflict is what the strategy auto-resolved.
                val conflictingPaths =
                    when (
                        val probe =
                            gitClient.mergeTreeWriteTree(
                                base = "${originalCommit.hash}^",
                                ours = backupRef,
                                theirs = originalCommit.hash,
                            )
                    ) {
                        is MergeTreeResult.Clean -> emptyList()
                        is MergeTreeResult.Conflict -> probe.conflictingPaths
                    }
                if (conflictingPaths.isEmpty()) {
                    UnsplitOutcome.Restored(attempt.commit)
                } else {
                    UnsplitOutcome.RestoredWithAutoResolvedConflicts(
                        restoredCommit = attempt.commit,
                        conflictingPaths = conflictingPaths,
                        backupRef = backupRef,
                        stashRef = recoveredStashRef,
                    )
                }
            }
            CherryPickResult.AlreadyApplied -> {
                val headCommit = gitClient.log(GitClient.HEAD, 1).single()
                UnsplitOutcome.Restored(headCommit)
            }
            CherryPickResult.LeftInProgress ->
                UnsplitOutcome.LeftInProgress(
                    originalCommit = originalCommit,
                    backupRef = backupRef,
                    stashRef = recoveredStashRef,
                )
        }
    }

    /**
     * Fold the current commit into an adjacent commit. The current commit is eliminated; the
     * neighbor absorbs its changes and keeps its own identity (message, commit-id, author).
     *
     * @param direction "down" to fold into the parent (default), "up" to fold into the child
     * @return the short message of the surviving commit
     */
    fun fold(direction: String = "down"): String {
        requireForUser(readSplitState() == null) { "Cannot fold while a split is in progress." }

        return when (direction) {
            "down" -> foldDown()
            "up" -> foldUp()
            else -> throw GitJasprException("Direction must be 'up' or 'down', got '$direction'")
        }
    }

    /**
     * Fold current commit down into its parent. Soft reset removes the current commit, amend merges
     * its changes into the parent. Parent's identity is preserved.
     */
    private fun foldDown(): String {
        val navState = readNavState()
        if (navState != null && gitClient.isHeadDetached()) {
            requireForUser(navState.cursorIndex > 0) {
                "Cannot fold down — already at the bottom of the stack."
            }
            val parent = gitClient.log("HEAD~1", 1).single()
            val reflogMessage = "jaspr fold down into ${parent.shortMessage}"
            // Soft reset removes current commit, amend merges into parent
            gitClient.resetSoft("HEAD~1", reflogMessage = reflogMessage)
            gitClient.commit(amend = true, reflogMessage = reflogMessage)

            val survivor = gitClient.log(GitClient.HEAD, 1).single()

            // Remove the folded commit from the stack, update the parent's SHA
            val newStack =
                navState.stack.toMutableList().apply {
                    removeAt(navState.cursorIndex)
                    set(
                        navState.cursorIndex - 1,
                        StackEntry(
                            sha = survivor.hash,
                            commitId =
                                checkNotNull(survivor.id) {
                                    "Surviving commit has no jaspr commit ID."
                                },
                        ),
                    )
                }
            writeNavState(navState.copy(stack = newStack, cursorIndex = navState.cursorIndex - 1))
            return survivor.shortMessage
        } else {
            // Top of stack, no nav session — just soft reset and amend
            val stack = gitClient.log(GitClient.HEAD, 2)
            requireForUser(stack.size >= 2) {
                "Cannot fold down — nothing below the current commit."
            }
            val parent = stack[1]
            val reflogMessage = "jaspr fold down into ${parent.shortMessage}"
            gitClient.resetSoft("HEAD~1", reflogMessage = reflogMessage)
            gitClient.commit(amend = true, reflogMessage = reflogMessage)
            return gitClient.log(GitClient.HEAD, 1).single().shortMessage
        }
    }

    /**
     * Fold current commit up into the next commit (above the cursor in the replay queue).
     * Cherry-picks the above commit, then soft-resets 2 commits and recommits with the above
     * commit's identity.
     */
    private fun foldUp(): String {
        val navState =
            requireNotNullForUser(readNavState()?.takeIf { gitClient.isHeadDetached() }) {
                "Cannot fold up without an active navigation session — there is no commit above."
            }
        val aboveIndex = navState.cursorIndex + 1
        requireForUser(aboveIndex <= navState.stack.lastIndex) {
            "Cannot fold up — already at the top of the stack."
        }

        val aboveEntry = navState.stack[aboveIndex]
        val aboveCommit = gitClient.log(aboveEntry.sha, 1).single()
        val reflogMessage = "jaspr fold up into ${aboveCommit.shortMessage}"

        checkNotNull(
            gitClient.cherryPick(aboveCommit, commitIdentOverride, reflogMessage = reflogMessage)
        ) {
            "Cherry-pick of ${aboveCommit.shortMessage} produced no changes during fold up"
        }

        // Now HEAD has: ...parent -> current -> above'
        // Soft reset 2 to collapse both into staged changes on top of parent
        gitClient.resetSoft("HEAD~2", reflogMessage = reflogMessage)

        // Recommit with the above commit's message, footers, and author
        val aboveFooters = CommitParsers.getFooters(aboveCommit.fullMessage)
        val aboveMessage = CommitParsers.trimFooters(aboveCommit.fullMessage)
        gitClient.commit(
            message = aboveMessage,
            footerLines = aboveFooters,
            author = aboveCommit.author,
            reflogMessage = reflogMessage,
        )

        val survivor = gitClient.log(GitClient.HEAD, 1).single()

        // Remove both the current commit and the above commit from the stack,
        // insert the survivor at the cursor position
        val newStack =
            navState.stack.toMutableList().apply {
                removeAt(aboveIndex) // remove above first (higher index)
                removeAt(navState.cursorIndex) // then current
                add(
                    navState.cursorIndex,
                    StackEntry(
                        sha = survivor.hash,
                        commitId =
                            checkNotNull(survivor.id) {
                                "Surviving commit has no jaspr commit ID."
                            },
                    ),
                )
            }
        writeNavState(navState.copy(stack = newStack))
        return survivor.shortMessage
    }

    /** Result of syncing a single branch. */
    data class SyncBranchResult(val branch: String, val success: Boolean, val message: String)

    /**
     * Rebases all local jaspr-managed branches onto the latest target ref. Uses a temporary
     * worktree for branches other than the current one, with topological ordering and commit
     * mapping to avoid duplicating shared commits. The current branch is rebased last in the main
     * working copy.
     *
     * @return list of results for each branch attempted
     */
    fun sync(targetRef: String): List<SyncBranchResult> {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val currentBranch = gitClient.getCurrentBranchName()
        val targetBase = "$remoteName/$targetRef"

        // Find all local branches with jaspr commit-IDs above the target
        data class BranchStack(val branch: String, val commits: List<Commit>)

        val branchStacks = buildList {
            for (branch in gitClient.getBranchNames()) {
                val commits = gitClient.getCommitStack(remoteName, branch, targetRef)
                val hasJasprCommits = commits.any { commit -> commit.id != null }
                if (hasJasprCommits) {
                    add(BranchStack(branch, commits))
                }
            }
        }

        if (branchStacks.isEmpty()) {
            renderer.info { "No jaspr-managed branches to sync." }
            return emptyList()
        }

        // Check which branches actually need rebasing
        val targetBaseHash = gitClient.log(targetBase, 1).singleOrNull()?.hash
        val branchesNeedingRebase = branchStacks.filter { (_, commits) ->
            // A branch needs rebase if the target base is not already the parent of its first
            // commit
            commits.isNotEmpty() &&
                gitClient.getParents(commits.first()).none { parent ->
                    parent.hash == targetBaseHash
                }
        }

        if (branchesNeedingRebase.isEmpty()) {
            renderer.info { "All branches are already up to date." }
            return branchStacks.map { SyncBranchResult(it.branch, true, "Already up to date") }
        }

        // Sort by stack depth (shallowest first) for topological ordering
        val sorted = branchesNeedingRebase.sortedBy { it.commits.size }

        // Separate the current branch from others
        val otherBranches = sorted.filter { it.branch != currentBranch }
        val currentBranchStack = sorted.find { it.branch == currentBranch }

        val results = mutableListOf<SyncBranchResult>()
        val commitMap = mutableMapOf<String, String>() // oldHash -> newHash
        val skippedCommits = mutableSetOf<String>() // commits whose branches conflicted

        // Rebase non-current branches in a worktree
        if (otherBranches.isNotEmpty()) {
            val worktreeDir = getJasprDir().resolve("sync-worktree")
            try {
                // Create detached worktree
                worktreeDir.deleteRecursively()
                gitClient.addWorktree(worktreeDir, detached = true)
                val worktreeClient = DefaultGitClient(worktreeDir, config.remoteBranchPrefix)

                for ((branch, commits) in otherBranches) {
                    // Check if any of this branch's commits are in a skipped set
                    val hasSkippedAncestor = commits.any { it.hash in skippedCommits }
                    if (hasSkippedAncestor) {
                        renderer.warn {
                            "Skipping ${entity(branch)} — depends on a conflicted branch"
                        }
                        results.add(
                            SyncBranchResult(
                                branch,
                                false,
                                "Skipped (depends on conflicted branch)",
                            )
                        )
                        commits.forEach { skippedCommits.add(it.hash) }
                        continue
                    }

                    val result =
                        rebaseBranchInWorktree(
                            worktreeClient,
                            branch,
                            commits,
                            targetBase,
                            commitMap,
                        )
                    results.add(result)
                    if (!result.success) {
                        commits.forEach { skippedCommits.add(it.hash) }
                    }
                }
            } finally {
                removeWorktreeQuietly(worktreeDir)
            }
        }

        // Rebase the current branch last
        if (currentBranchStack != null) {
            val hasSkippedAncestor = currentBranchStack.commits.any { it.hash in skippedCommits }
            if (hasSkippedAncestor) {
                renderer.warn {
                    "Skipping ${entity(currentBranch)} — depends on a conflicted branch"
                }
                results.add(
                    SyncBranchResult(currentBranch, false, "Skipped (depends on conflicted branch)")
                )
            } else {
                // If some commits are already mapped from worktree rebases of shallower branches,
                // use cherry-pick to maintain shared commit identity. Otherwise use normal rebase.
                val hasMappedCommits = currentBranchStack.commits.any { it.hash in commitMap }
                val result =
                    if (hasMappedCommits) {
                        rebaseCurrentBranchViaCherryPick(
                            currentBranch,
                            currentBranchStack.commits,
                            targetBase,
                            commitMap,
                        )
                    } else {
                        val rebaseResult = rebaseCurrentBranch(targetBase)
                        SyncBranchResult(
                            currentBranch,
                            rebaseResult == 0,
                            if (rebaseResult == 0) "Rebased"
                            else "Conflict (exit code $rebaseResult)",
                        )
                    }
                results.add(result)
            }
        }

        // Add results for branches that didn't need rebasing
        val attemptedBranches = results.map(SyncBranchResult::branch).toSet()
        for ((branch, _) in branchStacks) {
            if (branch !in attemptedBranches) {
                results.add(SyncBranchResult(branch, true, "Already up to date"))
            }
        }

        return results
    }

    /**
     * Rebases a single branch in the worktree using cherry-pick, respecting the commit map to avoid
     * duplicating commits that were already rebased as part of a shallower branch.
     */
    private fun rebaseBranchInWorktree(
        worktreeClient: GitClient,
        branch: String,
        commits: List<Commit>,
        targetBase: String,
        commitMap: MutableMap<String, String>,
    ): SyncBranchResult {
        logger.trace("rebaseBranchInWorktree {} ({} commits)", branch, commits.size)

        // Find the deepest commit that has already been mapped (rebased by an earlier branch)
        var newBase = targetBase
        var commitsToReplay = commits
        for (i in commits.indices.reversed()) {
            val mapped = commitMap[commits[i].hash]
            if (mapped != null) {
                newBase = mapped
                commitsToReplay = commits.subList(i + 1, commits.size)
                break
            }
        }

        val reflogMessage = "jaspr sync $branch"
        if (commitsToReplay.isEmpty()) {
            // All commits were already rebased by a shallower branch, just update the ref
            val tip = commitMap[commits.last().hash]
            if (tip != null) {
                gitClient.branch(
                    branch,
                    startPoint = tip,
                    force = true,
                    reflogMessage = reflogMessage,
                )
                renderer.info {
                    "Updated ${entity(branch)} (all commits shared with earlier branch)"
                }
            }
            return SyncBranchResult(branch, true, "Rebased (shared commits)")
        }

        // Checkout the new base in the worktree (checkout of a SHA detaches HEAD)
        try {
            worktreeClient.checkout(newBase, reflogMessage = reflogMessage)
        } catch (e: Exception) {
            logger.debug("Failed to checkout base $newBase in worktree", e)
            return SyncBranchResult(branch, false, "Failed to checkout base")
        }

        // Cherry-pick each commit
        for (commit in commitsToReplay) {
            try {
                worktreeClient.cherryPick(
                    commit,
                    reflogMessage = "$reflogMessage: cherry-pick of ${commit.shortMessage}",
                )
            } catch (e: CherryPickConflictException) {
                logger.debug("cherryPick failed during rebase of $branch", e)
                worktreeClient.cherryPickAbort()
                renderer.warn {
                    "Conflict rebasing ${entity(branch)} at commit ${entity(commit.hash.take(7))} (${commit.shortMessage})"
                }
                return SyncBranchResult(branch, false, "Conflict at ${commit.hash.take(7)}")
            }
            val newHash = worktreeClient.log(GitClient.HEAD, 1).single().hash
            commitMap[commit.hash] = newHash
        }

        // Update the branch ref in the main repo to point at the new tip
        val newTip = worktreeClient.log(GitClient.HEAD, 1).single().hash
        gitClient.branch(branch, startPoint = newTip, force = true, reflogMessage = reflogMessage)
        renderer.info { "Rebased ${entity(branch)}" }
        return SyncBranchResult(branch, true, "Rebased")
    }

    /** Rebases the current branch onto the target using normal git rebase with autosquash. */
    private fun rebaseCurrentBranch(targetBase: String): Int {
        val workingDirectory = config.workingDirectory
        val rebaseArgs = buildList {
            add("git")
            add("rebase")
            add("--autosquash")
            add(targetBase)
        }
        return ProcessBuilder(rebaseArgs)
            .directory(workingDirectory)
            .inheritIO()
            .apply { environment()["GIT_SEQUENCE_EDITOR"] = "true" }
            .start()
            .waitFor()
    }

    /**
     * Rebases the current branch using cherry-pick to maintain shared commit identity with branches
     * that were already rebased in the worktree.
     */
    private fun rebaseCurrentBranchViaCherryPick(
        branch: String,
        commits: List<Commit>,
        targetBase: String,
        commitMap: Map<String, String>,
    ): SyncBranchResult {
        // Find the deepest mapped commit and determine what to replay
        var newBase = targetBase
        var commitsToReplay = commits
        for (i in commits.indices.reversed()) {
            val mapped = commitMap[commits[i].hash]
            if (mapped != null) {
                newBase = mapped
                commitsToReplay = commits.subList(i + 1, commits.size)
                break
            }
        }

        val reflogMessage = "jaspr sync $branch"
        // Detach HEAD at the new base (checkout of a SHA detaches HEAD)
        gitClient.checkout(newBase, reflogMessage = reflogMessage)

        // Cherry-pick remaining commits
        for (commit in commitsToReplay) {
            try {
                gitClient.cherryPick(
                    commit,
                    reflogMessage = "$reflogMessage: cherry-pick of ${commit.shortMessage}",
                )
            } catch (e: CherryPickConflictException) {
                logger.debug("cherryPick failed during rebase of $branch", e)
                gitClient.cherryPickAbort()
                // Try to get back on the branch
                gitClient.checkout(branch, reflogMessage = "$reflogMessage: restore after conflict")
                renderer.warn {
                    "Conflict rebasing ${entity(branch)} at commit " +
                        "${entity(commit.hash.take(7))} (${commit.shortMessage})"
                }
                return SyncBranchResult(branch, false, "Conflict at ${commit.hash.take(7)}")
            }
        }

        // Update branch and check it out
        val newTip = gitClient.log(GitClient.HEAD, 1).single().hash
        gitClient.branch(branch, startPoint = newTip, force = true, reflogMessage = reflogMessage)
        gitClient.checkout(branch, reflogMessage = reflogMessage)
        renderer.info { "Rebased ${entity(branch)}" }
        return SyncBranchResult(branch, true, "Rebased")
    }

    companion object {
        private const val DETACHED_HEAD_NO_NAV_STATE =
            "HEAD is detached but no jaspr navigation state was found. " +
                "Check out a branch pointing to a commit with a jaspr ID before navigating."

        private val HEADER =
            """
            | ┌─────────── commit pushed
            | │ ┌─────────── exists         ┐
            | │ │ ┌───────── checks pass    │
            | │ │ │ ┌─────── ready          │ PR
            | │ │ │ │ ┌───── approved       ┘
            | │ │ │ │ │ ┌─ stack check
            | │ │ │ │ │ │
            |"""
                .trimMargin()
        private const val MAX_STATUS_SUBJECT_LENGTH = 72
        private const val COMMIT_MSG_HOOK = "commit-msg"
        private const val POST_CHECKOUT_HOOK = "post-checkout"
        private const val POST_CHECKOUT_HOOK_RESOURCE = "post-checkout-jaspr-section"
        private const val NAV_HOOK_BEGIN_MARKER = "# JASPR-NAV-HOOK-BEGIN"
        private const val NAV_HOOK_END_MARKER = "# JASPR-NAV-HOOK-END"
    }
}

const val FORCE_PUSH_PREFIX = "+"

/**
 * Much like [Iterable.windowed] with `size` == `2` but includes a leading pair of `null to
 * firstElement`
 */
fun <T : Any> Iterable<T>.windowedPairs(): List<Pair<T?, T>> {
    val iter = this
    return buildList {
        addAll(iter.take(1).map<T, Pair<T?, T>> { current -> null to current })
        addAll(iter.windowed(2).map { (prev, current) -> prev to current })
    }
}

/** Convert [ZonedDateTime] to the simplest representation as an offset from UTC. */
fun ZonedDateTime.canonicalize(): ZonedDateTime = toOffsetDateTime().toZonedDateTime()

fun truncateSubject(subject: String, max: Int): String =
    if (subject.length <= max) subject else subject.take(max - 1) + "…"
