package sims.michael.gitjaspr

import java.io.File
import java.io.RandomAccessFile
import org.slf4j.LoggerFactory

/**
 * Classifies whether two commits represent the same logical change. Two commits are
 * [Result.IDENTICAL] when their patches (their diffs against their respective parents) are
 * equivalent, even if they sit on different parents. They are [Result.DIVERGENT] when the patches
 * differ, for example, because one was amended or because conflict-resolution edits landed during a
 * rebase. The classification is symmetric: `classify(a, b)` always agrees with `classify(b, a)`.
 *
 * Strategy:
 * 1. Patch-id fast path. If `git patch-id` of the two commits' diffs match, classify as
 *    [Result.IDENTICAL]. Patch-id ignores line numbers and whitespace, so unrelated line-number
 *    shifts during a clean rebase still register as the same patch.
 * 2. Cherry-pick probe slow path. On patch-id mismatch, attempt to cherry-pick one commit onto the
 *    other's parent in a scratch worktree. If the cherry-pick applies cleanly and the resulting
 *    tree matches the expected tree, classify as [Result.IDENTICAL]. Otherwise [Result.DIVERGENT].
 *
 * Results are cached content-addressed under `.git/jaspr/divergence-cache/`. Cache entries never
 * need invalidation since the inputs are immutable commit SHAs.
 *
 * The probe worktree at `.git/jaspr/cherry-pick-probe-worktree` is created lazily on the first
 * simulation and removed on [close].
 */
class DivergenceClassifier(private val workingDirectory: File, jasprDir: File) : AutoCloseable {

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

        val aPatchId = computePatchId(aSha)
        val bPatchId = computePatchId(bSha)

        val result =
            if (aPatchId != null && aPatchId == bPatchId) {
                Result.IDENTICAL
            } else {
                simulateCherryPick(aSha, bSha)
            }
        writeCachedResult(aSha, bSha, result)
        return result
    }

    override fun close() {
        try {
            if (worktreeReady) {
                runGit(workingDirectory, "worktree", "remove", "--force", worktreeDir.absolutePath)
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
            val pipeline =
                ProcessBuilder.startPipeline(
                    listOf(
                        ProcessBuilder("git", "show", sha).directory(workingDirectory),
                        ProcessBuilder("git", "patch-id", "--stable").directory(workingDirectory),
                    )
                )
            val tail = pipeline.last()
            val output = tail.inputStream.bufferedReader().readText().trim()
            val showRc = pipeline.first().waitFor()
            val patchIdRc = tail.waitFor()
            if (showRc != 0 || patchIdRc != 0 || output.isEmpty()) {
                null
            } else {
                output.substringBefore(' ').takeIf(String::isNotEmpty)
            }
        } catch (e: Exception) {
            logger.debug("Failed to compute patch-id for {}", sha, e)
            null
        }

    private fun simulateCherryPick(aSha: String, bSha: String): Result {
        acquireLock()
        ensureWorktree()
        if (runGit(worktreeDir, "reset", "--hard", "$aSha^") != 0) {
            return Result.DIVERGENT
        }
        val cpRc = runGit(worktreeDir, "cherry-pick", "--allow-empty", bSha)
        if (cpRc != 0) {
            runGit(worktreeDir, "cherry-pick", "--abort")
            return Result.DIVERGENT
        }
        val resultTree = gitOutput(worktreeDir, "rev-parse", "HEAD^{tree}")
        val expectedTree = gitOutput(workingDirectory, "rev-parse", "$aSha^{tree}")
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
            runGit(workingDirectory, "worktree", "remove", "--force", worktreeDir.absolutePath)
        }
        val rc = runGit(workingDirectory, "worktree", "add", "--detach", worktreeDir.absolutePath)
        check(rc == 0) { "Failed to create cherry-pick probe worktree at $worktreeDir" }
        worktreeReady = true
    }

    private fun runGit(dir: File, vararg args: String): Int =
        ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start().let {
            proc ->
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
        }

    private fun gitOutput(dir: File, vararg args: String): String {
        val proc =
            ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0) { "git ${args.toList()} in $dir failed: $output" }
        return output
    }
}
