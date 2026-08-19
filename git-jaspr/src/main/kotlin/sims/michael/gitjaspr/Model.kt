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
import sims.michael.gitjaspr.serde.InstantSerializer
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
    val unresolvedReviewThreadCount: Int? = null,
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
    val unresolvedReviewThreadCount: Int? = null,
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

/** A commit in a navigation stack, identified by its jaspr Commit-Id. */
@Serializable data class StackEntry(val sha: String, val commitId: String)

/**
 * Ephemeral session state for stack navigation, persisted in `.git/jaspr/nav-state.json`. Exists
 * only while the user is navigated to a mid-stack commit (detached HEAD).
 *
 * The [stack] is ordered bottom-to-top (index 0 is closest to the target branch). [cursorIndex]
 * points at the commit HEAD is currently on. Entries at `0..cursorIndex` are materialized in git;
 * entries at `cursorIndex+1..lastIndex` form the replay queue that gets cherry-picked on `jaspr up`
 * / `jaspr top`.
 *
 * Schema policy: this class has no backwards-compatibility guarantee across jaspr versions. Adding
 * a required field or changing a field's type is allowed; existing nav-state.json files that fail
 * to deserialize are discarded with a warning (see [GitJaspr.readNavState]). The operator restarts
 * their nav session.
 */
@Serializable
data class NavState(
    /** The branch name HEAD was on before detaching, restored when the session ends. */
    val headBeforeDetach: String,
    /** The full stack, bottom-to-top. Each entry carries the latest known SHA for its Commit-Id. */
    val stack: List<StackEntry>,
    /** Index into [stack] of the commit HEAD is currently on. */
    val cursorIndex: Int,
    /**
     * Target ref the stack was anchored to at session start (e.g., the remote development branch).
     * Persisted so reconcile can run on operations like `up` / `top` / `cancel` that don't
     * otherwise need a targetRef from the caller.
     */
    val targetRef: String,
    /**
     * Resolved named-stack name at the time of detach; null when no named stack on the remote
     * contains the stack's commits (e.g., the stack hasn't been pushed yet). Cached here so nav UI
     * can render without re-walking remote refs on every move.
     */
    val stackName: String? = null,
)

/**
 * State for an in-progress split operation, persisted in `.git/jaspr/split-state.json`. Exists from
 * `jaspr split` until `jaspr unsplit` or `jaspr top` clears it.
 */
@Serializable
data class SplitState(
    /** SHA to soft-reset to when unsplitting (the original commit before the mixed reset). */
    val unsplitSha: String
)

/**
 * Persistent state for the periodic "is a newer jaspr available?" check, stored in
 * `~/.git-jaspr/update-check.json`. The check runs at most once per configured interval; this state
 * captures the most recent fetch results so the next invocation can skip the network call.
 */
@Serializable
data class UpdateCheckState(
    /** When the last fetch attempt was made (success or failure). */
    @Serializable(with = InstantSerializer::class) val lastCheckedAt: Instant,
    /** Tag of the latest stable release seen, or null if none/unknown. */
    val latestStable: String? = null,
    /** Tag of the latest prerelease seen, or null if none/unknown. */
    val latestPrerelease: String? = null,
    /**
     * Tag we last surfaced a notice for. Used to suppress nagging about the same version on
     * repeated invocations; the next notice fires only when a newer tag appears.
     */
    val lastNotifiedVersion: String? = null,
)

/**
 * A user-facing error carrying a clean, actionable [message]. The CLI's top-level handler renders
 * these as a plain error line; every *other* exception type is treated as an unexpected bug and
 * shown with the generic "you've likely encountered a bug" banner.
 *
 * So the rule for anything reachable from a command: throw this (or use [requireForUser] /
 * [requireNotNullForUser]) for a failure the user can cause through ordinary use, such as a bad
 * argument or the repo being in the wrong state. Reserve `require` / `check` / `checkNotNull`,
 * which throw `IllegalArgumentException` / `IllegalStateException`, for genuine internal invariants
 * where the bug banner is the right response. A plain `require` in a command or domain path that a
 * user can trip is a smell: it surfaces an ordinary mistake as an apparent crash.
 */
class GitJasprException(override val message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

/**
 * Like [require], but throws [GitJasprException] instead of [IllegalArgumentException] so the CLI
 * renders [lazyMessage] as a user-facing error rather treating it as an application bug
 */
inline fun requireForUser(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw GitJasprException(lazyMessage())
}

/**
 * Like [requireNotNull], but throws [GitJasprException] (see [requireForUser]) when [value] is null
 * and returns it otherwise.
 */
inline fun <T : Any> requireNotNullForUser(value: T?, lazyMessage: () -> String): T =
    value ?: throw GitJasprException(lazyMessage())

class PushFailedException(override val message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
