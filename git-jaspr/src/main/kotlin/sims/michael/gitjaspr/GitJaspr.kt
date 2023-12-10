package sims.michael.gitjaspr

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitFooters.trimFooters
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status
import sims.michael.gitjaspr.GitJaspr.StatusBits.Status.*
import sims.michael.gitjaspr.RemoteRefEncoding.REV_NUM_DELIMITER
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts
import kotlin.time.Duration.Companion.seconds

class GitJaspr(
    private val ghClient: GitHubClient,
    private val gitClient: JGitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
) {

    private val logger = LoggerFactory.getLogger(GitJaspr::class.java)

    // git jaspr push [[local-object:]target-ref]
    suspend fun push(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)) {
        logger.trace("push {}", refSpec)

        check(gitClient.isWorkingDirectoryClean()) {
            "Your working directory has local changes. Please commit or stash them and re-run the command."
        }

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        fun getLocalCommitStack() = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef)
        val stack = addCommitIdsToLocalStack(getLocalCommitStack()) ?: getLocalCommitStack()

        val pullRequests = checkSinglePullRequestPerCommit(ghClient.getPullRequests(stack))
        val pullRequestsRebased = pullRequests.updateBaseRefForReorderedPrsIfAny(stack, refSpec.remoteRef)

        val remoteBranches = gitClient.getRemoteBranches()
        val outOfDateBranches = stack.map { c -> c.toRefSpec() } - remoteBranches.map { b -> b.toRefSpec() }.toSet()
        val revisionHistoryRefs = getRevisionHistoryRefs(
            stack,
            remoteBranches,
            remoteName,
            outOfDateBranches.map(RefSpec::remoteRef),
        )
        // TODO consider push with lease here
        val refSpecs = outOfDateBranches.map(RefSpec::forcePush) + revisionHistoryRefs
        gitClient.push(refSpecs)
        logger.info("Pushed {} commit ref(s) and {} history ref(s)", outOfDateBranches.size, revisionHistoryRefs.size)

        val existingPrsByCommitId = pullRequestsRebased.associateBy(PullRequest::commitId)

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
                    body = buildPullRequestBody(currentCommit.fullMessage),
                    checksPass = existingPr?.checksPass,
                    approved = existingPr?.approved,
                    checkConclusionStates = existingPr?.checkConclusionStates.orEmpty(),
                    permalink = existingPr?.permalink,
                )
            }
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
        logger.info("Updated {} pull request(s)", prsToMutate.size)

        // Update pull request descriptions (has to be done in a second pass because we don't have the GH-assigned PR
        // numbers until the first creation pass)
        updateDescriptions(stack, prsToMutate)
    }

    private suspend fun updateDescriptions(stack: List<Commit>, prsToMutate: List<PullRequest>) {
        val stackById = stack.associateBy(Commit::id)
        val prsById = ghClient.getPullRequests(stack).associateBy { checkNotNull(it.commitId) }
        val stackPrsReordered = stack.fold(emptyList<PullRequest>()) { prs, commit ->
            prs + checkNotNull(prsById[checkNotNull(commit.id)])
        }
        val prsNeedingBodyUpdate = stackPrsReordered
            .map { existingPr ->
                val commit = checkNotNull(stackById[existingPr.commitId]) {
                    "Couldn't find commit for PR with commitId ${existingPr.commitId}"
                }
                val newBody = buildPullRequestBody(
                    fullMessage = commit.fullMessage,
                    pullRequests = stackPrsReordered.reversed(),
                    existingPr.commitId,
                )
                existingPr.copy(body = newBody)
            }
        logger.debug("{}", stack)
        for (pr in prsNeedingBodyUpdate) {
            ghClient.updatePullRequest(pr)
        }
        logger.info("Updated descriptions for {} pull request(s)", prsToMutate.size)
    }

    private fun buildPullRequestBody(
        fullMessage: String,
        pullRequests: List<PullRequest> = emptyList(),
        currentCommitId: String? = null,
    ): String {
        return buildString {
            append(trimFooters(fullMessage))
            appendLine()
            if (pullRequests.isNotEmpty()) {
                appendLine("**Stack**:")
                for (pr in pullRequests) {
                    append("- #${pr.number}")
                    if (pr.commitId == currentCommitId) {
                        append(" ⬅")
                    }
                    appendLine()
                    appendHistoryLinksIfApplicable(pr)
                }
                appendLine()
            }

            append("⚠\uFE0F *Part of a stack created by [jaspr](https://github.com/MichaelSims/git-jaspr). ")
            appendLine("Do not merge manually using the UI - doing so may have unexpected results.*")
        }
    }

    private fun StringBuilder.appendHistoryLinksIfApplicable(pr: PullRequest) {
        val (host, owner, name) = config.gitHubInfo
        val regex = "^${pr.headRefName}_(\\d+)".toRegex()
        val branches = gitClient.getRemoteBranches().map(RemoteBranch::name)
        val historyRefs = branches.filter { regex.matchEntire(it) != null }.sorted().reversed()
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

    suspend fun getRemoteCommitStatuses(stack: List<Commit>): List<RemoteCommitStatus> {
        val remoteBranchesById = gitClient.getRemoteBranchesById()
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
                    approved = prsById[commit.id]?.approved,
                )
            }
    }

    // TODO consider pulling the target ref from the branch name instead of requiring it on the command line
    suspend fun getStatusString(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)): String {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (stack.isEmpty()) return "Stack is empty.\n"

        val statuses = getRemoteCommitStatuses(stack)
        val numCommitsBehind = gitClient.logRange(stack.last().hash, "$remoteName/${refSpec.remoteRef}").size
        return buildString {
            append(HEADER)
            var stackCheck = numCommitsBehind == 0
            for (status in statuses) {
                append("[")
                val statusBits = StatusBits(
                    commitIsPushed = if (status.remoteCommit != null) SUCCESS else EMPTY,
                    pullRequestExists = if (status.pullRequest != null) SUCCESS else EMPTY,
                    checksPass = when {
                        status.pullRequest == null -> EMPTY
                        status.checksPass == null -> PENDING
                        status.checksPass -> SUCCESS
                        else -> FAIL
                    },
                    approved = when {
                        status.pullRequest == null -> EMPTY
                        status.approved == null -> EMPTY
                        status.approved -> SUCCESS
                        else -> FAIL
                    },
                )
                val flags = statusBits.toList()
                if (!flags.all { it == SUCCESS }) stackCheck = false
                val statusList = flags + if (stackCheck) SUCCESS else EMPTY
                append(statusList.joinToString(separator = "", transform = Status::emoji))
                append("] ")
                val permalink = status.pullRequest?.permalink
                if (permalink != null) {
                    append(status.pullRequest.permalink)
                    append(" : ")
                }
                appendLine(status.localCommit.shortMessage)
            }
            if (numCommitsBehind > 0) {
                appendLine()
                append("Your stack is out-of-date with the base branch ")
                val commits = if (numCommitsBehind > 1) "commits" else "commit"
                appendLine("($numCommitsBehind $commits behind ${refSpec.remoteRef}).")
                append("You'll need to rebase it (`git rebase $remoteName/${refSpec.remoteRef}`) ")
                appendLine("before your stack will be mergeable.")
            }
        }
    }

    suspend fun autoMerge(refSpec: RefSpec, pollingIntervalSeconds: Int = 10) {
        while (true) {
            val remoteName = config.remoteName
            gitClient.fetch(remoteName)

            val numCommitsBehind = gitClient.logRange(refSpec.localRef, "$remoteName/${refSpec.remoteRef}").size
            if (numCommitsBehind > 0) {
                val commits = if (numCommitsBehind > 1) "commits" else "commit"
                logger.warn(
                    "Cannot merge because your stack is out-of-date with the base branch ({} {} behind {}).",
                    numCommitsBehind,
                    commits,
                    refSpec.remoteRef,
                )
                break
            }

            val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
            if (stack.isEmpty()) {
                logger.warn("Stack is empty.")
                break
            }

            val statuses = getRemoteCommitStatuses(stack)
            if (statuses.all { status -> status.approved == true && status.checksPass == true }) {
                merge(refSpec)
                break
            }
            print(getStatusString(refSpec))
            logger.info("Delaying for $pollingIntervalSeconds seconds... (CTRL-C to cancel)")
            delay(pollingIntervalSeconds.seconds)
        }
    }

    suspend fun merge(refSpec: RefSpec) {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val numCommitsBehind = gitClient.logRange(refSpec.localRef, "$remoteName/${refSpec.remoteRef}").size
        if (numCommitsBehind > 0) {
            val commits = if (numCommitsBehind > 1) "commits" else "commit"
            logger.warn(
                "Cannot merge because your stack is out-of-date with the base branch ({} {} behind {}).",
                numCommitsBehind,
                commits,
                refSpec.remoteRef,
            )
            return
        }

        val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        if (stack.isEmpty()) {
            logger.warn("Stack is empty.")
            return
        }

        val statuses = getRemoteCommitStatuses(stack)

        val indexLastMergeable = statuses.indexOfLast { it.approved == true && it.checksPass == true }
        if (indexLastMergeable == -1) {
            logger.warn("No commits in your local stack are mergeable.")
            return
        }
        val lastMergeableStatus = statuses[indexLastMergeable]
        val lastPr = checkNotNull(lastMergeableStatus.pullRequest)
        if (lastPr.baseRefName != refSpec.remoteRef) {
            ghClient.updatePullRequest(lastPr.copy(baseRefName = refSpec.remoteRef))
        }

        val refSpecs = listOf(RefSpec(lastMergeableStatus.localCommit.hash, refSpec.remoteRef))
        val targetRefAndCommitIdsToDelete = stack
            .slice(0..indexLastMergeable)
            .map { checkNotNull(it.id) }
            .map { buildRemoteRef(it, refSpec.remoteRef, config.remoteBranchPrefix) }
            .mapNotNull { getRemoteRefParts(it, config.remoteBranchPrefix) }
            .map { (targetRef, commitId, _) -> targetRef to commitId }
        val branchesToDelete = gitClient
            .getRemoteBranches()
            .map(RemoteBranch::name)
            .filter {
                getRemoteRefParts(it, config.remoteBranchPrefix)
                    ?.let { (targetRef, commitId, _) -> targetRef to commitId in targetRefAndCommitIdsToDelete } == true
            }
            .map { RefSpec("+", it) }
        gitClient.push(refSpecs + branchesToDelete)
        logger.info("Merged {} ref(s) to {}", indexLastMergeable + 1, refSpec.remoteRef)

        val prsToClose = statuses.slice(0 until indexLastMergeable).mapNotNull(RemoteCommitStatus::pullRequest)
        for (pr in prsToClose) {
            ghClient.closePullRequest(pr)
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

    suspend fun clean(dryRun: Boolean) {
        val pullRequests = ghClient.getPullRequests().map(PullRequest::headRefName).toSet()
        gitClient.fetch(config.remoteName)
        val orphanedBranches = gitClient
            .getRemoteBranches()
            .map(RemoteBranch::name)
            .filter {
                val remoteRefParts = getRemoteRefParts(it, config.remoteBranchPrefix)
                if (remoteRefParts != null) {
                    val (targetRef, commitId, _) = remoteRefParts
                    // TODO why is it returning with the separator?
                    val targetRefWithoutSeparator = targetRef.trim('/')
                    buildRemoteRef(commitId, targetRefWithoutSeparator) !in pullRequests
                } else {
                    false
                }
            }
        for (branch in orphanedBranches) {
            logger.info("{} is orphaned", branch)
        }
        if (!dryRun) {
            logger.info("Deleting {} branch(es)", orphanedBranches.size)
            gitClient.push(orphanedBranches.map { RefSpec("+", it) })
        }
    }

    class SinglePullRequestPerCommitConstraintViolation(override val message: String) : RuntimeException(message)

    private fun checkSinglePullRequestPerCommit(pullRequests: List<PullRequest>): List<PullRequest> {
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

    private fun addCommitIdsToLocalStack(commits: List<Commit>): List<Commit>? {
        logger.trace("addCommitIdsToLocalStack {}", commits)
        val indexOfFirstCommitMissingId = commits.indexOfFirst { it.id == null }
        if (indexOfFirstCommitMissingId == -1) {
            logger.trace("No commits are missing IDs")
            return commits
        } else {
            logger.warn("Some commits in your local stack are missing commit IDs and are being amended to add them.")
            logger.warn("Consider running ${InstallCommitIdHook().commandName} to avoid this in the future.")
            val missing = commits.slice(indexOfFirstCommitMissingId until commits.size)
            val refName = "${missing.first().hash}^"
            gitClient.reset(refName) // TODO need a test that we're resetting and not doing this in detached HEAD
            for (commit in missing) {
                gitClient.cherryPick(commit)
                if (commit.id == null) {
                    val commitId = newUuid()
                    gitClient.setCommitId(commitId)
                }
            }
            return null
        }
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
            // TODO doesn't this update all of them? Do we really need to do that?
            ghClient.updatePullRequest(pr)
        }

        return updatedPullRequests
    }

    private fun Commit.toRefSpec(): RefSpec = RefSpec(hash, toRemoteRefName())
    private fun Commit.toRemoteRefName(): String = buildRemoteRef(checkNotNull(id), prefix = config.remoteBranchPrefix)

    private data class StatusBits(
        val commitIsPushed: Status,
        val pullRequestExists: Status,
        val checksPass: Status,
        val approved: Status,
    ) {
        fun toList(): List<Status> = listOf(commitIsPushed, pullRequestExists, checksPass, approved)

        @Suppress("unused")
        enum class Status(val emoji: String) {
            SUCCESS("✅"), FAIL("❌"), PENDING("⌛"), UNKNOWN("❓"), EMPTY("➖"), WARNING("⚠️")
        }
    }

    companion object {
        private val HEADER = """
            | ┌─ commit is pushed
            | │ ┌─ pull request exists
            | │ │ ┌─ github checks pass
            | │ │ │ ┌── pull request approved
            | │ │ │ │ ┌─── stack check
            | │ │ │ │ │

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
