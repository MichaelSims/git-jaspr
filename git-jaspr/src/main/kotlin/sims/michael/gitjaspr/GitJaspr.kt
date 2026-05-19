package sims.michael.gitjaspr

import java.io.File
import java.io.RandomAccessFile
import java.time.ZonedDateTime
import java.util.SortedSet
import kotlin.text.RegexOption.IGNORE_CASE
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
import sims.michael.gitjaspr.CommitParsers.trimFooters
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.AHEAD
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.AHEAD_DIVERGENT
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.BEHIND
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.BEHIND_DIVERGENT
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.DIVERGENT
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.EMPTY
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.FAIL
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.PENDING
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.SUCCESS
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.WARNING
import sims.michael.gitjaspr.RemoteRefEncoding.REV_NUM_DELIMITER
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef

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
    interface GetStatusStringStrategy {
        fun getRemoteBranches(): List<RemoteBranch>

        fun getLocalCommitStack(localRef: String, remoteRef: String): List<Commit>

        fun logRange(since: String, until: String): List<Commit>

        suspend fun getPullRequests(commits: List<Commit>): List<PullRequest>
    }

    private fun defaultStrategy() =
        object : GetStatusStringStrategy {
            override fun getRemoteBranches() = gitClient.getRemoteBranches(config.remoteName)

            override fun getLocalCommitStack(localRef: String, remoteRef: String) =
                gitClient.getLocalCommitStack(config.remoteName, localRef, remoteRef)

            override fun logRange(since: String, until: String) = gitClient.logRange(since, until)

            override suspend fun getPullRequests(commits: List<Commit>) =
                ghClient.getPullRequests(commits)
        }

    suspend fun getStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theme: Theme = MonoTheme,
    ): String {
        gitClient.fetch(config.remoteName)
        return getStatusString(refSpec, theme, defaultStrategy())
    }

    suspend fun getStatusString(
        refSpec: RefSpec,
        remoteBranches: List<RemoteBranch>,
        theme: Theme = MonoTheme,
    ): String {
        val strategy = defaultStrategy()
        return getStatusString(
            refSpec,
            theme,
            object : GetStatusStringStrategy by strategy {
                override fun getRemoteBranches() = remoteBranches
            },
        )
    }

    suspend fun getStatusString(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        theme: Theme = MonoTheme,
        strategy: GetStatusStringStrategy,
    ): String {
        logger.trace("getStatusString {}", refSpec)
        val remoteName = config.remoteName

        val remoteBranches = strategy.getRemoteBranches()
        val stack = strategy.getLocalCommitStack(refSpec.localRef, refSpec.remoteRef)
        if (stack.isEmpty()) return theme.muted("Stack is empty.") + "\n"

        val statuses = getRemoteCommitStatuses(stack, remoteBranches, strategy)
        val commitsWithDuplicateIds =
            statuses
                .filter { status -> status.localCommit.id != null }
                .groupingBy { status -> checkNotNull(status.localCommit.id) }
                .aggregate { _, accumulator: List<RemoteCommitStatus>?, element, _ ->
                    accumulator.orEmpty() + element
                }
                .filter { (_, statuses) -> statuses.size > 1 }

        val numCommitsBehindBase =
            strategy.logRange(stack.last().hash, "$remoteName/${refSpec.remoteRef}").size
        return DivergenceClassifier(config.workingDirectory, getJasprDir()).use { classifier ->
            val divergenceByLocalHash = classifyDivergences(statuses, classifier)
            buildStatusString(
                statuses,
                commitsWithDuplicateIds,
                divergenceByLocalHash,
                numCommitsBehindBase,
                remoteName,
                refSpec,
                stack,
                remoteBranches,
                strategy,
                theme,
            )
        }
    }

    private fun classifyDivergences(
        statuses: List<RemoteCommitStatus>,
        classifier: DivergenceClassifier,
    ): Map<String, DivergenceClassifier.Result> =
        statuses
            .mapNotNull { status ->
                val localHash = status.localCommit.hash
                status.remoteCommit
                    ?.hash
                    ?.takeIf { remoteHash -> remoteHash != localHash }
                    ?.let { remoteHash -> localHash to classifier.classify(localHash, remoteHash) }
            }
            .toMap()

    private fun buildStatusString(
        statuses: List<RemoteCommitStatus>,
        commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>,
        divergenceByLocalHash: Map<String, DivergenceClassifier.Result>,
        numCommitsBehindBase: Int,
        remoteName: String,
        refSpec: RefSpec,
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        strategy: GetStatusStringStrategy,
        theme: Theme,
    ): String = buildString {
        append(theme.heading(HEADER))

        val stackChecks =
            if (numCommitsBehindBase != 0) {
                // If the stack is out-of-date, no commits are mergeable
                List(statuses.size) { false }
            } else {
                statuses.fold(emptyList()) { currentStack, status ->
                    val allFlagsAreSuccess =
                        status.toStatusList(commitsWithDuplicateIds, divergenceByLocalHash).all {
                            it == SUCCESS
                        }
                    val currentStackIsAllTrue = currentStack.all { it }
                    currentStack + (currentStackIsAllTrue && allFlagsAreSuccess)
                }
            }

        for (statusAndStackCheck in statuses.reversed().zip(stackChecks.reversed())) {
            val (status, stackCheck) = statusAndStackCheck
            append("[")
            val flags = status.toStatusList(commitsWithDuplicateIds, divergenceByLocalHash)
            val statusList = flags + if (stackCheck) SUCCESS else EMPTY
            append(statusList.joinToString(separator = "") { it.styledEmoji(theme) })
            append("] ")
            append(theme.hash(status.localCommit.hash))
            append(" : ")
            val permalink = status.pullRequest?.permalink
            if (permalink != null) {
                append(theme.url(status.pullRequest.permalink))
                append(" : ")
            }
            appendLine(theme.value(status.localCommit.shortMessage))
        }

        appendNamedStackInfo(stack, remoteBranches, theme, strategy)

        if (numCommitsBehindBase > 0) {
            appendLine()
            appendLine(
                theme.warning(
                    "Your stack is out-of-date with the base branch " +
                        "($numCommitsBehindBase ${commitOrCommits(numCommitsBehindBase)} behind ${refSpec.remoteRef})."
                )
            )
            append("You'll need to rebase it (")
            append(theme.command("`git rebase $remoteName/${refSpec.remoteRef}`"))
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
        strategy: GetStatusStringStrategy,
    ) {
        val remoteName = config.remoteName
        data class NamedStackInfo(
            val name: String,
            val numCommitsAhead: Int,
            val numCommitsBehind: Int,
        )
        val stackSearchResult = getExistingStackName(stack, remoteBranches, strategy)
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
                    numCommitsAhead = strategy.logRange(trackingBranch, headStackCommit).size,
                    numCommitsBehind = strategy.logRange(headStackCommit, trackingBranch).size,
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
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)}."
                        )
                    } else if (numCommitsBehind == 0) { // && numCommitsAhead > 0
                        theme.warning(
                            "Your stack is ahead of the remote stack in '$remoteName' by " +
                                "$numCommitsAhead ${commitOrCommits(numCommitsAhead)}."
                        )
                    } else { // numBehind > 0 && numCommitsAhead > 0
                        theme.error(
                            "Your stack and the remote stack in '$remoteName' have diverged, and have " +
                                "$numCommitsAhead and $numCommitsBehind different commits each, " +
                                "respectively."
                        )
                    }
                )
            }
            appendRemoteOnlyCommits(stack, stackName, theme, strategy)
        }
    }

    private fun StringBuilder.appendRemoteOnlyCommits(
        localStack: List<Commit>,
        stackName: String,
        theme: Theme,
        strategy: GetStatusStringStrategy,
    ) {
        val remoteName = config.remoteName
        val targetRef =
            RemoteNamedStackRef.parse(stackName, config.remoteNamedStackBranchPrefix)?.targetRef
                ?: return
        val remoteStack =
            try {
                strategy.getLocalCommitStack("$remoteName/$stackName", targetRef)
            } catch (e: Exception) {
                logger.debug("Failed to walk remote stack '{}': {}", stackName, e.message)
                return
            }
        val localCommitIds = localStack.mapNotNull(Commit::id).toSet()
        val remoteOnly = remoteStack.filter { it.id != null && it.id !in localCommitIds }
        if (remoteOnly.isEmpty()) return

        val localMaxDate = localStack.maxOfOrNull(Commit::commitDate)
        val remoteOnlyMaxDate = checkNotNull(remoteOnly.maxOfOrNull(Commit::commitDate))
        // Mark as "likely stale" only when the local stack is strictly newer than every
        // remote-only commit; ties or remote-newer get the highlighted treatment.
        val likelyStale = localMaxDate != null && localMaxDate > remoteOnlyMaxDate

        appendLine()
        val count = remoteOnly.size
        val heading =
            if (!likelyStale) {
                "Remote stack has $count ${commitOrCommits(count)} not in your local stack:"
            } else {
                "Remote stack has $count ${commitOrCommits(count)} not in your local stack " +
                    "(likely stale):"
            }
        appendLine(if (!likelyStale) theme.warning(heading) else theme.muted(heading))
        for (commit in remoteOnly) {
            val line =
                "  ${BEHIND.emoji}  ${commit.hash.take(7)}  " +
                    "${commit.commitDate.toLocalDate()}  ${commit.shortMessage}"
            appendLine(if (!likelyStale) line else theme.muted(line))
        }
    }

    data class StackNameSuggestions(
        val candidates: List<String>,
        val ambiguousStackNames: List<String> = emptyList(),
    )

    private sealed class NamedStackSearchResult

    private data class Found(val name: String) : NamedStackSearchResult()

    private data class MultipleStacksContainCommit(val stackNames: List<String>) :
        NamedStackSearchResult()

    private data object NotFound : NamedStackSearchResult()

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
    ): NamedStackSearchResult = getExistingStackName(stack, remoteBranches, defaultStrategy())

    private fun getExistingStackName(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        strategy: GetStatusStringStrategy,
    ): NamedStackSearchResult {
        logger.trace("getExistingStackName")
        require(stack.isNotEmpty())

        val existingNamedStacks = remoteBranches.filter { branch ->
            RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix) != null
        }

        // Search the remote branches for named stack refs that point to stacks with unmerged
        // commits. Find the first commit in our local stack that is contained in exactly one named
        // stack and return its name.
        val result =
            stack
                .reversed()
                .filter { commit -> commit.id != null }
                .firstNotNullOfOrNull { commit ->
                    val stacksWithCommit = existingNamedStacks.mapNotNull { branch ->
                        val namedStackRefParts =
                            checkNotNull(
                                RemoteNamedStackRef.parse(
                                    branch.name,
                                    config.remoteNamedStackBranchPrefix,
                                )
                            )
                        branch.takeIf {
                            val remoteName = config.remoteName
                            val targetInRemote = "$remoteName/${namedStackRefParts.targetRef}"
                            val namedStackInRemote = "$remoteName/${branch.name}"
                            strategy
                                .logRange(targetInRemote, namedStackInRemote)
                                .mapNotNull(Commit::id)
                                .contains(checkNotNull(commit.id))
                        }
                    }
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
        val localStack =
            gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
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
            gitClient.getLocalCommitStack(
                remoteName,
                "$remoteName/$stackName",
                namedStackRef.targetRef,
            )

        return DivergenceClassifier(config.workingDirectory, getJasprDir()).use { classifier ->
            val rows = alignStacks(localStack, remoteStack, classifier)
            renderCompare(rows, "$remoteName/$stackName", theme)
        }
    }

    suspend fun push(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        stackName: String? = null,
        count: Int? = null,
        theme: Theme = MonoTheme,
        onAbandonedPrs: (List<PullRequest>) -> Boolean = { true },
    ) {
        logger.trace("push {}", refSpec)

        if (gitClient.hasUncommittedChangesToTrackedFiles()) {
            throw GitJasprException(
                "Your working directory has uncommitted changes to tracked files. " +
                    "Please commit or stash them and re-run the command."
            )
        }

        installCommitIdHook()

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        fun getLocalCommitStack() =
            gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef)
        val originalStack = resolveCount(getLocalCommitStack(), count)
        val stackWithIds =
            if (addCommitIdsToLocalStack(originalStack)) {
                resolveCount(getLocalCommitStack(), count)
            } else {
                originalStack
            }

        // Filter stack based on the dont-push pattern
        val (stack, excludedCommits) = filterStackByDontPushPattern(stackWithIds)
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

        val remoteBranches = gitClient.getRemoteBranches(config.remoteName)
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
        val stackSearchResult = getExistingStackName(stack)
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

        for (pr in prsToMutate) {
            if (pr.id == null) {
                // create the pull request
                ghClient.createPullRequest(pr)
            } else {
                // update the pull request
                ghClient.updatePullRequest(pr)
            }
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

    suspend fun merge(refSpec: RefSpec, count: Int? = null) {
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
            resolveCount(
                gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef),
                count,
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
        delay(2_000)
        cleanUpBranches(branchesToDelete)
    }

    /**
     * Resolves a count parameter to a sublist of the stack. Positive values take that many commits
     * from the bottom of the stack. Negative values exclude that many commits from the top.
     */
    private fun resolveCount(stack: List<Commit>, count: Int?): List<Commit> {
        if (count == null) return stack
        require(count != 0) { "Count must not be zero." }
        val effective =
            if (count > 0) {
                require(count <= stack.size) { "Count $count exceeds stack size of ${stack.size}." }
                count
            } else {
                val result = stack.size + count
                require(result >= 1) {
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
        theme: Theme = MonoTheme,
    ) {
        logger.trace("autoMerge {} {}", refSpec, pollingIntervalSeconds)

        // Filter the stack to exclude commits matching the dont-push pattern or draft commits
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val fullStack =
            resolveCount(
                gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef),
                count,
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
        val worktreeGit = OptimizedCliGitClient(worktreeDir, config.remoteBranchPrefix)

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
                    worktreeGit.getLocalCommitStack(
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
            gitClient.deleteBranches(localBranchesToDelete)
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

    internal suspend fun getOrphanedBranches(): List<String> {
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

    internal fun getOrphanedBranches(
        remoteBranches: List<RemoteBranch>,
        pullRequestHeadRefs: Set<String>,
    ): List<String> {
        logger.trace("getOrphanedBranches")
        return remoteBranches.map(RemoteBranch::name).filter { name ->
            val remoteRef = RemoteRef.parse(name, config.remoteBranchPrefix)
            if (remoteRef != null) {
                remoteRef.copy(revisionNum = null).name() !in pullRequestHeadRefs
            } else {
                false
            }
        }
    }

    internal fun getEmptyNamedStackBranches(remoteBranches: List<RemoteBranch>): List<String> {
        logger.trace("getEmptyNamedStackBranches")
        return remoteBranches.map(RemoteBranch::name).filter { branchName ->
            val parts = RemoteNamedStackRef.parse(branchName, config.remoteNamedStackBranchPrefix)
            if (parts != null) {
                // Named stack branch - check if it has commits not in its target
                val stack =
                    gitClient.getLocalCommitStack(
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
    internal fun getOrphanedNamedStackBranches(remoteBranches: List<RemoteBranch>): List<String> {
        logger.trace("getOrphanedNamedStackBranches")
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
                gitClient.getLocalCommitStack(
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
    internal suspend fun findPrsAbandonedByPush(
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
    internal fun getAbandonedBranches(
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
        val orphanedNamedStackBranches: SortedSet<String> = sortedSetOf(),
    ) {
        operator fun plus(other: CleanPlan): CleanPlan {
            return CleanPlan(
                (orphanedBranches + (other.orphanedBranches - abandonedBranches)).toSortedSet(),
                (emptyNamedStackBranches + other.emptyNamedStackBranches).toSortedSet(),
                (abandonedBranches + other.abandonedBranches).toSortedSet(),
                (orphanedNamedStackBranches + other.orphanedNamedStackBranches).toSortedSet(),
            )
        }

        fun allBranches() =
            (orphanedBranches +
                    emptyNamedStackBranches +
                    abandonedBranches +
                    orphanedNamedStackBranches)
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
        val orphanedNamedStackBranches = getOrphanedNamedStackBranches(remoteBranches)
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
            orphanedNamedStackBranches.toSortedSet(),
        )
    }

    fun installCommitIdHook() {
        logger.trace("installCommitIdHook")
        val hooksDir = resolveGitCommonDir().resolve("hooks")
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
        val hooksDir = resolveGitCommonDir().resolve("hooks")
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
        val hooksDir = resolveGitCommonDir().resolve("hooks")
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
        commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>,
        divergenceByLocalHash: Map<String, DivergenceClassifier.Result>,
    ) =
        StatusBits(
                commitIsPushed =
                    when {
                        commitsWithDuplicateIds.containsKey(localCommit.id) -> WARNING
                        remoteCommit == null -> EMPTY
                        remoteCommit.hash == localCommit.hash -> SUCCESS
                        divergenceByLocalHash[localCommit.hash] ==
                            DivergenceClassifier.Result.DIVERGENT ->
                            when {
                                localCommit.commitDate > remoteCommit.commitDate -> AHEAD_DIVERGENT
                                remoteCommit.commitDate > localCommit.commitDate -> BEHIND_DIVERGENT
                                else -> DIVERGENT
                            }
                        // Hashes differ but content is equivalent — indicate which side is fresher
                        localCommit.commitDate > remoteCommit.commitDate -> AHEAD
                        remoteCommit.commitDate > localCommit.commitDate -> BEHIND
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
            val fullMessageWithoutFooters = trimFooters(fullMessage)
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

    internal suspend fun getRemoteCommitStatuses(stack: List<Commit>): List<RemoteCommitStatus> =
        getRemoteCommitStatuses(stack, gitClient.getRemoteBranches(config.remoteName))

    internal suspend fun getRemoteCommitStatuses(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
    ): List<RemoteCommitStatus> = getRemoteCommitStatuses(stack, remoteBranches, defaultStrategy())

    private suspend fun getRemoteCommitStatuses(
        stack: List<Commit>,
        remoteBranches: List<RemoteBranch>,
        strategy: GetStatusStringStrategy,
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
                strategy
                    .getPullRequests(stack.filter { commit -> commit.id != null })
                    .filterByMatchingTargetRef()
                    .associateBy(PullRequest::commitId)
            } else {
                emptyMap()
            }
        return stack.map { commit ->
            RemoteCommitStatus(
                localCommit = commit,
                remoteCommit = remoteBranchesById[commit.id]?.commit,
                pullRequest = prsById[commit.id],
                checksPass = prsById[commit.id]?.checksPass,
                isDraft = prsById[commit.id]?.isDraft,
                approved = prsById[commit.id]?.approved,
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
                    delay(delayBetweenTries)
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
            gitClient.cherryPick(commit, commitIdentOverride)
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
            /** Local commit is fresher than its remote counterpart (push needed). */
            AHEAD("⬆️"),
            /** Remote commit is fresher than its local counterpart (pull needed). */
            BEHIND("⬇️"),
            /**
             * Local commit is fresher than the remote AND their content has diverged: an amend or a
             * conflict-resolution edit landed locally that isn't on the remote. Pushing would
             * overwrite the remote with the new content.
             */
            AHEAD_DIVERGENT("⏫"),
            /**
             * Remote commit is fresher than the local AND their content has diverged: an amend or
             * conflict-resolution edit landed on the remote that isn't local. Pulling/overwriting
             * locally would discard the local version.
             */
            BEHIND_DIVERGENT("⏬"),
            /**
             * Local and remote commits share a commit-id but their content differs, with no clear
             * date ordering between them. Rare; usually only when both sides were committed in the
             * same wall-clock second.
             */
            DIVERGENT("🔀");

            fun styledEmoji(theme: Theme) =
                when (this) {
                    SUCCESS -> theme.success(emoji)
                    FAIL -> theme.error(emoji)
                    PENDING,
                    UNKNOWN -> theme.warning(emoji)
                    EMPTY -> theme.muted(emoji)
                    WARNING,
                    AHEAD,
                    BEHIND,
                    AHEAD_DIVERGENT,
                    BEHIND_DIVERGENT,
                    DIVERGENT -> theme.warning(emoji)
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
    ): StackNameSuggestions {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        val stack =
            gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef).let { original ->
                filterStackByDontPushPattern(
                        if (addCommitIdsToLocalStack(original)) {
                            gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef)
                        } else {
                            original
                        }
                    )
                    .included
            }

        if (stack.isEmpty()) return StackNameSuggestions(emptyList())

        val searchResult = getExistingStackName(stack)

        if (searchResult is Found) return StackNameSuggestions(emptyList())

        val commitBasedCandidates =
            stack
                .flatMap { commit ->
                    StackNameGenerator.generateNameCandidates(commit.shortMessage)
                }
                .distinct()

        return when (searchResult) {
            is MultipleStacksContainCommit ->
                StackNameSuggestions(
                    candidates = searchResult.stackNames + commitBasedCandidates,
                    ambiguousStackNames = searchResult.stackNames,
                )
            else -> StackNameSuggestions(commitBasedCandidates)
        }
    }

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

    /**
     * Checks out a named stack by creating or switching to a local branch that tracks the remote
     * named stack ref.
     */
    fun checkoutNamedStack(namedStackRef: RemoteNamedStackRef) {
        val localBranchName = namedStackRef.stackName
        val remoteName = config.remoteName
        val remoteTrackingRef = "$remoteName/${namedStackRef.name()}"
        val branchExists = localBranchName in gitClient.getBranchNames()

        if (!branchExists) {
            gitClient.branch(localBranchName, startPoint = remoteTrackingRef)
            gitClient.checkout(localBranchName)
            gitClient.setUpstreamBranch(remoteName, namedStackRef.name())
            renderer.info {
                "Checked out named stack '${entity(localBranchName)}' on new local branch"
            }
        } else {
            // Branch exists - checkout and verify upstream matches
            val previousRef = gitClient.log(GitClient.HEAD, 1).single().hash
            gitClient.checkout(localBranchName)
            val upstream = gitClient.getUpstreamBranch(remoteName)
            if (upstream != null && upstream.name == namedStackRef.name()) {
                renderer.info { "Switched to existing local branch '${entity(localBranchName)}'" }
            } else {
                // Restore the previous branch before throwing
                gitClient.checkout(previousRef)
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
            gitClient.deleteBranches(localBranchesToDelete)
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

    /** Intended for tests */
    internal fun clone(transformConfig: (Config) -> Config) =
        GitJaspr(
            ghClient,
            gitClient,
            transformConfig(config),
            newUuid,
            commitIdentOverride,
            renderer,
        )

    /**
     * Resolves the shared git directory. In a worktree `.git` is a file pointing elsewhere, so we
     * use `git rev-parse --git-common-dir` which works in both normal repos and worktrees.
     */
    private fun resolveGitCommonDir(): File {
        val process =
            ProcessBuilder("git", "rev-parse", "--git-common-dir")
                .directory(config.workingDirectory)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Failed to resolve git common dir: $output" }
        return config.workingDirectory.resolve(output).canonicalFile
    }

    private fun getJasprDir(): File =
        config.workingDirectory.resolve(".git/jaspr").also { it.mkdirs() }

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
            runWorktreeRemove(worktreeDir)
        }
        val setupTime = measureTime {
            val proc =
                ProcessBuilder("git", "worktree", "add", "--detach", worktreeDir.absolutePath, ref)
                    .directory(config.workingDirectory)
                    .redirectErrorStream(true)
                    .start()
            val output = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "Failed to create auto-merge worktree: $output" }
        }
        logger.debug("Created auto-merge worktree in {}", setupTime)
    }

    private fun removeAutoMergeWorktree(worktreeDir: File) {
        runWorktreeRemove(worktreeDir)
    }

    private fun runWorktreeRemove(worktreeDir: File) {
        val proc =
            ProcessBuilder("git", "worktree", "remove", "--force", worktreeDir.absolutePath)
                .directory(config.workingDirectory)
                .redirectErrorStream(true)
                .start()
        val output = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        if (rc != 0) {
            logger.debug("git worktree remove returned {}: {}", rc, output)
        }
    }

    private val navStateFile
        get() = getJasprDir().resolve("nav-state.json")

    fun readNavState(): NavState? {
        val file = navStateFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<NavState>(file.readText())
        } catch (e: Exception) {
            logger.warn("Failed to read nav state, clearing", e)
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
        get() = getJasprDir().resolve("split-state.json")

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
    fun navigateDown(targetRef: String, n: Int) {
        val existingState = readNavState()
        val state =
            if (existingState != null && gitClient.isHeadDetached()) {
                reconcile(existingState, targetRef)
            } else {
                initNavState(targetRef)
            }

        val targetIndex = state.cursorIndex - n
        require(targetIndex >= 0) {
            "Cannot move down $n commit(s) — only ${state.cursorIndex} commit(s) below current position."
        }

        gitClient.checkout(state.stack[targetIndex].sha)
        writeNavState(state.copy(cursorIndex = targetIndex))
        installNavSessionHook()
    }

    /** Navigate to the bottom of the stack (first commit above the target branch). */
    fun navigateToBottom(targetRef: String) {
        val existingState = readNavState()
        val state =
            if (existingState != null && gitClient.isHeadDetached()) {
                reconcile(existingState, targetRef)
            } else {
                initNavState(targetRef)
            }

        require(state.cursorIndex > 0) { "Already at the bottom of the stack." }

        gitClient.checkout(state.stack.first().sha)
        writeNavState(state.copy(cursorIndex = 0))
        installNavSessionHook()
    }

    /**
     * Navigate up N commits by cherry-picking from the saved stack above the current HEAD. If all
     * remaining commits are replayed, restores the source branch and ends the session.
     *
     * @return true if navigation was performed, false if there was no active session (caller can
     *   render a friendly message rather than treating it as an error)
     */
    fun navigateUp(n: Int, targetRef: String? = null): Boolean {
        val state = activeNavSessionOrNull(targetRef) ?: return false

        val aboveCount = state.stack.size - state.cursorIndex - 1
        require(aboveCount > 0) { "Already at the top of the stack." }
        require(n <= aboveCount) {
            "Cannot move up $n commit(s) — only $aboveCount commit(s) above current position."
        }

        val updatedStack = state.stack.toMutableList()
        for (i in 1..n) {
            val entry = updatedStack[state.cursorIndex + i]
            val newCommit =
                gitClient.cherryPick(gitClient.log(entry.sha, 1).single(), commitIdentOverride)
            updatedStack[state.cursorIndex + i] = entry.copy(sha = newCommit.hash)
        }

        val newCursor = state.cursorIndex + n
        if (newCursor == updatedStack.lastIndex) { // We've replayed all commits, end the session
            endNavSession(state.copy(stack = updatedStack))
        } else {
            writeNavState(state.copy(stack = updatedStack, cursorIndex = newCursor))
        }
        return true
    }

    /**
     * Navigate to the top of the stack by replaying all remaining commits.
     *
     * @return true if navigation was performed, false if there was no active session
     */
    fun navigateToTop(targetRef: String? = null): Boolean {
        val state = activeNavSessionOrNull(targetRef) ?: return false

        val aboveCount = state.stack.size - state.cursorIndex - 1
        require(aboveCount > 0) { "Already at the top of the stack." }

        val updatedStack = state.stack.toMutableList()
        for (i in (state.cursorIndex + 1)..updatedStack.lastIndex) {
            val entry = updatedStack[i]
            val newCommit =
                gitClient.cherryPick(gitClient.log(entry.sha, 1).single(), commitIdentOverride)
            updatedStack[i] = entry.copy(sha = newCommit.hash)
        }

        endNavSession(state.copy(stack = updatedStack))
        return true
    }

    /**
     * Build the initial [NavState] from the current branch. Called on the first nav down/bottom
     * when no session exists yet.
     */
    private fun initNavState(targetRef: String): NavState {
        val branchName = gitClient.getCurrentBranchName()
        require(branchName.isNotEmpty()) { DETACHED_HEAD_NO_NAV_STATE }

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)
        val commits = gitClient.getLocalCommitStack(remoteName, GitClient.HEAD, targetRef)
        require(commits.isNotEmpty()) { "Stack is empty." }

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
        return NavState(headBeforeDetach = branchName, stack = stack, cursorIndex = stack.lastIndex)
    }

    /**
     * Reconcile the persisted [NavState] with the actual git state. Detects new commits (inserted
     * by the user), removed commits (hard reset), and amended commits (same Commit-Id, different
     * SHA).
     *
     * - New commits are inserted into the stack at their actual position.
     * - Missing commits are prepended to the replay queue (above cursor) in their original order.
     * - SHA changes for the same Commit-Id are updated in place.
     */
    fun reconcile(state: NavState, targetRef: String): NavState {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        // Walk from HEAD to the merge base to get what's actually materialized
        val actualBelow = gitClient.getLocalCommitStack(remoteName, GitClient.HEAD, targetRef)

        // The expected "below" portion of the stack
        val expectedBelow = state.stack.subList(0, state.cursorIndex + 1)

        // Build a map of actual commits by Commit-Id
        val actualByCommitId = linkedMapOf<String, StackEntry>()
        for (commit in actualBelow) {
            val commitId = commit.id ?: continue
            actualByCommitId[commitId] = StackEntry(sha = commit.hash, commitId = commitId)
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
    private fun requireActiveNavSession(targetRef: String? = null): NavState =
        activeNavSessionOrNull(targetRef)
            ?: throw IllegalArgumentException(
                "No navigation session in progress (already at the top of the stack)."
            )

    /**
     * Returns the active nav session state (after reconciliation), or null when no session is
     * active. Throws only for the invalid "detached HEAD without nav state" case. Auto-clears stale
     * state.
     */
    private fun activeNavSessionOrNull(targetRef: String? = null): NavState? {
        val state = readNavState()
        val detached = gitClient.isHeadDetached()
        return when {
            detached && state != null ->
                if (targetRef != null) reconcile(state, targetRef) else state
            detached -> throw IllegalArgumentException(DETACHED_HEAD_NO_NAV_STATE)
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
        endNavSession(state)
        return discarded
    }

    /**
     * Cancel the navigation session, restoring the original branch to its position before the
     * session started. Any commits created during the session that are not reachable from the
     * restored branch become orphaned.
     *
     * If a split is in progress, this acts as a hard escape: the working tree is reset to the
     * original branch, untracked files are removed, and split state is cleared.
     *
     * @return the SHAs of commits that were below the cursor but are not part of the original
     *   branch (i.e., commits created or cherry-picked during the session)
     */
    fun cancelNavSession(): List<String> {
        val state = requireActiveNavSession()

        // Walk from the original branch tip to build the set of SHAs that will remain
        // reachable after we restore the branch. We walk enough commits to cover the stack.
        val originalShas =
            gitClient.log(state.headBeforeDetach, state.stack.size + 1).map(Commit::hash).toSet()

        // Walk from current HEAD to find commits that are NOT in the original branch.
        // These will be orphaned when we restore it. Stop at the first original commit.
        val headLog = gitClient.log(GitClient.HEAD, state.stack.size + 1)
        val orphanedShas = headLog.takeWhile { it.hash !in originalShas }.map(Commit::hash)

        if (isSplitInProgress()) {
            // Hard escape: discard working tree changes and any commits made during the split
            gitClient.reset(state.headBeforeDetach)
            gitClient.cleanUntracked()
            clearSplitState()
        }

        gitClient.checkout(state.headBeforeDetach)
        clearNavState()
        return orphanedShas
    }

    /**
     * End the navigation session: update the original branch to the new tip, check it out, clear
     * the state.
     */
    private fun endNavSession(state: NavState) {
        val newTip = gitClient.log(GitClient.HEAD, 1).single().hash
        gitClient.branch(state.headBeforeDetach, startPoint = newTip, force = true)
        gitClient.checkout(state.headBeforeDetach)
        clearNavState()
    }

    /**
     * Drop [n] commits from the top of the current stack.
     *
     * When a nav session is active, the dropped commits are removed from the nav stack and the
     * cursor is adjusted. When no session is active, this is equivalent to `git reset --hard
     * HEAD~n`.
     */
    fun drop(n: Int, targetRef: String? = null) {
        require(n > 0) { "Must drop at least 1 commit." }

        val state = readNavState()
        if (state != null && gitClient.isHeadDetached()) {
            val reconciled = if (targetRef != null) reconcile(state, targetRef) else state
            require(n <= reconciled.cursorIndex + 1) {
                "Cannot drop $n commit(s) — only ${reconciled.cursorIndex + 1} commit(s) at or below current position."
            }

            // Remove the top n entries from below the cursor
            val newStack =
                reconciled.stack.toMutableList().apply {
                    val removeFrom = reconciled.cursorIndex - n + 1
                    repeat(n) { removeAt(removeFrom) }
                }
            val newCursor = reconciled.cursorIndex - n

            gitClient.reset("HEAD~$n")

            if (newStack.isEmpty()) {
                // Dropped everything — end the session
                clearNavState()
            } else {
                writeNavState(reconciled.copy(stack = newStack, cursorIndex = newCursor))
            }
        } else {
            // No nav session — just hard reset
            gitClient.reset("HEAD~$n")
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
        require(readSplitState() == null) {
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

        gitClient.resetMixed("HEAD~1")
        return head.shortMessage
    }

    /**
     * Unsplit: restore the original commit from before the split, absorbing all current working
     * tree and index changes into it. If a nav session is active, re-inserts the restored commit
     * into the nav stack.
     *
     * @return the short message of the restored commit (for display)
     */
    fun unsplit(): String {
        val splitState = requireNotNull(readSplitState()) { "No split in progress." }

        gitClient.resetSoft(splitState.unsplitSha)
        gitClient.add(".")
        gitClient.commit(amend = true)

        val restoredCommit = gitClient.log(GitClient.HEAD, 1).single()

        // If in a nav session, re-insert the restored commit into the stack
        val navState = readNavState()
        if (navState != null && gitClient.isHeadDetached()) {
            val entry =
                StackEntry(
                    sha = restoredCommit.hash,
                    commitId =
                        checkNotNull(restoredCommit.id) {
                            "Restored commit has no jaspr commit ID."
                        },
                )
            val newStack =
                navState.stack.toMutableList().apply { add(navState.cursorIndex + 1, entry) }
            writeNavState(navState.copy(stack = newStack, cursorIndex = navState.cursorIndex + 1))
        }

        clearSplitState()
        return restoredCommit.shortMessage
    }

    /**
     * Fold the current commit into an adjacent commit. The current commit is eliminated; the
     * neighbor absorbs its changes and keeps its own identity (message, commit-id, author).
     *
     * @param direction "down" to fold into the parent (default), "up" to fold into the child
     * @return the short message of the surviving commit
     */
    fun fold(direction: String = "down"): String {
        require(readSplitState() == null) { "Cannot fold while a split is in progress." }

        return when (direction) {
            "down" -> foldDown()
            "up" -> foldUp()
            else ->
                throw IllegalArgumentException("Direction must be 'up' or 'down', got '$direction'")
        }
    }

    /**
     * Fold current commit down into its parent. Soft reset removes the current commit, amend merges
     * its changes into the parent. Parent's identity is preserved.
     */
    private fun foldDown(): String {
        val navState = readNavState()
        if (navState != null && gitClient.isHeadDetached()) {
            require(navState.cursorIndex > 0) {
                "Cannot fold down — already at the bottom of the stack."
            }
            // Soft reset removes current commit, amend merges into parent
            gitClient.resetSoft("HEAD~1")
            gitClient.commit(amend = true)

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
            require(stack.size >= 2) { "Cannot fold down — nothing below the current commit." }
            gitClient.resetSoft("HEAD~1")
            gitClient.commit(amend = true)
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
            requireNotNull(readNavState()?.takeIf { gitClient.isHeadDetached() }) {
                "Cannot fold up without an active navigation session — there is no commit above."
            }
        val aboveIndex = navState.cursorIndex + 1
        require(aboveIndex <= navState.stack.lastIndex) {
            "Cannot fold up — already at the top of the stack."
        }

        val aboveEntry = navState.stack[aboveIndex]
        val aboveCommit = gitClient.log(aboveEntry.sha, 1).single()

        // Cherry-pick the above commit onto the current position
        gitClient.cherryPick(aboveCommit, commitIdentOverride)

        // Now HEAD has: ...parent -> current -> above'
        // Soft reset 2 to collapse both into staged changes on top of parent
        gitClient.resetSoft("HEAD~2")

        // Recommit with the above commit's message, footers, and author
        val aboveFooters = CommitParsers.getFooters(aboveCommit.fullMessage)
        val aboveMessage = CommitParsers.trimFooters(aboveCommit.fullMessage)
        gitClient.commit(
            message = aboveMessage,
            footerLines = aboveFooters,
            author = aboveCommit.author,
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
                            checkNotNull(survivor.id) { "Surviving commit has no jaspr commit ID." },
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
                val commits = gitClient.getLocalCommitStack(remoteName, branch, targetRef)
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
                val addResult =
                    ProcessBuilder("git", "worktree", "add", "--detach", worktreeDir.absolutePath)
                        .directory(config.workingDirectory)
                        .redirectErrorStream(true)
                        .start()
                        .let { proc ->
                            proc.inputStream.bufferedReader().readText()
                            proc.waitFor()
                        }
                check(addResult == 0) { "Failed to create worktree" }

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
                        rebaseBranchInWorktree(worktreeDir, branch, commits, targetBase, commitMap)
                    results.add(result)
                    if (!result.success) {
                        commits.forEach { skippedCommits.add(it.hash) }
                    }
                }
            } finally {
                // Clean up the worktree
                ProcessBuilder("git", "worktree", "remove", "--force", worktreeDir.absolutePath)
                    .directory(config.workingDirectory)
                    .redirectErrorStream(true)
                    .start()
                    .let { proc ->
                        proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                    }
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
        worktreeDir: File,
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

        if (commitsToReplay.isEmpty()) {
            // All commits were already rebased by a shallower branch, just update the ref
            val tip = commitMap[commits.last().hash]
            if (tip != null) {
                gitBranchForce(config.workingDirectory, branch, tip)
                renderer.info {
                    "Updated ${entity(branch)} (all commits shared with earlier branch)"
                }
            }
            return SyncBranchResult(branch, true, "Rebased (shared commits)")
        }

        // Checkout the new base in the worktree
        val checkoutResult = gitCommand(worktreeDir, "checkout", "--detach", newBase)
        if (checkoutResult != 0) {
            return SyncBranchResult(branch, false, "Failed to checkout base")
        }

        // Cherry-pick each commit
        for (commit in commitsToReplay) {
            val cpResult = gitCommand(worktreeDir, "cherry-pick", "--allow-empty", commit.hash)
            if (cpResult != 0) {
                // Abort the cherry-pick and bail
                gitCommand(worktreeDir, "cherry-pick", "--abort")
                renderer.warn {
                    "Conflict rebasing ${entity(branch)} at commit ${entity(commit.hash.take(7))} (${commit.shortMessage})"
                }
                return SyncBranchResult(branch, false, "Conflict at ${commit.hash.take(7)}")
            }
            // Record the mapping: old hash -> new hash in worktree
            val newHash = gitOutput(worktreeDir, "rev-parse", "HEAD")
            commitMap[commit.hash] = newHash
        }

        // Update the branch ref in the main repo to point at the new tip
        val newTip = gitOutput(worktreeDir, "rev-parse", "HEAD")
        gitBranchForce(config.workingDirectory, branch, newTip)
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
        val workingDirectory = config.workingDirectory

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

        // Detach HEAD at the new base
        gitCommand(workingDirectory, "checkout", "--detach", newBase)

        // Cherry-pick remaining commits
        for (commit in commitsToReplay) {
            val result = gitCommand(workingDirectory, "cherry-pick", "--allow-empty", commit.hash)
            if (result != 0) {
                gitCommand(workingDirectory, "cherry-pick", "--abort")
                // Try to get back on the branch
                gitCommand(workingDirectory, "checkout", branch)
                renderer.warn {
                    "Conflict rebasing ${entity(branch)} at commit " +
                        "${entity(commit.hash.take(7))} (${commit.shortMessage})"
                }
                return SyncBranchResult(branch, false, "Conflict at ${commit.hash.take(7)}")
            }
        }

        // Update branch and check it out
        val newTip = gitOutput(workingDirectory, "rev-parse", "HEAD")
        gitBranchForce(workingDirectory, branch, newTip)
        gitCommand(workingDirectory, "checkout", branch)
        renderer.info { "Rebased ${entity(branch)}" }
        return SyncBranchResult(branch, true, "Rebased")
    }

    /** Run a git command in a directory and return the exit code. */
    private fun gitCommand(dir: File, vararg args: String): Int =
        ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start().let {
            proc ->
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
        }

    /** Run a git command and return trimmed stdout. */
    @Suppress("SameParameterValue")
    private fun gitOutput(dir: File, vararg args: String): String =
        ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start().let {
            proc ->
            val output = proc.inputStream.bufferedReader().readText().trim()
            check(proc.waitFor() == 0) { "git ${args.toList()} failed: $output" }
            output
        }

    /** Force-update a branch ref to point at a specific commit. */
    private fun gitBranchForce(dir: File, branch: String, commit: String) {
        val result = gitCommand(dir, "branch", "-f", branch, commit)
        check(result == 0) { "Failed to update branch $branch to $commit" }
    }

    companion object {
        private const val DETACHED_HEAD_NO_NAV_STATE =
            "HEAD is detached but no jaspr navigation state was found. " +
                "Check out a branch pointing to a commit with a jaspr ID before navigating."

        private val HEADER =
            """
            | ┌─────────── commit pushed
            | │ ┌─────────── exists       ┐
            | │ │ ┌───────── checks pass  │ PR
            | │ │ │ ┌─────── ready        │
            | │ │ │ │ ┌───── approved     ┘
            | │ │ │ │ │ ┌─ stack check
            | │ │ │ │ │ │ 
            |"""
                .trimMargin()
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
