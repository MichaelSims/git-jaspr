package sims.michael.gitjaspr

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef
import sims.michael.gitjaspr.generated.AddPullRequestReviewMutation
import sims.michael.gitjaspr.generated.ClosePullRequestMutation
import sims.michael.gitjaspr.generated.CreatePullRequestMutation
import sims.michael.gitjaspr.generated.GetPullRequestsByHeadRefQuery
import sims.michael.gitjaspr.generated.GetPullRequestsQuery
import sims.michael.gitjaspr.generated.GetRepositoryIdQuery
import sims.michael.gitjaspr.generated.UpdatePullRequestMutation
import sims.michael.gitjaspr.generated.fragment.PullRequest as RawPullRequest
import sims.michael.gitjaspr.generated.fragment.RateLimitFields
import sims.michael.gitjaspr.generated.type.AddPullRequestReviewInput
import sims.michael.gitjaspr.generated.type.ClosePullRequestInput
import sims.michael.gitjaspr.generated.type.CreatePullRequestInput
import sims.michael.gitjaspr.generated.type.PullRequestReviewEvent
import sims.michael.gitjaspr.generated.type.UpdatePullRequestInput

interface GitHubClient {
    suspend fun getPullRequests(commitFilter: List<Commit>? = null): List<PullRequest>

    suspend fun getPullRequestsById(commitFilter: List<String>? = null): List<PullRequest>

    suspend fun getPullRequestsByHeadRef(headRefName: String): List<PullRequest>

    suspend fun createPullRequest(pullRequest: PullRequest): PullRequest

    suspend fun updatePullRequest(pullRequest: PullRequest)

    suspend fun closePullRequest(pullRequest: PullRequest)

    suspend fun approvePullRequest(pullRequest: PullRequest)

    fun autoClosePrs()

    companion object {
        const val GET_PULL_REQUESTS_DEFAULT_PAGE_SIZE = 100
    }
}

