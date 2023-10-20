package sims.michael.gitkspr

import ch.qos.logback.classic.Level
import kotlinx.serialization.Serializable
import sims.michael.gitkspr.serde.FileSerializer
import sims.michael.gitkspr.serde.LevelSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import sims.michael.gitkspr.generated.getpullrequests.RateLimit as GetPullRequestsRateLimit
import sims.michael.gitkspr.generated.getrepositoryid.RateLimit as GetRepositoryIdRateLimit

@Serializable
data class Config(
    @Serializable(with = FileSerializer::class)
    val workingDirectory: File,
    val remoteName: String,
    val gitHubInfo: GitHubInfo,
    val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    @Serializable(with = LevelSerializer::class)
    val logLevel: Level = Level.INFO,
    @Serializable(with = FileSerializer::class)
    val logsDirectory: File? = null,
)

@Serializable
data class GitHubInfo(val host: String, val owner: String, val name: String)

data class Commit(val hash: String, val shortMessage: String, val fullMessage: String, val id: String?) {
    override fun toString() = "Commit(id=$id, h=$hash, msg=$shortMessage)"
}

data class RefSpec(val localRef: String, val remoteRef: String) {
    override fun toString() = "$localRef:$remoteRef"
    fun forcePush() = if (!localRef.startsWith(FORCE_PUSH_PREFIX)) copy(localRef = "+$localRef") else this
}

data class RemoteBranch(val name: String, val commit: Commit) {
    fun toRefSpec(): RefSpec = RefSpec(commit.hash, name)
}

data class PullRequest(
    val id: String?,
    val commitId: String?,
    val number: Int?,
    val headRefName: String,
    val baseRefName: String,
    val title: String,
    val body: String,
    // TODO add state?
    // TODO add draft?
) {
    override fun toString(): String {
        val numberString = number?.let { "#$it" }.orEmpty()
        return "PR$numberString($headRefName -> $baseRefName, title=$title, id=$id)"
    }
}

data class GitHubRateLimitInfo(
    val cost: Int,
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val nodeCount: Int,
    val resetAt: LocalDateTime,
)

fun GetPullRequestsRateLimit.toCanonicalRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

fun GetRepositoryIdRateLimit.toCanonicalRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

/** Convert an ISO-8601 encoded UTC date string to a [LocalDateTime] */
private fun String.iso8601ToLocalDate(): LocalDateTime =
    Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

class GitKsprException(override val message: String) : RuntimeException(message)
