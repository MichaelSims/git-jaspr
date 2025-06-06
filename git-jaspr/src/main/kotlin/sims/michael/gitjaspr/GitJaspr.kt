package sims.michael.gitjaspr

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitParsers.getSubjectAndBodyFromFullMessage
import sims.michael.gitjaspr.CommitParsers.trimFooters
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.EMPTY
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.FAIL
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.PENDING
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.SUCCESS
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.WARNING
import sims.michael.gitjaspr.RemoteRefEncoding.REV_NUM_DELIMITER
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts
import java.time.ZonedDateTime
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.time.Duration.Companion.seconds

class GitJaspr(
    private val ghClient: GitHubClient,
    private val gitClient: GitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
    private val commitIdentOverride: Ident? = null,
) {

    private val logger = LoggerFactory.getLogger(GitJaspr::class.java)

    suspend fun getStatusString(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)): String {
        logger.trace("getStatusString {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (stack.isEmpty()) return "Stack is empty.\n"

        val statuses = getRemoteCommitStatuses(stack)
        val commitsWithDuplicateIds = statuses
            .filter { status -> status.localCommit.id != null }
            .groupingBy { status -> checkNotNull(status.localCommit.id) }
            .aggregate { _, accumulator: List<RemoteCommitStatus>?, element, _ -> accumulator.orEmpty() + element }
            .filter { (_, statuses) -> statuses.size > 1 }

        data class NamedStackInfo(val name: String, val numCommitsAhead: Int, val numCommitsBehind: Int)
        val namedStackPrefix = "${config.remoteNamedStackBranchPrefix}/"
        val namedStackInfo = gitClient
            .getUpstreamBranch(config.remoteName)
            ?.takeIf { upstream -> upstream.name.startsWith(namedStackPrefix) }
            ?.let { upstream ->
                val name = upstream.name
                val trackingBranch = "$remoteName/$name"
                NamedStackInfo(
                    name = name.removePrefix(namedStackPrefix),
                    numCommitsAhead = gitClient.logRange(trackingBranch, stack.last().hash).size,
                    numCommitsBehind = gitClient.logRange(stack.last().hash, trackingBranch).size,
                )
            }

        val numCommitsBehindBase = gitClient.logRange(stack.last().hash, "$remoteName/${refSpec.remoteRef}").size
        return buildString {
            append(HEADER)

            val stackChecks = if (numCommitsBehindBase != 0) {
                List(statuses.size) { false } // If the stack is out-of-date, no commits are mergeable
            } else {
                statuses.fold(emptyList()) { currentStack, status ->
                    val allFlagsAreSuccess = status.toStatusList(commitsWithDuplicateIds).all { it == SUCCESS }
                    val currentStackIsAllTrue = currentStack.all { it }
                    currentStack + (currentStackIsAllTrue && allFlagsAreSuccess)
                }
            }

            for (statusAndStackCheck in statuses.reversed().zip(stackChecks.reversed())) {
                val (status, stackCheck) = statusAndStackCheck
                append("[")
                val flags = status.toStatusList(commitsWithDuplicateIds)
                val statusList = flags + if (stackCheck) SUCCESS else EMPTY
                append(statusList.joinToString(separator = "", transform = Status::emoji))
                append("] ")
                append(status.localCommit.hash)
                append(" : ")
                val permalink = status.pullRequest?.permalink
                if (permalink != null) {
                    append(status.pullRequest.permalink)
                    append(" : ")
                }
                appendLine(status.localCommit.shortMessage)
            }
            if (namedStackInfo != null) {
                with(namedStackInfo) {
                    appendLine()
                    appendLine("Stack name: $name")
                    appendLine(
                        if (numCommitsBehind == 0 && numCommitsAhead == 0) {
                            "Your stack is up to date with the remote stack in '$remoteName'."
                        } else if (numCommitsBehind > 0 && numCommitsAhead == 0) {
                            "Your stack is behind the remote stack in '$remoteName' by " +
                                "$numCommitsBehind ${commitOrCommits(numCommitsBehind)}."
                        } else if (numCommitsBehind == 0) { // && numCommitsAhead > 0
                            "Your stack is ahead of the remote stack in '$remoteName' by " +
                                "$numCommitsAhead ${commitOrCommits(numCommitsAhead)}."
                        } else { // numBehind > 0 && numCommitsAhead > 0
                            "Your stack and the remote stack in '$remoteName' have diverged, and have " +
                                "$numCommitsAhead and $numCommitsBehind different commits each, " +
                                "respectively."
                        },
                    )
                }
            }
            if (numCommitsBehindBase > 0) {
                appendLine()
                append("Your stack is out-of-date with the base branch ")
                appendLine("($numCommitsBehindBase ${commitOrCommits(numCommitsBehindBase)} behind ${refSpec.remoteRef}).")
                append("You'll need to rebase it (`git rebase $remoteName/${refSpec.remoteRef}`) ")
                appendLine("before your stack will be mergeable.")
            }
            if (commitsWithDuplicateIds.isNotEmpty()) {
                appendLine()
                appendLine("Some commits in your local stack have duplicate IDs:")
                for ((id, statusList) in commitsWithDuplicateIds) {
                    appendLine("- $id: (${statusList.joinToString(", ") { it.localCommit.shortMessage }})")
                }
                appendLine("This is likely because you've based new commit messages off of those from other commits.")
                appendLine("Please correct this by amending the commits and deleting the commit-id lines, then retry your operation.")
            }
        }
    }

    suspend fun push(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF), stackName: String? = null) {
        logger.trace("push {}", refSpec)

        if (!gitClient.isWorkingDirectoryClean()) {
            throw GitJasprException(
                "Your working directory has local changes. Please commit or stash them and re-run the command.",
            )
        }

        if (stackName != null && gitClient.isHeadDetached()) {
            throw GitJasprException("Pushing a named stack from detached HEAD is not supported.")
        }
        val prefixedStackName = stackName?.let { name -> "${config.remoteNamedStackBranchPrefix}/$name" }

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        fun getLocalCommitStack() = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef)
        val originalStack = getLocalCommitStack()
        val stack = if (addCommitIdsToLocalStack(originalStack)) getLocalCommitStack() else originalStack

        val commitsWithDuplicateIds = stack
            .filter { it.id != null }
            .groupingBy { checkNotNull(it.id) }
            .aggregate { _, accumulator: List<Commit>?, element, _ -> accumulator.orEmpty() + element }
            .filter { (_, commits) -> commits.size > 1 }
        if (commitsWithDuplicateIds.isNotEmpty()) {
            logger.error("Refusing to push because some commits in your stack have duplicate IDs.")
            logger.error("Run `git jaspr status` to see which commits are affected.")
            return
        }

        val pullRequests = checkSinglePullRequestPerCommit(ghClient.getPullRequests(stack))
        val pullRequestsRebased = pullRequests.updateBaseRefForReorderedPrsIfAny(stack, refSpec.remoteRef)

        val remoteBranches = gitClient.getRemoteBranches(config.remoteName)
        val remoteRefSpecs = remoteBranches.map { b -> b.toRefSpec() }
        val outOfDateBranches = stack.map { c -> c.toRefSpec() } - remoteRefSpecs.toSet()
        val revisionHistoryRefs = getRevisionHistoryRefs(
            stack,
            remoteBranches,
            remoteName,
            outOfDateBranches.map(RefSpec::remoteRef),
        )
        val upstreamBranchName = gitClient
            .getUpstreamBranch(remoteName)
            ?.takeIf { branch -> branch.name.startsWith("${config.remoteNamedStackBranchPrefix}/") }
            ?.name
        val namedStackRefSpec = (prefixedStackName ?: upstreamBranchName)?.let { name ->
            // Convert symbolic refs (i.e. HEAD) to short hash so our comparison matches below
            val localRef = gitClient.log(refSpec.localRef, 1).single().hash
            RefSpec(localRef, name)
        }
        val outOfDateNamedStackBranch = listOfNotNull(namedStackRefSpec) - remoteRefSpecs.toSet()
        val refSpecs = outOfDateBranches.map(RefSpec::forcePush) +
            outOfDateNamedStackBranch.map(RefSpec::forcePush) +
            revisionHistoryRefs
        gitClient.push(refSpecs, config.remoteName)
        logger.info(
            "Pushed {} commit {}, {} named stack {}, and {} history {}",
            outOfDateBranches.size,
            refOrRefs(outOfDateBranches.size),
            outOfDateNamedStackBranch.size,
            refOrRefs(outOfDateNamedStackBranch.size),
            revisionHistoryRefs.size,
            refOrRefs(revisionHistoryRefs.size),
        )

        if (namedStackRefSpec != null) {
            gitClient.setUpstreamBranch(remoteName, namedStackRefSpec.remoteRef)
        }

        val existingPrsByCommitId = pullRequestsRebased.associateBy(PullRequest::commitId)

        val isDraftRegex = "^(draft|wip)\\b.*$".toRegex(IGNORE_CASE)
        val remoteBranchNames = gitClient.getRemoteBranches(config.remoteName).map(RemoteBranch::name)
        val prsToMutate = stack
            .windowedPairs()
            .map { (prevCommit, currentCommit) ->
                val existingPr = existingPrsByCommitId[currentCommit.id]
                PullRequest(
                    id = existingPr?.id,
                    commitId = currentCommit.id,
                    number = existingPr?.number,
                    headRefName = currentCommit.toRemoteRefName(),
                    // The base ref for the first commit in the stack (prevCommit == null) is the target branch
                    // (the branch the commit will ultimately merge into). The base ref for each subsequent
                    // commit is the remote ref name (i.e. jaspr/<commit-id>) of the previous commit in the stack
                    baseRefName = prevCommit?.toRemoteRefName() ?: refSpec.remoteRef,
                    title = currentCommit.shortMessage,
                    body = buildPullRequestBody(currentCommit.fullMessage, emptyList(), existingPr, remoteBranchNames),
                    checksPass = existingPr?.checksPass,
                    approved = existingPr?.approved,
                    checkConclusionStates = existingPr?.checkConclusionStates.orEmpty(),
                    permalink = existingPr?.permalink,
                    isDraft = isDraftRegex.matches(currentCommit.shortMessage),
                )
            }
            // Second pass to update descriptions with information about the stack
            .updateDescriptionsWithStackInfo(stack)
            .filter { pr -> existingPrsByCommitId[pr.commitId] != pr }

        for (pr in prsToMutate) {
            if (pr.id == null) {
                // create pull request
                ghClient.createPullRequest(pr)
            } else {
                // update pull request
                ghClient.updatePullRequest(pr)
            }
        }
        logger.info("Updated {} pull {}", prsToMutate.size, requestOrRequests(prsToMutate.size))

        // Update pull request descriptions second pass. This is necessary because we don't have the GH-assigned PR
        // numbers for new PRs until after we create them.
        logger.trace("updateDescriptions second pass {} {}", stack, prsToMutate)
        val prs = ghClient.getPullRequests(stack)
        val prsNeedingBodyUpdate = prs.updateDescriptionsWithStackInfo(stack)
        for (pr in prsNeedingBodyUpdate) {
            ghClient.updatePullRequest(pr)
        }
        logger.info("Updated descriptions for {} pull {}", prsToMutate.size, requestOrRequests(prsToMutate.size))

        print(getStatusString(refSpec))
    }

    suspend fun merge(refSpec: RefSpec) {
        logger.trace("merge {}", refSpec)
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val numCommitsBehind = gitClient.logRange(refSpec.localRef, "$remoteName/${refSpec.remoteRef}").size
        if (numCommitsBehind > 0) {
            logMergeOutOfDateWarning(numCommitsBehind, if (numCommitsBehind > 1) "commits" else "commit", refSpec)
            return
        }

        val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (stack.isEmpty()) {
            logStackIsEmptyWarning()
            return
        }

        val statuses = getRemoteCommitStatuses(stack)

        // Do a "stack check"
        // Find the first commit that isn't mergeable, and the one before it is the last mergeable commit
        val firstIndexNotMergeable = statuses.indexOfFirst { status -> !status.isMergeable }
        val indexLastMergeable = if (firstIndexNotMergeable == -1) {
            statuses.lastIndex
        } else {
            firstIndexNotMergeable - 1
        }
        if (indexLastMergeable == -1) {
            logger.warn("No commits in your local stack are mergeable.")
            return
        }

        val prs = ghClient.getPullRequests()
        val branchesToDelete = getBranchesToDeleteDuringMerge(stack.slice(0..indexLastMergeable), refSpec.remoteRef)

        val lastMergeableStatus = statuses[indexLastMergeable]
        val lastPr = checkNotNull(lastMergeableStatus.pullRequest)
        if (lastPr.baseRefName != refSpec.remoteRef) {
            logger.trace("Rebase {} onto {} in prep for merge", lastPr, refSpec.remoteRef)
            ghClient.updatePullRequest(lastPr.copy(baseRefName = refSpec.remoteRef))
        }

        val refSpecs = listOf(RefSpec(lastMergeableStatus.localCommit.hash, refSpec.remoteRef))
        gitClient.push(refSpecs, remoteName)
        logger.info("Merged {} {} to {}", indexLastMergeable + 1, refOrRefs(indexLastMergeable + 1), refSpec.remoteRef)

        val prsToClose = statuses.slice(0 until indexLastMergeable).mapNotNull(RemoteCommitStatus::pullRequest)
        for (pr in prsToClose) {
            ghClient.closePullRequest(pr)
        }

        val lastMergedRef = stack[indexLastMergeable].toRemoteRefName()
        val prsToRebase = prs
            .filter { it.baseRefName == lastMergedRef }
            .map { it.copy(baseRefName = refSpec.remoteRef) }
        logger.trace("Rebasing {} prs to {}", prsToRebase.size, refSpec.remoteRef)
        for (pr in prsToRebase) {
            ghClient.updatePullRequest(pr)
        }

        // Call this for the benefit of the stub client in case we're running within tests. In production, this does
        // nothing as GitHub will "auto close" PRs that are merged
        ghClient.autoClosePrs()

        // Do this cleanup separately after we've rebased remaining PRs. Otherwise, if we delete a branch that's the
        // base ref for a current PR, GitHub will implicitly close it.
        cleanUpBranches(branchesToDelete)
    }

    suspend fun autoMerge(refSpec: RefSpec, pollingIntervalSeconds: Int = 10) {
        logger.trace("autoMerge {} {}", refSpec, pollingIntervalSeconds)
        while (true) {
            val remoteName = config.remoteName
            gitClient.fetch(remoteName)

            val numCommitsBehind = gitClient.logRange(refSpec.localRef, "$remoteName/${refSpec.remoteRef}").size
            if (numCommitsBehind > 0) {
                val commits = if (numCommitsBehind > 1) "commits" else "commit"
                logMergeOutOfDateWarning(numCommitsBehind, commits, refSpec)
                break
            }

            val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
            if (stack.isEmpty()) {
                logStackIsEmptyWarning()
                break
            }

            val statuses = getRemoteCommitStatuses(stack)
            if (statuses.all(RemoteCommitStatus::isMergeable)) {
                merge(refSpec)
                break
            }
            print(getStatusString(refSpec))

            if (statuses.any { status -> status.checksPass == false }) {
                logger.warn("Checks are failing. Aborting auto-merge.")
                break
            }
            if (statuses.any { status -> status.approved == false }) {
                logger.warn("PRs are not approved. Aborting auto-merge.")
                break
            }
            if (statuses.any { status -> status.isDraft == true }) {
                logger.warn("Some PRs in the stack are drafts. Aborting auto-merge.")
                break
            }

            logger.info("Delaying for $pollingIntervalSeconds seconds... (CTRL-C to cancel)")
            delay(pollingIntervalSeconds.seconds)
        }
    }

    internal suspend fun getOrphanedBranches(): List<String> {
        logger.trace("getOrphanedBranches")
        val pullRequests = ghClient.getPullRequests().map(PullRequest::headRefName).toSet()
        gitClient.fetch(config.remoteName, prune = true)
        val orphanedBranches = gitClient
            .getRemoteBranches(config.remoteName)
            .map(RemoteBranch::name)
            .filter {
                val remoteRefParts = getRemoteRefParts(it, config.remoteBranchPrefix)
                if (remoteRefParts != null) {
                    val (targetRef, commitId, _) = remoteRefParts
                    buildRemoteRef(commitId, targetRef) !in pullRequests
                } else {
                    false
                }
            }
        return orphanedBranches
    }

    suspend fun clean(dryRun: Boolean) {
        logger.trace("clean{}", if (dryRun) " (dryRun)" else "")
        gitClient.fetch(config.remoteName)
        val orphanedBranches = getOrphanedBranches()
        for (branch in orphanedBranches) {
            val shortMessage = gitClient.log("${config.remoteName}/$branch", 1).singleOrNull()?.shortMessage
            logger.info("{}{} is orphaned", branch, if (shortMessage != null) " ($shortMessage)" else "")
        }
        if (!dryRun) {
            logger.info("Deleting {} {}", orphanedBranches.size, branchOrBranches(orphanedBranches.size))
            gitClient.push(orphanedBranches.map { RefSpec(FORCE_PUSH_PREFIX, it) }, config.remoteName)
        }
    }

    fun installCommitIdHook() {
        logger.trace("installCommitIdHook")
        val hooksDir = config.workingDirectory.resolve(".git").resolve("hooks")
        require(hooksDir.isDirectory)
        val hook = hooksDir.resolve(COMMIT_MSG_HOOK)
        val source = checkNotNull(javaClass.getResourceAsStream("/$COMMIT_MSG_HOOK"))
        logger.info("Installing/overwriting {} to {} and setting the executable bit", COMMIT_MSG_HOOK, hook)
        source.use { inStream -> hook.outputStream().use { outStream -> inStream.copyTo(outStream) } }
        check(hook.setExecutable(true)) { "Failed to set the executable bit on $hook" }
    }

    private fun RemoteCommitStatus.toStatusList(commitsWithDuplicateIds: Map<String, List<RemoteCommitStatus>>) =
        StatusBits(
            commitIsPushed = when {
                commitsWithDuplicateIds.containsKey(localCommit.id) -> WARNING
                remoteCommit == null -> EMPTY
                remoteCommit.hash != localCommit.hash -> WARNING
                else -> SUCCESS
            },
            pullRequestExists = if (pullRequest != null) SUCCESS else EMPTY,
            checksPass = when {
                pullRequest == null -> EMPTY
                checksPass == null -> PENDING
                checksPass -> SUCCESS
                else -> FAIL
            },
            readyForReview = if (pullRequest != null && isDraft != true) SUCCESS else EMPTY,
            approved = when {
                pullRequest == null -> EMPTY
                approved == null -> EMPTY
                approved -> SUCCESS
                else -> FAIL
            },
        ).toList()

    private fun List<PullRequest>.updateDescriptionsWithStackInfo(stack: List<Commit>): List<PullRequest> {
        val prsById = associateBy { checkNotNull(it.commitId) }
        val stackById = stack.associateBy(Commit::id)
        val stackPrsReordered = stack.fold(emptyList<PullRequest>()) { prs, commit ->
            prs + checkNotNull(prsById[checkNotNull(commit.id)])
        }
        val remoteBranchNames = gitClient.getRemoteBranches(config.remoteName).map(RemoteBranch::name)
        val prsNeedingBodyUpdate = stackPrsReordered
            .map { existingPr ->
                val commit = checkNotNull(stackById[existingPr.commitId]) {
                    "Couldn't find commit for PR with commitId ${existingPr.commitId}"
                }
                val newBody = buildPullRequestBody(
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

            append("⚠\uFE0F *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). ")
            appendLine("Do not merge manually using the UI - doing so may have unexpected results.*")
        }
    }

    private fun StringBuilder.appendHistoryLinksIfApplicable(pr: PullRequest, remoteBranches: List<String>) {
        val (host, owner, name) = config.gitHubInfo
        val regex = "^${pr.headRefName}_(\\d+)".toRegex()
        val historyRefs = remoteBranches.filter { regex.matchEntire(it) != null }.sorted().reversed()
        if (historyRefs.isNotEmpty()) {
            append("  - ")
            val historyPairs = listOf(pr.headRefName) + historyRefs
            append(
                historyPairs
                    .windowed(2)
                    .joinToString(", ") { (new, old) ->
                        fun String.toRevisionDescription() = checkNotNull(regex.matchEntire(this)).groupValues[1]
                        val oldDescription = old.toRevisionDescription()
                        val newDescription = if (new == pr.headRefName) "Current" else new.toRevisionDescription()
                        "[%s..%s](https://%s/%s/%s/compare/%s..%s)"
                            .format(oldDescription, newDescription, host, owner, name, old, new)
                    },
            )
            appendLine()
        }
    }

    internal suspend fun getRemoteCommitStatuses(stack: List<Commit>): List<RemoteCommitStatus> {
        logger.trace("getRemoteCommitStatuses")
        val remoteBranchesById = gitClient.getRemoteBranchesById(config.remoteName)
        val prsById = if (stack.isNotEmpty()) {
            ghClient.getPullRequests(stack.filter { commit -> commit.id != null }).associateBy(PullRequest::commitId)
        } else {
            emptyMap()
        }
        return stack
            .map { commit ->
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

        stackBeingMerged.map { c -> c.toRemoteRefName() }

        val deletionCandidates = stackBeingMerged
            .asSequence()
            .map { commit -> checkNotNull(commit.id) }
            .map { id -> buildRemoteRef(id, targetRef, config.remoteBranchPrefix) }
            .mapNotNull { remoteRef -> getRemoteRefParts(remoteRef, config.remoteBranchPrefix) }
            .map { (targetRef, commitId, _) -> TargetRefToCommitId(targetRef, commitId) }
            .toList()

        logger.trace("Deletion candidates {}", deletionCandidates)

        val branchesToDelete = gitClient
            .getRemoteBranches(config.remoteName)
            .map(RemoteBranch::name)
            .filter { branchName ->
                getRemoteRefParts(branchName, config.remoteBranchPrefix)
                    ?.let { (targetRef, commitId, _) ->
                        TargetRefToCommitId(targetRef, commitId) in deletionCandidates
                    } == true
            }
            .map { branchName -> RefSpec(FORCE_PUSH_PREFIX, branchName) }
        logger.trace("Deletion list {}", branchesToDelete)
        return branchesToDelete
    }

    private suspend fun cleanUpBranches(branchesToDelete: List<RefSpec>) {
        logger.info("Cleaning up {} {}.", branchesToDelete.size, branchOrBranches(branchesToDelete.size))
        val maxTries = 3
        val delayBetweenTries = 500L
        var tries = 0
        while (true) {
            try {
                gitClient.push(branchesToDelete, config.remoteName)
                tries++
                if (tries > 1) {
                    logger.info("Successfully deleted branches after {} tries.", tries)
                }
                break
            } catch (e: Exception) {
                tries++
                logger.error("Failed to delete branches (attempt $tries of $maxTries)", e)
                if (tries < maxTries) {
                    logger.info("Retrying in {} ms...", delayBetweenTries)
                    delay(delayBetweenTries)
                } else {
                    throw e
                }
            }
        }
    }

    class SinglePullRequestPerCommitConstraintViolation(override val message: String) : RuntimeException(message)

    private fun checkSinglePullRequestPerCommit(pullRequests: List<PullRequest>): List<PullRequest> {
        logger.trace("checkSinglePullRequestPerCommit")
        val commitsWithMultiplePrs = pullRequests
            .groupBy { pr -> checkNotNull(pr.commitId) }
            .filterValues { prs -> prs.size > 1 }
        if (commitsWithMultiplePrs.isNotEmpty()) {
            throw SinglePullRequestPerCommitConstraintViolation(
                "Some commits have multiple open PRs; please correct this and retry your operation: " +
                    commitsWithMultiplePrs.toString(),
            )
        }
        return pullRequests
    }

    private fun getRevisionHistoryRefs(
        stack: List<Commit>,
        branches: List<RemoteBranch>,
        remoteName: String,
        outOfDateBranches: List<String>,
    ): List<RefSpec> {
        logger.trace("getRevisionHistoryRefs")
        val branchNames = branches.map(RemoteBranch::name).toSet()
        val nextRevisionById = branchNames
            .mapNotNull { branchName ->
                getRemoteRefParts(branchName, config.remoteBranchPrefix)?.let { (_, id, revisionNumber) ->
                    id to (revisionNumber ?: 0) + 1
                }
            }
            .sortedBy { (_, revisionNumber) -> revisionNumber }
            .toMap()

        return stack
            .mapNotNull { commit ->
                nextRevisionById[commit.id]
                    ?.let { revision ->
                        val refName = commit.toRemoteRefName()
                        RefSpec("$remoteName/$refName", "%s%s%02d".format(refName, REV_NUM_DELIMITER, revision))
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

        logger.warn("Some commits in your local stack are missing commit IDs and are being amended to add them.")
        logger.warn("Consider running ${InstallCommitIdHook().commandName} to avoid this in the future.")
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
     * This is necessary because there is no way to atomically force push the PR branches AND update their baseRefs.
     * We have to do one or the other first, and if at any point a PR's `baseRefName..headRefName` is empty, GitHub
     * will implicitly close that PR and make it impossible for us to update in the future. To avoid this we temporarily
     * update the [PullRequest.baseRefName] of any moved PR to point to [remoteRef] (which should be the ultimate
     * target of the PR and therefore guaranteed to be non-empty). These PRs will be updated again after we force push
     * the branches.
     */
    private suspend fun List<PullRequest>.updateBaseRefForReorderedPrsIfAny(
        commitStack: List<Commit>,
        remoteRef: String,
    ): List<PullRequest> {
        logger.trace("updateBaseRefForReorderedPrsIfAny")

        val commitMap = commitStack.windowedPairs().associateBy { (_, commit) -> checkNotNull(commit.id) }
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

    private fun logStackIsEmptyWarning() = logger.warn("Stack is empty.")

    private fun logMergeOutOfDateWarning(numCommitsBehind: Int, commits: String, refSpec: RefSpec) =
        logger.warn(
            "Cannot merge because your stack is out-of-date with the base branch ({} {} behind {}).",
            numCommitsBehind,
            commits,
            refSpec.remoteRef,
        )

    private fun Commit.toRefSpec(): RefSpec = RefSpec(hash, toRemoteRefName())
    private fun Commit.toRemoteRefName(): String = buildRemoteRef(checkNotNull(id), prefix = config.remoteBranchPrefix)

    private data class StatusBits(
        val commitIsPushed: Status,
        val pullRequestExists: Status,
        val checksPass: Status,
        val readyForReview: Status,
        val approved: Status,
    ) {
        fun toList(): List<Status> = listOf(commitIsPushed, pullRequestExists, checksPass, readyForReview, approved)

        @Suppress("unused")
        enum class Status(val emoji: String) {
            SUCCESS("✅"),
            FAIL("❌"),
            PENDING("⌛"),
            UNKNOWN("❓"),
            EMPTY("ㄧ"),
            WARNING("❗"),
        }
    }

    private fun refOrRefs(count: Int) = if (count == 1) "ref" else "refs"
    private fun requestOrRequests(count: Int) = if (count == 1) "request" else "requests"
    private fun branchOrBranches(count: Int) = if (count == 1) "branch" else "branches"
    private fun commitOrCommits(count: Int) = if (count == 1) "commit" else "commits"

    companion object {
        private val HEADER = """
            | ┌─────────── commit pushed
            | │ ┌─────────── exists       ┐
            | │ │ ┌───────── checks pass  │ PR
            | │ │ │ ┌─────── ready        │
            | │ │ │ │ ┌───── approved     ┘
            | │ │ │ │ │ ┌─ stack check
            | │ │ │ │ │ │ 

        """.trimMargin()
        private const val COMMIT_MSG_HOOK = "commit-msg"
    }
}

const val FORCE_PUSH_PREFIX = "+"

/** Much like [Iterable.windowed] with `size` == `2` but includes a leading pair of `null to firstElement` */
fun <T : Any> Iterable<T>.windowedPairs(): List<Pair<T?, T>> {
    val iter = this
    return buildList {
        addAll(iter.take(1).map<T, Pair<T?, T>> { current -> null to current })
        addAll(iter.windowed(2).map { (prev, current) -> prev to current })
    }
}

/** Convert [ZonedDateTime] to the simplest representation as an offset from UTC. */
fun ZonedDateTime.canonicalize(): ZonedDateTime = toOffsetDateTime().toZonedDateTime()