class GitHubClientImpl(
    private val apolloClient: ApolloClient,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
    private val getPullRequestsPageSize: Int,
) : GitHubClient {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests {}", commitFilter ?: "")
        return getPullRequestsById(
            commitFilter?.map { commit ->
                requireNotNull(commit.id) { "Missing commit id, filter is $commitFilter" }
            }
        )
    }

    override suspend fun getPullRequestsById(commitFilter: List<String>?): List<PullRequest> {
        logger.trace("getPullRequestsById {}", commitFilter ?: "")

        // If commitFilter was supplied, build a set of commit IDs for filtering the returned PR
        // list. It'd be nice if the server could filter this for us, but there doesn't seem to be a
        // good way to do that.
        val ids = commitFilter?.requireNoNulls()?.toSet()

        // Recursively fetch all PR pages and concatenate them into a single list
        suspend fun getPullRequests(
            afterCursor: String? = null
        ): Pair<List<RawPullRequest>, RateLimitFields?> {
            val query =
                GetPullRequestsQuery(
                    gitHubInfo.owner,
                    gitHubInfo.name,
                    Optional.present(getPullRequestsPageSize),
                    Optional.presentIfNotNull(afterCursor),
                )
            val resultData = apolloClient.query(query).execute().also(::checkNoErrors).data
            val pullRequests = resultData?.repository?.pullRequests
            val pageInfo = pullRequests?.pageInfo
            val nodes =
                pullRequests?.nodes.orEmpty().filterNotNull() +
                    if (pageInfo?.hasNextPage == true) {
                        getPullRequests(pageInfo.endCursor).first
                    } else {
                        emptyList()
                    }
            return nodes to resultData?.rateLimit
        }
        val (prs, rateLimit) = getPullRequests()

        logger.logRateLimitInfo(rateLimit?.toRateLimitInfo())
        return prs.mapNotNull { pr ->
                val commitId = getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix)
                if (ids?.contains(commitId) != false) {
                    pr.toPullRequest(commitId)
                } else {
                    null
                }
            }
            .also { pullRequests ->
                logger.trace("getPullRequestsById {}: {}", pullRequests.size, pullRequests)
            }
    }

    @Suppress("DuplicatedCode")
    override suspend fun getPullRequestsByHeadRef(headRefName: String): List<PullRequest> {
        logger.trace("getPullRequestsByHeadRef {}", headRefName)
        val query = GetPullRequestsByHeadRefQuery(gitHubInfo.owner, gitHubInfo.name, headRefName)
        val response = apolloClient.query(query).execute().also(::checkNoErrors).data
        logger.logRateLimitInfo(response?.rateLimit?.toRateLimitInfo())
        val prs = response?.repository?.pullRequests?.nodes.orEmpty().filterNotNull()
        return prs.map { pr ->
                pr.toPullRequest(getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix))
            }
            .also { pullRequests ->
                logger.trace("getPullRequests {}: {}", pullRequests.size, pullRequests)
            }
    }

    override suspend fun createPullRequest(pullRequest: PullRequest): PullRequest {
        logger.trace("createPullRequest {}", pullRequest)
        check(pullRequest.id == null) { "Cannot create $pullRequest which already exists" }
        val mutation =
            CreatePullRequestMutation(
                CreatePullRequestInput(
                    baseRefName = pullRequest.baseRefName,
                    headRefName = pullRequest.headRefName,
                    repositoryId = repositoryId(),
                    title = pullRequest.title,
                    body = Optional.present(pullRequest.body),
                    draft = Optional.present(pullRequest.isDraft),
                )
            )
        val pr =
            apolloClient
                .mutation(mutation)
                .execute()
                .also { response ->
                    checkNoErrors(response) { logger.error("Error creating {}", pullRequest) }
                }
                .data
                ?.createPullRequest
                ?.pullRequest

        checkNotNull(pr) { "createPullRequest returned a null result" }

        return pr.toPullRequest(getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix))
    }

    override suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot update $pullRequest without an ID" }
        val mutation =
            UpdatePullRequestMutation(
                UpdatePullRequestInput(
                    pullRequestId = pullRequest.id,
                    baseRefName = Optional.present(pullRequest.baseRefName),
                    title = Optional.present(pullRequest.title),
                    body = Optional.present(pullRequest.body),
                )
            )
        apolloClient.mutation(mutation).execute().also { response ->
            checkNoErrors(response) { logger.error("Error updating PR #{}", pullRequest.number) }
        }
    }

    override suspend fun closePullRequest(pullRequest: PullRequest) {
        logger.trace("closePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot close $pullRequest without an ID" }
        val mutation =
            ClosePullRequestMutation(ClosePullRequestInput(pullRequestId = pullRequest.id))
        apolloClient.mutation(mutation).execute().also { response ->
            checkNoErrors(response) { logger.error("Error closing PR #{}", pullRequest.number) }
        }
    }

    override suspend fun approvePullRequest(pullRequest: PullRequest) {
        logger.trace("approvePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot approve $pullRequest without an ID" }
        val mutation =
            AddPullRequestReviewMutation(
                AddPullRequestReviewInput(
                    pullRequestId = pullRequest.id,
                    event = Optional.present(PullRequestReviewEvent.APPROVE),
                )
            )
        apolloClient.mutation(mutation).execute().also { response ->
            checkNoErrors(response) { logger.error("Error approving PR #{}", pullRequest.number) }
        }
    }

    override fun autoClosePrs() {
        // No-op: only the stub client needs to do this
    }

    private suspend fun fetchRepositoryId(gitHubInfo: GitHubInfo): String {
        logger.trace("fetchRepositoryId {}", gitHubInfo)
        val query = GetRepositoryIdQuery(owner = gitHubInfo.owner, name = gitHubInfo.name)
        val response =
            apolloClient.query(query).execute().also { response ->
                checkNoErrors(response) {
                    logger.error("Error fetching repository ID for {}", gitHubInfo)
                }
            }
        val repositoryId = response.data?.repository?.id
        logger.logRateLimitInfo(response.data?.rateLimit?.toRateLimitInfo())
        return checkNotNull(repositoryId) { "Failed to fetch repository ID, response is null" }
    }

    private val repositoryId = AtomicReference<String?>(null)

    private suspend fun repositoryId() =
        repositoryId.get() ?: fetchRepositoryId(gitHubInfo).also(repositoryId::set)

    private fun <D : Operation.Data> checkNoErrors(
        response: ApolloResponse<D>,
        onError: () -> Unit = {},
    ) {
        val list = response.errors?.takeUnless { it.isEmpty() } ?: return

        onError()
        for (error in list) {
            logger.error(error.toString())
        }

        throw GitJasprException(list.first().message)
    }

    private fun Logger.logRateLimitInfo(gitHubRateLimitInfo: GitHubRateLimitInfo?) {
        if (gitHubRateLimitInfo == null) {
            warn("GitHub rate limit info is null; please report this to the maintainers")
        } else {
            debug("Rate limit info {}", gitHubRateLimitInfo)
        }
    }

    /** Convert a RawPullRequest to our domain model PullRequest. */
    private fun RawPullRequest.toPullRequest(commitId: String?): PullRequest {
        val state = commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state
        return PullRequest(
            id = id,
            commitId = commitId,
            number = number,
            headRefName = headRefName,
            baseRefName = baseRefName,
            title = title,
            body = body,
            checksPass =
                when (state?.rawValue) {
                    "SUCCESS" -> true
                    "FAILURE",
                    "ERROR" -> false
                    else -> null
                },
            approved =
                when (reviewDecision?.rawValue) {
                    "APPROVED" -> true
                    "CHANGES_REQUESTED" -> false
                    else -> null
                },
            permalink = permalink,
            isDraft = isDraft,
        )
    }
}
