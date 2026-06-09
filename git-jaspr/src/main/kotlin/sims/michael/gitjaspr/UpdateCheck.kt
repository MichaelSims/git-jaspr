package sims.michael.gitjaspr

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.Closeable
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

const val UPDATE_CHECK_REPO_OWNER = "MichaelSims"
const val UPDATE_CHECK_REPO_NAME = "git-jaspr"
val DEFAULT_UPDATE_CHECK_INTERVAL: Duration = Duration.ofHours(24)

/** A single release as observed from the GitHub Releases API. */
data class ReleaseRef(val tag: String, val htmlUrl: String)

/**
 * Latest releases on each channel as of the most recent fetch. Either side may be null if the
 * upstream has no releases on that channel.
 */
data class LatestReleases(val stable: ReleaseRef?, val prerelease: ReleaseRef?)

/**
 * Pluggable source of release information. The production impl fetches from GitHub; tests inject a
 * fake. Returning null signals a transient failure (offline, rate-limited, parse error). Callers
 * must treat null as "skip the notice, retry next interval."
 */
fun interface ReleaseFetcher {
    fun fetchLatestReleases(): LatestReleases?
}

/**
 * A surfaceable update notice. The render layer formats this into a single muted line at the end of
 * the command output.
 */
data class UpdateNotice(val currentVersion: String, val latestVersion: String, val url: String)

/**
 * Coordinates "is a newer jaspr available?" checks. Reads/writes state at [stateFile], asks
 * [fetcher] when the interval has elapsed, and returns a notice if one is warranted.
 *
 * The class is intentionally not coroutine-aware: a fetch happens synchronously on the calling
 * thread with whatever timeout the fetcher imposes. State writes are best-effort — a failure to
 * persist is logged at debug and otherwise swallowed.
 *
 * Concurrent invocations across processes race on the state file; last write wins. The cost of a
 * lost write is one redundant network call, so no file locking is needed.
 */
class UpdateCheck(
    private val stateFile: File,
    private val fetcher: ReleaseFetcher,
    private val now: () -> Instant,
    private val currentVersion: String,
    private val interval: Duration = DEFAULT_UPDATE_CHECK_INTERVAL,
    private val json: Json,
    private val skip: () -> Boolean = { false },
) {
    private val logger = LoggerFactory.getLogger(UpdateCheck::class.java)

    /**
     * Returns a notice if one is warranted right now, or null otherwise. If the cached state is
     * stale (or absent) and [skip] returns false, this triggers a fetch. The decision tree:
     *
     * 1. If [skip] is true, return null without I/O.
     * 2. If [currentVersion] isn't a recognizable SemVer, return null (dev builds, `undefined`).
     * 3. If cached state is fresh, decide from cache without fetching.
     * 4. Otherwise, fetch via [fetcher] and persist. On failure, persist the new timestamp with the
     *    old data so we don't hammer the network on every command.
     * 5. From the (possibly refreshed) state, pick a candidate release. Notice only fires when the
     *    candidate is strictly newer than [currentVersion] AND wasn't already last-notified.
     */
    fun maybeNotice(): UpdateNotice? {
        if (skip()) return null
        val current = parseSemVer(currentVersion) ?: return null

        val cached = readState()
        val needsFetch = cached == null || Duration.between(cached.lastCheckedAt, now()) >= interval
        val freshState =
            if (needsFetch) {
                val fetched = fetcher.fetchLatestReleases()
                val merged =
                    UpdateCheckState(
                        lastCheckedAt = now(),
                        latestStable = fetched?.stable?.tag ?: cached?.latestStable,
                        latestPrerelease = fetched?.prerelease?.tag ?: cached?.latestPrerelease,
                        lastNotifiedVersion = cached?.lastNotifiedVersion,
                    )
                writeState(merged)
                merged
            } else {
                cached
            }

        val candidate = pickCandidate(current, freshState) ?: return null
        if (candidate.tag == freshState.lastNotifiedVersion) return null

        // Persist the notification so we don't re-fire on the next invocation.
        writeState(freshState.copy(lastNotifiedVersion = candidate.tag))

        return UpdateNotice(
            currentVersion = currentVersion,
            latestVersion = candidate.tag,
            url = candidate.htmlUrl,
        )
    }

    /**
     * Picks the release to surface, given the current version's channel:
     * - Prerelease users see a newer prerelease if available; otherwise a newer stable (graduation
     *   prompt). Newer-stable wins only when no newer prerelease exists, to keep one notice at a
     *   time.
     * - Stable users see a newer stable only. Prereleases are invisible.
     *
     * Returns null when no candidate is strictly newer than [current].
     */
    private fun pickCandidate(current: SemVer, state: UpdateCheckState): ReleaseRef? {
        val stableRef = state.latestStable?.let { tag -> parseSemVer(tag)?.let { it to tag } }
        val prereleaseRef =
            state.latestPrerelease?.let { tag -> parseSemVer(tag)?.let { it to tag } }

        fun candidateFor(tag: String): ReleaseRef = ReleaseRef(tag = tag, htmlUrl = releaseUrl(tag))

        return if (current.prerelease != null) {
            val newerPrerelease =
                prereleaseRef?.takeIf { (sv, _) -> sv > current }?.let { (_, tag) -> tag }
            val newerStable = stableRef?.takeIf { (sv, _) -> sv > current }?.let { (_, tag) -> tag }
            (newerPrerelease ?: newerStable)?.let(::candidateFor)
        } else {
            stableRef?.takeIf { (sv, _) -> sv > current }?.let { (_, tag) -> candidateFor(tag) }
        }
    }

    private fun releaseUrl(tag: String): String =
        "https://github.com/$UPDATE_CHECK_REPO_OWNER/$UPDATE_CHECK_REPO_NAME/releases/tag/$tag"

    private fun readState(): UpdateCheckState? {
        if (!stateFile.exists()) return null
        return try {
            json.decodeFromString<UpdateCheckState>(stateFile.readText())
        } catch (e: Exception) {
            logger.debug("Discarding malformed update-check state at {}", stateFile, e)
            null
        }
    }

    private fun writeState(state: UpdateCheckState) {
        try {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            logger.debug("Failed to write update-check state to {}", stateFile, e)
        }
    }
}

