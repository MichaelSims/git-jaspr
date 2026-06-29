package sims.michael.gitjaspr

import java.io.File
import java.io.RandomAccessFile
import org.slf4j.LoggerFactory

/**
 * Classifies whether two commits represent the same logical change. Two commits are
 * [Result.IDENTICAL] when their patches (their diffs against their respective parents) are
 * equivalent **and** their commit messages (subject + body) match, even if they sit on different
 * parents. They are [Result.DIVERGENT] when the patches differ, when the messages differ, or both:
 * for example, an amended commit changes the patch; a reworded commit changes the message. The
 * classification is symmetric: `classify(a, b)` always agrees with `classify(b, a)`.
 *
 * Strategy:
 * 1. Message comparison. If the two commits' full messages (trimmed of trailing whitespace) differ,
 *    classify as [Result.DIVERGENT]. This is the cheapest check and short-circuits cases where the
 *    patches happen to match but the messages don't (e.g., a reword).
 * 2. Patch-id fast path. If `git patch-id` of the two commits' diffs match, classify as
 *    [Result.IDENTICAL]. Patch-id ignores line numbers and whitespace, so unrelated line-number
 *    shifts during a clean rebase still register as the same patch.
 * 3. Cherry-pick probe slow path. On patch-id mismatch, attempt to cherry-pick one commit onto the
 *    other's parent in a scratch worktree. If the cherry-pick applies cleanly and the resulting
 *    tree matches the expected tree, classify as [Result.IDENTICAL]. Otherwise [Result.DIVERGENT].
 *
 * Results are cached content-addressed under `.git/jaspr/divergence-cache/`. Cache entries never
 * need invalidation since the inputs are immutable commit SHAs.
 *
 * The probe worktree at `.git/jaspr/cherry-pick-probe-worktree` is created lazily on the first
 * simulation and removed on [close].
 */
class DivergenceClassifier(jasprDir: File, private val gitClient: GitClient) : AutoCloseable {

    enum class Result {
        IDENTICAL,
        DIVERGENT,
    }

    private val logger = LoggerFactory.getLogger(DivergenceClassifier::class.java)
    private val cacheDir: File = jasprDir.resolve("divergence-cache").also { it.mkdirs() }
    private val worktreeDir: File = jasprDir.resolve("cherry-pick-probe-worktree")
    private val lockFile: File = jasprDir.resolve("cherry-pick-probe.lock")
    private var worktreeReady = false
    private var lock: RandomAccessFile? = null

    fun classify(aSha: String, bSha: String): Result {
        if (aSha == bSha) return Result.IDENTICAL

        val cachedResult = readCachedResult(aSha, bSha)
        if (cachedResult != null) {
            return cachedResult
        }

        val result =
            if (!messagesMatch(aSha, bSha)) {
                Result.DIVERGENT
            } else {
                val aPatchId = computePatchId(aSha)
                val bPatchId = computePatchId(bSha)
                if (aPatchId != null && aPatchId == bPatchId) {
                    Result.IDENTICAL
                } else {
                    simulateCherryPick(aSha, bSha)
                }
            }
        writeCachedResult(aSha, bSha, result)
        return result
    }

    /**
     * True when both commits' full messages (subject + body, trimmed of trailing whitespace) are
     * non-null and equal. Returns false if either message cannot be read; treating a read failure
     * as "messages differ" is conservative and avoids classifying as IDENTICAL when we can't
     * actually verify equality.
     */
    private fun messagesMatch(aSha: String, bSha: String): Boolean {
        val aMessage = computeCommitMessage(aSha) ?: return false
        val bMessage = computeCommitMessage(bSha) ?: return false
        return aMessage == bMessage
    }

    private fun computeCommitMessage(sha: String): String? =
        try {
            gitClient.log(sha, 1).singleOrNull()?.fullMessage?.trimEnd()
        } catch (e: Exception) {
            logger.debug("Failed to read commit message for {}", sha, e)
            null
        }

    override fun close() {
        try {
            if (worktreeReady) {
                removeProbeWorktreeQuietly()
            }
        } finally {
            lock?.close()
            lock = null
        }
    }

    private fun cacheFile(aSha: String, bSha: String): File {
        // Canonicalize the pair so classify(a, b) and classify(b, a) hit the same on-disk entry.
        val (first, second) = listOf(aSha, bSha).sorted()
        return cacheDir.resolve("$first-$second")
    }

    private fun readCachedResult(aSha: String, bSha: String): Result? {
        val file = cacheFile(aSha, bSha)
        if (!file.exists()) return null
        return try {
            Result.valueOf(file.readText().trim())
        } catch (_: IllegalArgumentException) {
            logger.warn("Discarding malformed divergence cache entry at {}", file)
            file.delete()
            null
        }
    }

    private fun writeCachedResult(aSha: String, bSha: String, result: Result) {
        val file = cacheFile(aSha, bSha)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(result.name)
        tmp.renameTo(file)
    }

    private fun computePatchId(sha: String): String? =
        try {
            gitClient.patchId(sha)
        } catch (e: Exception) {
            logger.debug("Failed to compute patch-id for {}", sha, e)
            null
        }

    private fun simulateCherryPick(aSha: String, bSha: String): Result {
        acquireLock()
        ensureWorktree()
        val worktreeClient = DefaultGitClient(worktreeDir)
        try {
            worktreeClient.reset("$aSha^")
        } catch (e: Exception) {
            logger.debug("reset --hard failed in probe worktree", e)
            return Result.DIVERGENT
        }
        try {
            worktreeClient.cherryPick(worktreeClient.log(bSha, 1).single())
        } catch (e: Exception) {
            logger.debug("cherry-pick failed in probe worktree", e)
            worktreeClient.cherryPickAbort()
            return Result.DIVERGENT
        }
        val resultTree = worktreeClient.getTree(GitClient.HEAD)
        val expectedTree = gitClient.getTree(aSha)
        return if (resultTree == expectedTree) Result.IDENTICAL else Result.DIVERGENT
    }

    private fun acquireLock() {
        if (lock != null) return
        val raf = RandomAccessFile(lockFile, "rw")
        raf.channel.lock()
        lock = raf
    }

    private fun ensureWorktree() {
        if (worktreeReady) return
        // Clean up any stale worktree left behind by a crashed run before we add a new one.
        if (worktreeDir.exists()) {
            removeProbeWorktreeQuietly()
        }
        gitClient.addWorktree(worktreeDir, detached = true)
        worktreeReady = true
    }

    /**
     * Force-removes the probe worktree, swallowing failures (logged at debug). Used in cleanup
     * paths where the worktree may not exist or may be in an unexpected state.
     */
    private fun removeProbeWorktreeQuietly() {
        try {
            gitClient.removeWorktree(worktreeDir, force = true)
        } catch (e: Exception) {
            logger.debug("Failed to remove probe worktree at {}: {}", worktreeDir, e.message)
        }
    }
}
