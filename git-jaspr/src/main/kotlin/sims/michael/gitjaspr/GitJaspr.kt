package sims.michael.gitjaspr

import java.nio.file.Files
import java.time.ZonedDateTime
import java.util.SortedSet
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitParsers.getSubjectAndBodyFromFullMessage
import sims.michael.gitjaspr.CommitParsers.trimFooters
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
        return buildString {
            append(theme.heading(HEADER))

            val stackChecks =
                if (numCommitsBehindBase != 0) {
                    // If the stack is out-of-date, no commits are mergeable
                    List(statuses.size) { false }
                } else {
                    statuses.fold(emptyList()) { currentStack, status ->
                        val allFlagsAreSuccess =
                            status.toStatusList(commitsWithDuplicateIds).all { it == SUCCESS }
                        val currentStackIsAllTrue = currentStack.all { it }
                        currentStack + (currentStackIsAllTrue && allFlagsAreSuccess)
                    }
                }

            for (statusAndStackCheck in statuses.reversed().zip(stackChecks.reversed())) {
                val (status, stackCheck) = statusAndStackCheck
                append("[")
                val flags = status.toStatusList(commitsWithDuplicateIds)
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
        val stackName = (getExistingStackName(stack, remoteBranches, strategy) as? Found)?.name
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
        }
    }

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

        val existingNamedStacks =
            remoteBranches.filter { branch ->
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
                    val stacksWithCommit =
                        existingNamedStacks.mapNotNull { branch ->
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

    suspend fun push(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
        stackName: String? = null,
        count: Int? = null,
        theme: Theme = MonoTheme,
        onAbandonedPrs: (List<PullRequest>) -> Boolean = { true },
    ) {
        logger.trace("push {}", refSpec)

        if (!gitClient.isWorkingDirectoryClean()) {
            throw GitJasprException(
                "Your working directory has local changes. Please commit or stash them and re-run the command."
            )
        }

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
        logExcludedCommits(excludedCommits)
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
        val existingStackName =
            (getExistingStackName(stack) as? Found)?.name?.let { existingBranchName ->
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
                    "No stack name provided and no existing stack name found on the remote."
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
        val prsNeedingBodyUpdate = prs.updateDescriptionsWithStackInfo(stack)
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
            logMergeOutOfDateWarning(
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
            logStackIsEmptyWarning()
            return
        }

        // Filter stack based on the dont-push pattern
        val (stack, excludedCommits) = filterStackByDontPushPattern(fullStack)
        logExcludedCommits(excludedCommits)

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
            prs.filter { it.baseRefName in mergedRefs }
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

    private fun logExcludedCommits(excludedCommits: List<Commit>) {
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
        logExcludedCommits(excludedCommits)

        if (filteredStack.isEmpty()) {
            renderer.warn {
                "All commits in the stack are either drafts or match the dont-push pattern. Nothing to auto-merge."
            }
            return
        }

        // Use the topmost non-excluded commit as the localRef for auto-merge
        val adjustedLocalRef = filteredStack.last().hash

        // We'll execute the auto-merge in a temporary clone after grabbing the current HEAD ref.
        // This way the user can run this in the background or in another terminal and continue to
        // use their working copy without interfering with the auto-merge process.
        val currentRef = gitClient.log(refSpec.localRef, 1).first().hash
        val tempRefSpec = refSpec.copy(localRef = adjustedLocalRef)
        logger.trace("autoMerge refSpec: {}", tempRefSpec)

        val tempDir =
            withContext(Dispatchers.IO) {
                Files.createTempDirectory("git-jaspr-automerge-").toFile()
            }
        logger.debug("Created temporary directory for auto-merge: {}", tempDir.absolutePath)

        val remoteUri =
            requireNotNull(gitClient.getRemoteUriOrNull(remoteName)) {
                "Could not find remote URI for remote: $remoteName"
            }

        val tempGit = OptimizedCliGitClient(tempDir, config.remoteBranchPrefix)
        try {
            renderer.info { "Cloning repository to temporary directory for auto-merge..." }
            val cloneTime = measureTime {
                coroutineScope {
                    val heartbeat = launch {
                        delay(5.seconds)
                        while (isActive) {
                            renderer.info { "Still cloning, please wait... (CTRL-C to cancel)" }
                            delay(5.seconds)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        tempGit.clone(remoteUri, remoteName)

                        // Add the original working directory as a remote so we can fetch unpushed
                        // commits
                        logger.debug("Adding local remote for unpushed commits")
                        tempGit.addRemote("local", config.workingDirectory.absolutePath)
                        tempGit.fetch("local")

                        tempGit.checkout(currentRef)
                    }
                    heartbeat.cancel()
                }
            }
            logger.debug("Cloned repository to temporary directory in {}", cloneTime)
        } catch (e: Exception) {
            logger.error(
                "Failed to set up temporary clone for auto-merge in ${tempDir.absolutePath}",
                e,
            )
            tempDir.deleteRecursively()
            throw e
        }

        // Run the auto-merge loop
        try {
            val tempJaspr =
                GitJaspr(
                    ghClient,
                    tempGit,
                    config.copy(workingDirectory = tempDir),
                    newUuid,
                    commitIdentOverride,
                    renderer,
                )

            var attemptsMade = 0
            while (attemptsMade < maxAttempts) {
                val numCommitsBehind =
                    tempGit
                        .logRange(tempRefSpec.localRef, "$remoteName/${tempRefSpec.remoteRef}")
                        .size
                if (numCommitsBehind > 0) {
                    val commits = if (numCommitsBehind > 1) "commits" else "commit"
                    logMergeOutOfDateWarning(numCommitsBehind, commits, tempRefSpec)
                    break
                }

                val stack =
                    tempGit.getLocalCommitStack(
                        remoteName,
                        tempRefSpec.localRef,
                        tempRefSpec.remoteRef,
                    )
                if (stack.isEmpty()) {
                    logStackIsEmptyWarning()
                    break
                }

                val statuses = tempJaspr.getRemoteCommitStatuses(stack)
                if (statuses.all(RemoteCommitStatus::isMergeable)) {
                    tempJaspr.merge(tempRefSpec)

                    // Since we merged from a separate directory, the local working copy will be
                    // out of date, so let's fetch the latest changes.
                    gitClient.fetch(remoteName)
                    break
                }
                print(tempJaspr.getStatusString(tempRefSpec))

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
                tempGit.fetch(remoteName)
            }

            // Either the merge was successful, or we exited the loop because the stack was not
            // mergeable. Either way we delete the temp directory.
            tempDir.deleteRecursively()
            logger.debug("Cleaned up temporary directory: {}", tempDir.absolutePath)
        } catch (e: Exception) {
            // Keep the temporary directory on exception for troubleshooting
            logger.error(
                "Auto-merge failed with exception. Temporary directory has been retained for troubleshooting: {}",
                tempDir.absolutePath,
                e,
            )
            throw e
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
        renderer.info {
            "Deleting ${branchesToDelete.size} ${branchOrBranches(branchesToDelete.size)}"
        }
        gitClient.push(
            branchesToDelete.map { name -> RefSpec(FORCE_PUSH_PREFIX, name) },
            config.remoteName,
        )
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
        val droppedIds = oldCommitIds - newCommitIds
        if (droppedIds.isEmpty()) return emptyList()

        return ghClient.getPullRequestsById(droppedIds.toList()).filterByMatchingTargetRef()
    }

    /** Returns a list of jaspr branches with open PRs that are not reachable by any named stack. */
    internal fun getAbandonedBranches(
        remoteBranches: List<RemoteBranch>,
        pullRequestHeadRefs: Set<String>,
    ): List<String> {
        logger.trace("getAbandonedBranches")
        val namedStackBranches =
            remoteBranches.filter { branch ->
                RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix) != null
            }
        val remoteJasprBranches =
            remoteBranches.filter { branch ->
                RemoteRef.parse(branch.name, config.remoteBranchPrefix) != null
            }

        val unmergedAndReachableFromNamedStacks =
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
                        .map(Commit::hash)
                }
                .toSet()

        // Return abandoned branches (those with open PRs not reachable by any of our named stacks)
        val branchesWithPrs =
            remoteJasprBranches.filter { branch -> branch.name in pullRequestHeadRefs }
        val refsToCheck = branchesWithPrs.map { "${config.remoteName}/${it.name}" }
        val commits = gitClient.getCommits(refsToCheck)
        return branchesWithPrs
            .filter { branch ->
                val ref = "${config.remoteName}/${branch.name}"
                commits[ref]?.hash !in unmergedAndReachableFromNamedStacks
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
    ) {
        operator fun plus(other: CleanPlan): CleanPlan {
            return CleanPlan(
                (orphanedBranches + (other.orphanedBranches - abandonedBranches)).toSortedSet(),
                (emptyNamedStackBranches + other.emptyNamedStackBranches).toSortedSet(),
                (abandonedBranches + other.abandonedBranches).toSortedSet(),
            )
        }

        fun allBranches() =
            (orphanedBranches + emptyNamedStackBranches + abandonedBranches).sorted()
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
        )
    }

    fun installCommitIdHook() {
        logger.trace("installCommitIdHook")
        val hooksDir = config.workingDirectory.resolve(".git").resolve("hooks")
        require(hooksDir.isDirectory)
        val hook = hooksDir.resolve(COMMIT_MSG_HOOK)
        val source = checkNotNull(javaClass.getResourceAsStream("/$COMMIT_MSG_HOOK"))
        renderer.info {
            "Installing/overwriting ${entity(COMMIT_MSG_HOOK)} to ${entity(hook.toString())} and setting the executable bit"
        }
        source.use { inStream ->
            hook.outputStream().use { outStream -> inStream.copyTo(outStream) }
        }
        check(hook.setExecutable(true)) { "Failed to set the executable bit on $hook" }
    }

    private fun RemoteCommitStatus.toStatusList(
        commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>
    ) =
        StatusBits(
                commitIsPushed =
                    when {
                        commitsWithDuplicateIds.containsKey(localCommit.id) -> WARNING
                        remoteCommit == null -> EMPTY
                        remoteCommit.hash != localCommit.hash -> WARNING
                        else -> SUCCESS
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
        stack: List<Commit>
    ): List<PullRequest> {
        val prsById = associateBy { checkNotNull(it.commitId) }
        val stackById = stack.associateBy(Commit::id)
        val stackPrsReordered =
            stack.fold(emptyList<PullRequest>()) { prs, commit ->
                prs + checkNotNull(prsById[checkNotNull(commit.id)])
            }
        val remoteBranchNames =
            gitClient.getRemoteBranches(config.remoteName).map(RemoteBranch::name)
        val prsNeedingBodyUpdate =
            stackPrsReordered.map { existingPr ->
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
        renderer.warn {
            "Consider running ${command(InstallCommitIdHook().commandName)} to avoid this in the future."
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

        val firstMatchIndex =
            statuses.indexOfFirst { status ->
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

    private fun logStackIsEmptyWarning() = renderer.warn { "Stack is empty." }

    private fun logMergeOutOfDateWarning(numCommitsBehind: Int, commits: String, refSpec: RefSpec) =
        renderer.warn {
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
            WARNING("❗");

            fun styledEmoji(theme: Theme) =
                when (this) {
                    SUCCESS -> theme.success(emoji)
                    FAIL -> theme.error(emoji)
                    PENDING,
                    UNKNOWN -> theme.warning(emoji)
                    EMPTY -> theme.muted(emoji)
                    WARNING -> theme.warning(emoji)
                }
        }
    }

    /** Get the current user's commit author identity that would be used for new commits. */
    private fun getCurrentUserIdent(): Ident {
        val name = gitClient.getConfigValue("user.name") ?: System.getenv("USER") ?: "unknown"
        val email = gitClient.getConfigValue("user.email") ?: "unknown@unknown.com"
        return Ident(name, email)
    }

    /**
     * Returns a suggested stack name for the given [refSpec] if the stack needs a new name, or null
     * if the stack already has an existing name on the remote. The suggestion is derived from the
     * first commit's subject.
     */
    fun suggestStackName(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
    ): String? {
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

        if (stack.isEmpty()) return null

        val existingName =
            (getExistingStackName(stack) as? Found)?.name?.let { existingBranchName ->
                RemoteNamedStackRef.parse(existingBranchName, config.remoteNamedStackBranchPrefix)
                    ?.stackName
            }
        if (existingName != null) return null

        return StackNameGenerator.generateName(stack.first().shortMessage)
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
    fun getAllNamedStacks(): List<RemoteNamedStackRef> {
        gitClient.fetch(config.remoteName, prune = true)
        return gitClient
            .getRemoteBranches(config.remoteName)
            .mapNotNull { branch ->
                RemoteNamedStackRef.parse(branch.name, config.remoteNamedStackBranchPrefix)
            }
            .sortedBy(RemoteNamedStackRef::stackName)
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

        // Force-delete the remote branch
        gitClient.push(listOf(RefSpec(FORCE_PUSH_PREFIX, stackRef)), remoteName)
        renderer.info { "Deleted remote stack branch ${entity(stackRef)}" }

        // Unset upstream tracking for any local branches that pointed to the deleted ref
        val affectedBranches = mutableListOf<String>()
        for (localBranch in gitClient.getBranchNames()) {
            val upstreamName = gitClient.getUpstreamBranchName(localBranch, remoteName)
            if (upstreamName == stackRef) {
                gitClient.setUpstreamBranchForLocalBranch(localBranch, remoteName, null)
                affectedBranches.add(localBranch)
                renderer.info { "Unset upstream for local branch '${entity(localBranch)}'" }
            }
        }
        return affectedBranches
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

    companion object {
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
