package sims.michael.gitkspr

import org.slf4j.LoggerFactory
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
        gitClient.push(outOfDateBranches.map(RefSpec::forcePush) + revisionHistoryRefs)

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
    }

    suspend fun getRemoteCommitStatuses(
        refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF),
    ): List<RemoteCommitStatus> {
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val remoteBranchesById = gitClient.getRemoteBranchesById()
        val stack = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
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

    suspend fun getStatusString(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)): String {
        data class StatusBits(
            val commitIsPushed: Boolean = false,
            val pullRequestExists: Boolean = false,
            val checksPass: Boolean? = null,
            val approved: Boolean? = null,
        ) {
            fun toList(): List<Boolean?> = listOf(commitIsPushed, pullRequestExists, checksPass, approved, false, false)
        }

        fun Boolean?.toIndicator() = if (this == true) "+" else if (this == null) "?" else "-"

        val statuses = getRemoteCommitStatuses(refSpec)
        return buildString {
            append(HEADER)
            for (status in statuses) {
                append("[")
                val statusBits = StatusBits(
                    commitIsPushed = status.remoteCommit != null,
                    pullRequestExists = status.pullRequest != null,
                    checksPass = if (status.pullRequest == null) false else status.checksPass,
                    approved = if (status.pullRequest == null) false else status.approved,
                )
                append(statusBits.toList().joinToString(separator = " ", transform = Boolean?::toIndicator))
                append("] ")
                appendLine(status.localCommit.shortMessage)
            }
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
    ): List<RefSpec> {
        logger.trace("getRevisionHistoryRefs")
        val branchNames = branches.map(RemoteBranch::name).toSet()
        val nextRevisionById = branchNames
            .mapNotNull { branchName ->
                getRemoteRefParts(branchName, config.remoteBranchPrefix)?.let { (id, revisionNumber) ->
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
    private fun Commit.toRemoteRefName(): String = buildRemoteRef(checkNotNull(id), config.remoteBranchPrefix)

    companion object {
        private val HEADER = """
            | ┌─ commit is pushed
            | │ ┌─ pull request exists
            | │ │ ┌─ github checks pass
            | │ │ │ ┌── pull request approved
            | │ │ │ │ ┌─── no merge conflicts
            | │ │ │ │ │ ┌──── stack check
            | │ │ │ │ │ │

        """.trimMargin()
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
