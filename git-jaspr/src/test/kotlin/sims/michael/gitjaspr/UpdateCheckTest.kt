package sims.michael.gitjaspr

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateCheckTest {

    private lateinit var tempDir: File
    private lateinit var stateFile: File
    private val json = Json { prettyPrint = true }
    private val now = Instant.parse("2026-06-09T12:00:00Z")
    private val interval = Duration.ofHours(24)

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("update-check-test").toFile()
        stateFile = tempDir.resolve("update-check.json")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ---------- SemVer ----------

    @Test
    fun `parseSemVer handles stable, prerelease, and v-prefix variants`() {
        assertEquals(SemVer(2, 1, 0, null), parseSemVer("v2.1.0"))
        assertEquals(SemVer(2, 1, 0, null), parseSemVer("2.1.0"))
        assertEquals(SemVer(2, 1, 0, "beta.3"), parseSemVer("v2.1.0-beta.3"))
        assertEquals(SemVer(2, 1, 0, "rc.1"), parseSemVer("v2.1.0-rc.1"))
        // Build metadata is ignored.
        assertEquals(SemVer(2, 1, 0, null), parseSemVer("v2.1.0+build.42"))
    }

    @Test
    fun `parseSemVer returns null for non-SemVer inputs`() {
        assertNull(parseSemVer("undefined"))
        assertNull(parseSemVer("v2.1"))
        assertNull(parseSemVer("not-a-version"))
        assertNull(parseSemVer(""))
    }

    @Test
    fun `SemVer ordering respects core, prerelease vs stable, and prerelease subfields`() {
        // Core comparison.
        assertTrue(parseSemVer("v2.1.0")!! > parseSemVer("v2.0.99")!!)
        assertTrue(parseSemVer("v2.1.1")!! > parseSemVer("v2.1.0")!!)
        // Stable outranks prerelease at the same core.
        assertTrue(parseSemVer("v2.1.0")!! > parseSemVer("v2.1.0-beta.3")!!)
        assertTrue(parseSemVer("v2.1.0")!! > parseSemVer("v2.1.0-rc.1")!!)
        // Prerelease ordering: numeric identifiers compared numerically.
        assertTrue(parseSemVer("v2.1.0-beta.3")!! > parseSemVer("v2.1.0-beta.2")!!)
        assertTrue(parseSemVer("v2.1.0-beta.10")!! > parseSemVer("v2.1.0-beta.9")!!)
        // Prerelease ordering: alphabetic precedence (alpha < beta < rc).
        assertTrue(parseSemVer("v2.1.0-beta.1")!! > parseSemVer("v2.1.0-alpha.1")!!)
        assertTrue(parseSemVer("v2.1.0-rc.1")!! > parseSemVer("v2.1.0-beta.99")!!)
        // Numeric ranks lower than alphanumeric.
        assertTrue(parseSemVer("v2.1.0-alpha")!! > parseSemVer("v2.1.0-1")!!)
    }

    // ---------- skip() and currentVersion gating ----------

    @Test
    fun `maybeNotice returns null when skip is true`() {
        val check = makeCheck(currentVersion = "v2.1.0", skip = { true })
        assertNull(check.maybeNotice())
    }

    @Test
    fun `maybeNotice returns null for an unparseable current version`() {
        val check = makeCheck(currentVersion = "undefined")
        assertNull(check.maybeNotice())
    }

    // ---------- state interactions ----------

    @Test
    fun `first invocation triggers a fetch and persists state`() {
        val fetcher = FakeFetcher(stable = "v2.1.0", prerelease = "v2.2.0-beta.1")
        val check = makeCheck(currentVersion = "v2.1.0", fetcher = fetcher)
        assertNull(check.maybeNotice()) // current == latestStable
        assertEquals(1, fetcher.callCount)
        val state = readPersistedState()
        assertEquals(now, state.lastCheckedAt)
        assertEquals("v2.1.0", state.latestStable)
        assertEquals("v2.2.0-beta.1", state.latestPrerelease)
    }

    @Test
    fun `fresh state within interval reuses cache without fetching`() {
        // Prime cache.
        writePersistedState(
            UpdateCheckState(
                lastCheckedAt = now,
                latestStable = "v2.0.0",
                latestPrerelease = null,
                lastNotifiedVersion = null,
            )
        )
        val fetcher = FakeFetcher(stable = "v3.0.0")
        val laterButFresh = now.plus(Duration.ofHours(23))
        val check =
            makeCheck(
                currentVersion = "v2.0.0",
                fetcher = fetcher,
                nowOverride = laterButFresh,
            )
        assertNull(check.maybeNotice())
        assertEquals(0, fetcher.callCount, "must NOT fetch when cache is fresh")
    }

    @Test
    fun `stale state triggers a refetch`() {
        writePersistedState(
            UpdateCheckState(
                lastCheckedAt = now,
                latestStable = "v2.0.0",
                latestPrerelease = null,
                lastNotifiedVersion = null,
            )
        )
        val fetcher = FakeFetcher(stable = "v2.1.0")
        val laterAndStale = now.plus(Duration.ofHours(25))
        val check =
            makeCheck(
                currentVersion = "v2.0.0",
                fetcher = fetcher,
                nowOverride = laterAndStale,
            )
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.1.0", notice.latestVersion)
        assertEquals(1, fetcher.callCount)
    }

    @Test
    fun `failed fetch persists the timestamp to avoid retrying every command`() {
        val fetcher = FakeFetcher(stable = null, returnNull = true)
        val check = makeCheck(currentVersion = "v2.1.0", fetcher = fetcher)
        check.maybeNotice()
        val state = readPersistedState()
        assertEquals(now, state.lastCheckedAt, "timestamp must advance even on failure")
        assertNull(state.latestStable)
    }

    // ---------- notice logic ----------

    @Test
    fun `stable user sees a notice for a newer stable`() {
        val fetcher = FakeFetcher(stable = "v2.2.0")
        val check = makeCheck(currentVersion = "v2.1.0", fetcher = fetcher)
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.1.0", notice.currentVersion)
        assertEquals("v2.2.0", notice.latestVersion)
        assertTrue(notice.url.endsWith("/releases/tag/v2.2.0"))
    }

    @Test
    fun `stable user does NOT see a notice for a newer prerelease only`() {
        val fetcher = FakeFetcher(stable = "v2.1.0", prerelease = "v2.2.0-beta.1")
        val check = makeCheck(currentVersion = "v2.1.0", fetcher = fetcher)
        assertNull(check.maybeNotice())
    }

    @Test
    fun `prerelease user sees a notice for a newer prerelease`() {
        val fetcher = FakeFetcher(stable = "v2.0.0", prerelease = "v2.1.0-beta.3")
        val check = makeCheck(currentVersion = "v2.1.0-beta.2", fetcher = fetcher)
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.1.0-beta.3", notice.latestVersion)
    }

    @Test
    fun `prerelease user sees a graduation notice when only a newer stable exists`() {
        val fetcher = FakeFetcher(stable = "v2.1.0", prerelease = "v2.1.0-beta.2")
        val check = makeCheck(currentVersion = "v2.1.0-beta.2", fetcher = fetcher)
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.1.0", notice.latestVersion, "graduate to stable when no newer prerelease")
    }

    @Test
    fun `prerelease user prefers a newer prerelease over a newer stable`() {
        val fetcher = FakeFetcher(stable = "v2.1.0", prerelease = "v2.2.0-beta.1")
        val check = makeCheck(currentVersion = "v2.1.0-beta.2", fetcher = fetcher)
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.2.0-beta.1", notice.latestVersion)
    }

    @Test
    fun `same notice fires once then suppresses`() {
        val fetcher = FakeFetcher(stable = "v2.2.0")
        val check = makeCheck(currentVersion = "v2.1.0", fetcher = fetcher)
        assertNotNull(check.maybeNotice(), "first invocation surfaces the notice")

        // Re-invoke within the interval: state is fresh, lastNotifiedVersion is set, no notice.
        val secondCheck =
            makeCheck(
                currentVersion = "v2.1.0",
                fetcher = fetcher,
                nowOverride = now.plus(Duration.ofMinutes(5)),
            )
        assertNull(secondCheck.maybeNotice(), "same version should not nag")
    }

    @Test
    fun `newer release after a previous notice fires a fresh notice`() {
        writePersistedState(
            UpdateCheckState(
                lastCheckedAt = now,
                latestStable = "v2.1.0",
                latestPrerelease = null,
                lastNotifiedVersion = "v2.1.0",
            )
        )
        val fetcher = FakeFetcher(stable = "v2.2.0")
        val check =
            makeCheck(
                currentVersion = "v2.0.0",
                fetcher = fetcher,
                nowOverride = now.plus(Duration.ofHours(25)),
            )
        val notice = assertNotNull(check.maybeNotice())
        assertEquals("v2.2.0", notice.latestVersion)
    }

    // ---------- helpers ----------

    private class FakeFetcher(
        private val stable: String? = null,
        private val prerelease: String? = null,
        private val returnNull: Boolean = false,
    ) : ReleaseFetcher {
        var callCount = 0
            private set

        override fun fetchLatestReleases(): LatestReleases? {
            callCount++
            if (returnNull) return null
            return LatestReleases(
                stable = stable?.let { ReleaseRef(it, "https://example.com/$it") },
                prerelease = prerelease?.let { ReleaseRef(it, "https://example.com/$it") },
            )
        }
    }

    private fun makeCheck(
        currentVersion: String,
        fetcher: ReleaseFetcher = FakeFetcher(),
        nowOverride: Instant = now,
        skip: () -> Boolean = { false },
    ): UpdateCheck =
        UpdateCheck(
            stateFile = stateFile,
            fetcher = fetcher,
            now = { nowOverride },
            currentVersion = currentVersion,
            interval = interval,
            json = json,
            skip = skip,
        )

    private fun readPersistedState(): UpdateCheckState =
        json.decodeFromString<UpdateCheckState>(stateFile.readText())

    private fun writePersistedState(state: UpdateCheckState) {
        stateFile.writeText(json.encodeToString(UpdateCheckState.serializer(), state))
    }
}
