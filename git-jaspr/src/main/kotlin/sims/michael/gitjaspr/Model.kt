package sims.michael.gitjaspr

import ch.qos.logback.classic.Level
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable
import org.eclipse.jgit.lib.PersonIdent
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.generated.fragment.RateLimitFields
import sims.michael.gitjaspr.serde.FileSerializer
import sims.michael.gitjaspr.serde.LevelSerializer

@Serializable
data class Config(
    @Serializable(with = FileSerializer::class) val workingDirectory: File,
    val remoteName: String,
    val gitHubInfo: GitHubInfo,
    val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    val remoteNamedStackBranchPrefix: String = DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
    @Serializable(with = LevelSerializer::class) val logLevel: Level = Level.INFO,
    @Serializable(with = FileSerializer::class) val logsDirectory: File? = null,
    val dontPushRegex: String = "^(dont[ -]?push)\\b.*$",
    val showTips: Boolean = true,
)

@Serializable data class GitHubInfo(val host: String, val owner: String, val name: String)

data class Commit(
    val hash: String,
    val shortMessage: String,
    val fullMessage: String,
    val id: String?,
    val author: Ident,
    val committer: Ident,
    // Format with date.format(DateTimeFormatter.ofPattern("E MMM d, YYYY, h:mm:ss a z"))
    val commitDate: ZonedDateTime,
    val authorDate: ZonedDateTime,
)

data class Ident(val name: String, val email: String) {
    override fun toString() = "$name <$email>"
}

fun Ident.toPersonIdent() = PersonIdent(name, email)

data class RefSpec(val localRef: String, val remoteRef: String) {
    override fun toString() = "$localRef:$remoteRef"

    fun forcePush() =
        if (!localRef.startsWith(FORCE_PUSH_PREFIX)) copy(localRef = "+$localRef") else this
}

data class RemoteBranch(val name: String, val commit: Commit) {
    fun toRefSpec(): RefSpec = RefSpec(commit.hash, name)
}

data class RemoteCommitStatus(
    val localCommit: Commit,
    val remoteCommit: Commit?,
    val pullRequest: PullRequest?,
    val checksPass: Boolean?,
    val isDraft: Boolean?,
    val approved: Boolean?,
) {
    val isMergeable =
        localCommit.hash == remoteCommit?.hash &&
            checksPass == true &&
            isDraft != true &&
            approved == true
}

data class PullRequest(
    val id: String?,
    val commitId: String?,
    val number: Int?,
    val headRefName: String,
    val baseRefName: String,
    val title: String,
    val body: String,
    val checksPass: Boolean? = null,
    val approved: Boolean? = null,
    val permalink: String? = null,
    val isDraft: Boolean = false,
) {
    override fun toString(): String {
        val numberString = number?.let { "#$it" }.orEmpty()
        return "PR$numberString($headToBaseString, title=$title, id=$id)"
    }

    val headToBaseString: String
        get() = "$headRefName -> $baseRefName"
}

data class GitHubRateLimitInfo(
    val cost: Int,
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val nodeCount: Int,
    val resetAt: LocalDateTime,
)

fun RateLimitFields.toRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

/** Convert an ISO-8601 encoded UTC date string to a [LocalDateTime] */
private fun String.iso8601ToLocalDate(): LocalDateTime =
    Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

class GitJasprException(override val message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

class PushFailedException(override val message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