/**
 * Production [ReleaseFetcher] that hits the GitHub Releases API for the configured repo. Uses a
 * dedicated ktor [HttpClient] with no auth (the releases endpoint is public) and a short
 * engine-level request timeout, so an unreachable network short-circuits rather than blocking the
 * user's command for the default ktor 15-second window.
 *
 * Walks the first [perPage] releases (GitHub returns newest first), skips drafts, and picks the
 * first non-prerelease entry as the latest stable and the first prerelease entry as the latest
 * prerelease. Returns null on any non-200 status, parse error, timeout, or other transient failure
 * — the caller (see [UpdateCheck]) records the attempt timestamp regardless so we don't hammer the
 * network on every command.
 *
 * Holds the ktor client open between fetches. Call [close] (via the owning [AppWiring]) at process
 * shutdown.
 */
class KtorReleaseFetcher(
    private val owner: String = UPDATE_CHECK_REPO_OWNER,
    private val repo: String = UPDATE_CHECK_REPO_NAME,
    private val baseUrl: String = "https://api.github.com",
    private val timeoutMs: Long = 2_000,
    private val perPage: Int = 30,
) : ReleaseFetcher, Closeable {

    private val logger = LoggerFactory.getLogger(KtorReleaseFetcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val clientLazy: Lazy<HttpClient> = lazy {
        HttpClient(CIO) {
            engine { requestTimeout = timeoutMs }
            expectSuccess = false
        }
    }
    private val client: HttpClient
        get() = clientLazy.value

    override fun fetchLatestReleases(): LatestReleases? =
        try {
            runBlocking {
                val url = "$baseUrl/repos/$owner/$repo/releases?per_page=$perPage"
                val response =
                    client.get(url) {
                        headers {
                            append("Accept", "application/vnd.github+json")
                            append("X-GitHub-Api-Version", "2022-11-28")
                            append("User-Agent", "git-jaspr-update-check")
                        }
                    }
                if (response.status != HttpStatusCode.OK) {
                    logger.debug(
                        "Non-OK status from GitHub releases for {}/{}: {}",
                        owner,
                        repo,
                        response.status,
                    )
                    null
                } else {
                    parseReleases(response.bodyAsText())
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch releases from GitHub for {}/{}", owner, repo, e)
            null
        }

    private fun parseReleases(body: String): LatestReleases {
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(body).filterNot { it.draft }
        return LatestReleases(
            stable = releases.firstOrNull { !it.prerelease }?.toRef(),
            prerelease = releases.firstOrNull { it.prerelease }?.toRef(),
        )
    }

    override fun close() {
        if (clientLazy.isInitialized()) client.close()
    }
}

/** Subset of the GitHub Releases API response we care about. */
@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String,
)

private fun GitHubReleaseDto.toRef(): ReleaseRef = ReleaseRef(tag = tagName, htmlUrl = htmlUrl)

/**
 * Minimal SemVer 2.0 representation, parsing the subset jaspr produces via `git describe --tags`
 * (e.g. `v2.1.0`, `v2.1.0-beta.3`). Build metadata (the `+...` suffix) is ignored on input.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Null for stable releases; otherwise the dot-separated prerelease identifiers. */
    val prerelease: String?,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val byCore = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
        if (byCore != 0) return byCore
        // SemVer §11.3: stable (null prerelease) outranks any prerelease.
        return when {
            prerelease == null && other.prerelease == null -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> comparePrereleases(prerelease, other.prerelease)
        }
    }
}

fun parseSemVer(tag: String): SemVer? {
    val trimmed = tag.removePrefix("v").substringBefore('+')
    val (coreRaw, prerelease) =
        if ('-' in trimmed) trimmed.substringBefore('-') to trimmed.substringAfter('-')
        else trimmed to null
    val parts = coreRaw.split('.')
    if (parts.size != 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    return SemVer(major, minor, patch, prerelease)
}

/**
 * SemVer §11.4 prerelease precedence: dot-separated identifiers compared left to right; numeric
 * identifiers compared numerically, alphanumeric lexically; numeric always ranks lower than
 * alphanumeric; shorter prefix loses if otherwise equal.
 */
private fun comparePrereleases(a: String, b: String): Int {
    val aParts = a.split('.')
    val bParts = b.split('.')
    val common = minOf(aParts.size, bParts.size)
    for (i in 0 until common) {
        val ai = aParts[i]
        val bi = bParts[i]
        val aNum = ai.toIntOrNull()
        val bNum = bi.toIntOrNull()
        val cmp =
            when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1
                bNum != null -> 1
                else -> ai.compareTo(bi)
            }
        if (cmp != 0) return cmp
    }
    return aParts.size.compareTo(bParts.size)
}
