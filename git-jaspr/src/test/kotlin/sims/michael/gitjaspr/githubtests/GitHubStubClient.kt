package sims.michael.gitjaspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.*
import sims.michael.gitjaspr.Commit
import sims.michael.gitjaspr.PullRequest
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef

class GitHubStubClient(
    private val remoteBranchPrefix: String,
    private val remoteName: String,
    private val localGit: GitClient,
) : GitHubClient {

    private val logger = LoggerFactory.getLogger(GitHubStubClient::class.java)

    private val prNumberIterator = (0..Int.MAX_VALUE).iterator()

    private data class PullRequestAndState(val pullRequest: PullRequest, val open: Boolean = true)

    private val prs = mutableListOf<PullRequestAndState>()

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests {}", commitFilter ?: "")
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
        logger.trace("getPullRequestsById {}", commitFilter ?: "")
        return synchronized(prs) {
            autoClosePrs()
            prs.openPullRequests().filter { pr ->
                if (commitFilter != null) pr.commitId in commitFilter else true
            }
        }
    }

    override suspend fun getPullRequestsByHeadRef(headRefName: String): List<PullRequest> {
        logger.trace("getPullRequestsByHeadRef {}", headRefName)
        return synchronized(prs) {
            prs.map(PullRequestAndState::pullRequest).filter { it.headRefName == headRefName }
        }
    }

    override suspend fun createPullRequest(pullRequest: PullRequest): PullRequest {
        logger.trace("createPullRequest {}", pullRequest)
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
        logger.trace("closePullRequest {}", pullRequest)
        synchronized(prs) {
            val i =
                prs.map(PullRequestAndState::pullRequest).indexOfFirst { it.id == pullRequest.id }
            require(i > -1) { "PR $pullRequest was not found" }
            prs[i] = prs[i].copy(open = false)
        }
    }

    override suspend fun approvePullRequest(pullRequest: PullRequest) {
        logger.trace("approvePullRequest {}", pullRequest)
        // No op
    }

    override suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        synchronized(prs) {
            val i =
                prs.map(PullRequestAndState::pullRequest).indexOfFirst { it.id == pullRequest.id }
            require(i > -1) { "PR with ID ${pullRequest.id} was not found" }
            prs[i] = prs[i].copy(pullRequest = pullRequest)
        }
    }

    // Mimic GitHub's behavior since our program logic depends on it. Should be called from any
    // method that returns PRs so that the PR state is always viewed consistently with the git repo
    // state
    override fun autoClosePrs() {
        logger.trace("autoClosePrs")
        val remoteBranchesById = localGit.getRemoteBranchesById(remoteName)
        synchronized(prs) {
            for (i in 0 until prs.size) {
                val pr = prs[i].pullRequest
                val commitId = pr.commitId ?: continue // Commit missing ID
                val remoteBranch =
                    remoteBranchesById[commitId] ?: continue // No remote branch for commit
                val range =
                    localGit.logRange("$remoteName/${DEFAULT_TARGET_REF}", remoteBranch.commit.hash)
                // Close it if it's already been merged
                if (range.isEmpty()) {
                    logger.trace("autoClosePrs closing {}", prs[i])
                    prs[i] = prs[i].copy(open = false)
                }
            }
        }
    }
}
