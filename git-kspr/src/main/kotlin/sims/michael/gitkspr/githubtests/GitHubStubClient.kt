package sims.michael.gitkspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitkspr.*
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.PullRequest

class GitHubStubClient(private val remoteBranchPrefix: String) : GitHubClient {

    private val logger = LoggerFactory.getLogger(GitHubStubClient::class.java)

    private val prNumberIterator = (0..Int.MAX_VALUE).iterator()

    private val prs = mutableListOf<PullRequest>()

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests")
        return prs
    }

    override suspend fun getPullRequestsById(commitFilter: List<String>?): List<PullRequest> {
        logger.trace("getPullRequestsById")
        return prs.filter { it.commitId in commitFilter.orEmpty() }
    }

    override suspend fun createPullRequest(pullRequest: PullRequest) {
        val regex = "^$remoteBranchPrefix(.*?)$".toRegex()
        val commitId = regex.matchEntire(pullRequest.headRefName)?.let { result -> result.groupValues[1] }
        // Assign a unique id and the next PR number... simulates what GitHub would do
        val pullRequestToAdd = pullRequest.copy(
            id = generateUuid(8),
            number = prNumberIterator.nextInt(),
            commitId = pullRequest.commitId ?: commitId,
        )
        logger.trace("createPullRequest {}", pullRequestToAdd)
        prs.add(pullRequestToAdd)
    }

    override suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        val i = prs.indexOfFirst { it.id == pullRequest.id }
        require(i > -1) { "PR with ID ${pullRequest.id} was not found" }
        prs[i] = pullRequest
    }
}
