package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.generated.*
import sims.michael.gitkspr.generated.enums.PullRequestReviewDecision
import sims.michael.gitkspr.generated.enums.PullRequestReviewEvent
import sims.michael.gitkspr.generated.enums.StatusState
import sims.michael.gitkspr.generated.inputs.AddPullRequestReviewInput
import sims.michael.gitkspr.generated.inputs.CreatePullRequestInput
import sims.michael.gitkspr.generated.inputs.UpdatePullRequestInput
import java.util.concurrent.atomic.AtomicReference

interface GitHubClient {
    suspend fun getPullRequests(commitFilter: List<Commit>? = null): List<PullRequest>
    suspend fun getPullRequestsById(commitFilter: List<String>? = null): List<PullRequest>
    suspend fun createPullRequest(pullRequest: PullRequest): PullRequest
    suspend fun updatePullRequest(pullRequest: PullRequest)
    suspend fun approvePullRequest(pullRequest: PullRequest)
}

class GitHubClientImpl(
    private val delegate: GraphQLClient<*>,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
) : GitHubClient {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests")
        return getPullRequestsById(
            commitFilter?.map { commit -> requireNotNull(commit.id) { "Missing commit id, filter is $commitFilter" } },
        )
    }

    override suspend fun getPullRequestsById(commitFilter: List<String>?): List<PullRequest> {
        logger.trace("getPullRequests")

        // If commitFilter was supplied, build a set of commit IDs for filtering the returned PR list.
        // It'd be nice if the server could filter this for us but there doesn't seem to be a good way to do that.
        val ids = commitFilter?.requireNoNulls()?.toSet()

        val regex = "^$remoteBranchPrefix(.*?)$".toRegex()
        val response = delegate
            .execute(GetPullRequests(GetPullRequests.Variables(gitHubInfo.owner, gitHubInfo.name)))
            .data
        logger.logRateLimitInfo(response?.rateLimit?.toCanonicalRateLimitInfo())
        return response
            ?.repository
            ?.pullRequests
            ?.nodes
            .orEmpty()
            .filterNotNull()
            .mapNotNull { pr ->
                val commitId = regex.matchEntire(pr.headRefName)?.let { result -> result.groupValues[1] }
                if (ids?.contains(commitId) != false) {
                    PullRequest(
                        pr.id,
                        commitId,
                        pr.number,
                        pr.headRefName,
                        pr.baseRefName,
                        pr.title,
                        pr.body,
                        pr.commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state == StatusState.SUCCESS,
                        pr.reviewDecision == PullRequestReviewDecision.APPROVED,
                    )
                } else {
                    null
                }
            }
            .also { pullRequests -> logger.trace("getPullRequests {}: {}", pullRequests.size, pullRequests) }
    }

    override suspend fun createPullRequest(pullRequest: PullRequest): PullRequest {
        logger.trace("createPullRequest {}", pullRequest)
        check(pullRequest.id == null) { "Cannot create $pullRequest which already exists" }
        val pr = delegate
            .execute(
                CreatePullRequest(
                    CreatePullRequest.Variables(
                        CreatePullRequestInput(
                            baseRefName = pullRequest.baseRefName,
                            headRefName = pullRequest.headRefName,
                            repositoryId = repositoryId(),
                            title = pullRequest.title,
                            body = pullRequest.body,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error creating {}", pullRequest) }
            }
            .data
            ?.createPullRequest
            ?.pullRequest

        checkNotNull(pr) { "createPullRequest returned a null result" }

        // TODO
        //  There's some duplicated logic here, although the creation of the pull request isn't technically
        //  duped since CreatePullRequest.Result is different from GetPullRequests.Result even though they both
        //  contain a PullRequest type
        val regex = "^$remoteBranchPrefix(.*?)$".toRegex()
        val commitId = regex.matchEntire(pr.headRefName)?.let { result -> result.groupValues[1] }
        return PullRequest(
            pr.id,
            commitId,
            pr.number,
            pr.headRefName,
            pr.baseRefName,
            pr.title,
            pr.body,
            pr.commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state == StatusState.SUCCESS,
            pr.reviewDecision == PullRequestReviewDecision.APPROVED,
        )
    }

    override suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot update $pullRequest without an ID" }
        delegate
            .execute(
                UpdatePullRequest(
                    UpdatePullRequest.Variables(
                        UpdatePullRequestInput(
                            pullRequestId = pullRequest.id,
                            baseRefName = pullRequest.baseRefName,
                            title = pullRequest.title,
                            body = pullRequest.body,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error updating PR #{}", pullRequest.number) }
            }
    }

    override suspend fun approvePullRequest(pullRequest: PullRequest) {
        logger.trace("approvePullRequest {}", "")
        checkNotNull(pullRequest.id) { "Cannot approve $pullRequest without an ID" }
        delegate
            .execute(
                AddPullRequestReview(
                    AddPullRequestReview.Variables(
                        AddPullRequestReviewInput(
                            pullRequestId = pullRequest.id,
                            event = PullRequestReviewEvent.APPROVE,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error approving PR #{}", pullRequest.number) }
            }
    }

    private suspend fun fetchRepositoryId(gitHubInfo: GitHubInfo): String {
        logger.trace("fetchRepositoryId {}", gitHubInfo)
        val response = delegate.execute(
            GetRepositoryId(
                GetRepositoryId.Variables(
                    gitHubInfo.owner,
                    gitHubInfo.name,
                ),
            ),
        )
        val repositoryId = response.data?.repository?.id
        logger.logRateLimitInfo(response.data?.rateLimit?.toCanonicalRateLimitInfo())
        return checkNotNull(repositoryId) { "Failed to fetch repository ID, response is null" }
    }

    private val repositoryId = AtomicReference<String?>(null)
    private suspend fun repositoryId() = repositoryId.get() ?: fetchRepositoryId(gitHubInfo).also(repositoryId::set)

    private fun GraphQLClientResponse<*>.checkNoErrors(onError: () -> Unit = {}) {
        val list = errors?.takeUnless { list -> list.isEmpty() } ?: return

        onError()
        for (graphQLClientError in list) {
            logger.error(graphQLClientError.toString())
        }

        throw GitKsprException(list.first().message)
    }

    private fun Logger.logRateLimitInfo(gitHubRateLimitInfo: GitHubRateLimitInfo?) {
        if (gitHubRateLimitInfo == null) {
            warn("GitHub rate limit info is null; please report this to the maintainers")
        } else {
            debug("Rate limit info {}", gitHubRateLimitInfo)
        }
    }
}
