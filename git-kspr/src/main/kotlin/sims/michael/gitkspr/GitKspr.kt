package sims.michael.gitkspr

import org.slf4j.LoggerFactory
import sims.michael.gitkspr.GitKspr.StatusBits.Status
import sims.michael.gitkspr.GitKspr.StatusBits.Status.*
import sims.michael.gitkspr.RemoteRefEncoding.REV_NUM_DELIMITER
import sims.michael.gitkspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitkspr.RemoteRefEncoding.getRemoteRefParts

class GitKspr(
    private val ghClient: GitHubClient,
    private val gitClient: JGitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
) {

    private val logger = LoggerFactory.getLogger(GitKspr::class.java)

    // git kspr push [[local-object:]target-ref]
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
        val revisionHistoryRefs = getRevisionHistoryRefs(stack, remoteBranches, remoteName)
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
                    // commit is the remote ref name (i.e. kspr/<commit-id>) of the previous commit in the stack
                    baseRefName = prevCommit?.toRemoteRefName() ?: refSpec.remoteRef,
                    title = currentCommit.shortMessage,
                    body = currentCommit.fullMessage,
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
        if (stack.isEmpty()) return "Stack is empty."

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
        gitClient.push(refSpecs)
        logger.info("Merged {} ref(s) to {}", refSpecs.size, refSpec.remoteRef)

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
        val updatedPullRequests = map { pr ->
            val commitPair = commitMap[checkNotNull(pr.commitId)]
            if (commitPair == null) {
                pr
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
