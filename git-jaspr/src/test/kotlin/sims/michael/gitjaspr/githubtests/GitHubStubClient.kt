package sims.michael.gitjaspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.*
import sims.michael.gitjaspr.Commit
import sims.michael.gitjaspr.PullRequest
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef

class GitHubStubClient(private val remoteBranchPrefix: String, private val localGit: JGitClient) : GitHubClient {

    private val logger = LoggerFactory.getLogger(GitHubStubClient::class.java)

    private val prNumberIterator = (0..Int.MAX_VALUE).iterator()

    private data class PullRequestAndState(val pullRequest: PullRequest, val open: Boolean = true)

    private val prs = mutableListOf<PullRequestAndState>()

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests")
        return synchronized(prs) {
            autoClosePrs()
            if (commitFilter == null) {
                prs.openPullRequests()
            } else {
                val ids = commitFilter.map { commit -> commit.id }
                prs.openPullRequests().filter { pr -> pr.commitId in ids }
            }
        }
    }

    private fun List<PullRequestAndState>.openPullRequests(): List<PullRequest> =
        filter(PullRequestAndState::open).map(PullRequestAndState::pullRequest)

    override suspend fun getPullRequestsById(commitFilter: List<String>?): List<PullRequest> {
        logger.trace("getPullRequestsById")
        return synchronized(prs) {
            autoClosePrs()
            // TODO this looks suspect, if commitFilter is null we return nothing?
            prs.openPullRequests().filter { it.commitId in commitFilter.orEmpty() }
        }
    }

    override suspend fun getPullRequestsByHeadRef(headRefName: String): List<PullRequest> {
        return synchronized(prs) { prs.map(PullRequestAndState::pullRequest).filter { it.headRefName == headRefName } }
    }

    override suspend fun createPullRequest(pullRequest: PullRequest): PullRequest {
        val commitId = getCommitIdFromRemoteRef(pullRequest.headRefName, remoteBranchPrefix)
        return pullRequest
            .copy(
                // Assign a unique id and the next PR number... simulates what GitHub would do
                id = generateUuid(8),
                number = prNumberIterator.nextInt(),
                commitId = pullRequest.commitId ?: commitId,
            )
            .also { pullRequestToAdd ->
                logger.trace("createPullRequest {}", pullRequestToAdd)
                synchronized(prs) { prs.add(PullRequestAndState(pullRequestToAdd, open = true)) }
            }
    }

    override suspend fun closePullRequest(pullRequest: PullRequest) {
        synchronized(prs) {
            val i = prs.openPullRequests().indexOfFirst { it == pullRequest }
            require(i > -1) { "PR was not found" }
            prs[i] = prs[i].copy(open = false)
        }
    }

    override suspend fun approvePullRequest(pullRequest: PullRequest) {
        // No op
    }

    override suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        synchronized(prs) {
            val i = prs.openPullRequests().indexOfFirst { it.id == pullRequest.id }
            require(i > -1) { "PR with ID ${pullRequest.id} was not found" }
            prs[i] = prs[i].copy(pullRequest = pullRequest)
        }
    }

    // Mimic GitHub's behavior since our program logic depends on it. Should be called from any method that returns
    // PRs so that the PR state is always viewed consistently with the git repo state
    private fun autoClosePrs() {
        synchronized(prs) {
            for (i in 0 until prs.size) {
                val pr = prs[i].pullRequest
                val range = localGit.logRange(
                    "$DEFAULT_REMOTE_NAME/${DEFAULT_TARGET_REF}",
                    "$DEFAULT_REMOTE_NAME/${pr.headRefName}",
                )
                // Close it if it's already been merged
                if (range.isEmpty()) prs[i] = prs[i].copy(open = false)
            }
        }
    }
}
