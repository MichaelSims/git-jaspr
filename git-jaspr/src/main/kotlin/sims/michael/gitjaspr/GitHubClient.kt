package sims.michael.gitjaspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef
import sims.michael.gitjaspr.generated.*
import sims.michael.gitjaspr.generated.enums.PullRequestReviewDecision
import sims.michael.gitjaspr.generated.enums.PullRequestReviewEvent
import sims.michael.gitjaspr.generated.enums.StatusState
import sims.michael.gitjaspr.generated.getpullrequests.RateLimit
import sims.michael.gitjaspr.generated.inputs.AddPullRequestReviewInput
import sims.michael.gitjaspr.generated.inputs.ClosePullRequestInput
import sims.michael.gitjaspr.generated.inputs.CreatePullRequestInput
import sims.michael.gitjaspr.generated.inputs.UpdatePullRequestInput
import java.util.concurrent.atomic.AtomicReference
import sims.michael.gitjaspr.generated.getpullrequests.PullRequest as GetPullRequestsPullRequest
import sims.michael.gitjaspr.generated.getpullrequestsbyheadref.PullRequest as GetPullRequestsByHeadRefPullRequest

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
    private val delegate: GraphQLClient<*>,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
    private val getPullRequestsPageSize: Int,
) : GitHubClient {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)

    override suspend fun getPullRequests(commitFilter: List<Commit>?): List<PullRequest> {
        logger.trace("getPullRequests {}", commitFilter ?: "")
        return getPullRequestsById(
            commitFilter?.map { commit -> requireNotNull(commit.id) { "Missing commit id, filter is $commitFilter" } },
        )
    }

    override suspend fun getPullRequestsById(commitFilter: List<String>?): List<PullRequest> {
        logger.trace("getPullRequestsById {}", commitFilter ?: "")

        // If commitFilter was supplied, build a set of commit IDs for filtering the returned PR list.
        // It'd be nice if the server could filter this for us but there doesn't seem to be a good way to do that.
        val ids = commitFilter?.requireNoNulls()?.toSet()

        // Recursively fetch all PR pages and concatenate them into a single list
        suspend fun getPullRequests(afterCursor: String? = null): Pair<List<GetPullRequestsPullRequest>, RateLimit?> {
            val variables = GetPullRequests.Variables(
                gitHubInfo.owner,
                gitHubInfo.name,
                getPullRequestsPageSize,
                afterCursor,
            )
            val resultData = delegate.execute(GetPullRequests(variables)).data
            val pullRequests = resultData?.repository?.pullRequests
            val pageInfo = pullRequests?.pageInfo
            val nodes = pullRequests?.nodes.orEmpty().filterNotNull() + if (pageInfo?.hasNextPage == true) {
                getPullRequests(pageInfo.endCursor).first
            } else {
                emptyList()
            }
            return nodes to resultData?.rateLimit
        }
        val (prs, rateLimit) = getPullRequests()

        logger.logRateLimitInfo(rateLimit?.toCanonicalRateLimitInfo())
        return prs
            .mapNotNull { pr ->
                val commitId = getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix)
                if (ids?.contains(commitId) != false) {
                    val state = pr.commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state
                    PullRequest(
                        pr.id,
                        commitId,
                        pr.number,
                        pr.headRefName,
                        pr.baseRefName,
                        pr.title,
                        pr.body,
                        when (state) {
                            StatusState.SUCCESS -> true
                            StatusState.FAILURE, StatusState.ERROR -> false
                            else -> null
                        },
                        when (pr.reviewDecision) {
                            PullRequestReviewDecision.APPROVED -> true
                            PullRequestReviewDecision.CHANGES_REQUESTED -> false
                            else -> null
                        },
                        pr.conclusionStates,
                        pr.permalink,
                        pr.isDraft,
                    )
                } else {
                    null
                }
            }
            .also { pullRequests -> logger.trace("getPullRequests {}: {}", pullRequests.size, pullRequests) }
    }

    // There's some duplicated logic here, although the creation of the pull request isn't technically
    // duped since CreatePullRequest.Result is different from GetPullRequests.Result even though they both
    // contain a PullRequest type
    @Suppress("DuplicatedCode")
    override suspend fun getPullRequestsByHeadRef(headRefName: String): List<PullRequest> {
        logger.trace("getPullRequestsByHeadRef {}", headRefName)
        val response = delegate.execute(
            GetPullRequestsByHeadRef(
                GetPullRequestsByHeadRef.Variables(
                    gitHubInfo.owner,
                    gitHubInfo.name,
                    headRefName,
                ),
            ),
        ).data
        logger.logRateLimitInfo(response?.rateLimit?.toCanonicalRateLimitInfo())
        return response
            ?.repository
            ?.pullRequests
            ?.nodes
            .orEmpty()
            .filterNotNull()
            .map { pr ->
                val commitId = getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix)
                val state = pr.commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state
                PullRequest(
                    pr.id,
                    commitId,
                    pr.number,
                    pr.headRefName,
                    pr.baseRefName,
                    pr.title,
                    pr.body,
                    when (state) {
                        StatusState.SUCCESS -> true
                        StatusState.FAILURE, StatusState.ERROR -> false
                        else -> null
                    },
                    when (pr.reviewDecision) {
                        PullRequestReviewDecision.APPROVED -> true
                        PullRequestReviewDecision.CHANGES_REQUESTED -> false
                        else -> null
                    },
                    pr.conclusionStates,
                    pr.permalink,
                    pr.isDraft,
                )
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
                            draft = pullRequest.isDraft,
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

        val commitId = getCommitIdFromRemoteRef(pr.headRefName, remoteBranchPrefix)
        val state = pr.commits.nodes?.singleOrNull()?.commit?.statusCheckRollup?.state
        return PullRequest(
            pr.id,
            commitId,
            pr.number,
            pr.headRefName,
            pr.baseRefName,
            pr.title,
            pr.body,
            when (state) {
                StatusState.SUCCESS -> true
                StatusState.FAILURE, StatusState.ERROR -> false
                else -> null
            },
            when (pr.reviewDecision) {
                PullRequestReviewDecision.APPROVED -> true
                PullRequestReviewDecision.CHANGES_REQUESTED -> false
                else -> null
            },
            permalink = pr.permalink,
            isDraft = pr.isDraft,
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

    override suspend fun closePullRequest(pullRequest: PullRequest) {
        logger.trace("closePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot close $pullRequest without an ID" }
        delegate
            .execute(
                ClosePullRequest(
                    ClosePullRequest.Variables(
                        ClosePullRequestInput(
                            pullRequestId = pullRequest.id,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error closing PR #{}", pullRequest.number) }
            }
    }

    override suspend fun approvePullRequest(pullRequest: PullRequest) {
        logger.trace("approvePullRequest {}", pullRequest)
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

    override fun autoClosePrs() {
        // No-op: only the stub client needs to do this
    }

    private suspend fun fetchRepositoryId(gitHubInfo: GitHubInfo): String {
        logger.trace("fetchRepositoryId {}", gitHubInfo)
        val response = delegate
            .execute(
                GetRepositoryId(
                    GetRepositoryId.Variables(
                        gitHubInfo.owner,
                        gitHubInfo.name,
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error fetching repository ID for {}", gitHubInfo) }
            }
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

        throw GitJasprException(list.first().message)
    }

    private fun Logger.logRateLimitInfo(gitHubRateLimitInfo: GitHubRateLimitInfo?) {
        if (gitHubRateLimitInfo == null) {
            warn("GitHub rate limit info is null; please report this to the maintainers")
        } else {
            debug("Rate limit info {}", gitHubRateLimitInfo)
        }
    }

    private val GetPullRequestsPullRequest.conclusionStates: List<String>
        get() = commits
            .nodes
            .orEmpty()
            .filterNotNull()
            .flatMap { prCommit ->
                prCommit
                    .commit
                    .checkSuites
                    ?.nodes
                    .orEmpty()
                    .filterNotNull()
                    .mapNotNull { checkSuite -> checkSuite.conclusion?.toString() }
            }

    private val GetPullRequestsByHeadRefPullRequest.conclusionStates: List<String>
        get() = commits
            .nodes
            .orEmpty()
            .filterNotNull()
            .flatMap { prCommit ->
                prCommit
                    .commit
                    .checkSuites
                    ?.nodes
                    .orEmpty()
                    .filterNotNull()
                    .mapNotNull { checkSuite -> checkSuite.conclusion?.toString() }
            }
}
