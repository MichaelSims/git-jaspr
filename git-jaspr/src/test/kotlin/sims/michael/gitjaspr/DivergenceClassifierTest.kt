package sims.michael.gitjaspr

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DivergenceClassifierTest {

    @Test
    fun `same SHA classifies as IDENTICAL`() {
        withRepo { workDir ->
            val sha = commit(workDir, "file.txt", "content\n", "subject", DATE_A)
            withClassifier(workDir) { classifier ->
                assertEquals(DivergenceClassifier.Result.IDENTICAL, classifier.classify(sha, sha))
            }
        }
    }

    @Test
    fun `identical patches with identical messages classify as IDENTICAL`() {
        withRepo { workDir ->
            val shaA = commit(workDir, "file.txt", "content\n", "shared subject", DATE_A)
            git(workDir, "reset", "HEAD~1")
            val shaB = commit(workDir, "file.txt", "content\n", "shared subject", DATE_B)

            assertNotEquals(shaA, shaB)
            withClassifier(workDir) { classifier ->
                assertEquals(DivergenceClassifier.Result.IDENTICAL, classifier.classify(shaA, shaB))
            }
        }
    }

    @Test
    fun `identical patches with different subjects classify as DIVERGENT`() {
        withRepo { workDir ->
            val shaA = commit(workDir, "file.txt", "content\n", "Subject A", DATE_A)
            git(workDir, "reset", "HEAD~1")
            val shaB = commit(workDir, "file.txt", "content\n", "Subject B", DATE_B)

            assertNotEquals(shaA, shaB)
            withClassifier(workDir) { classifier ->
                assertEquals(DivergenceClassifier.Result.DIVERGENT, classifier.classify(shaA, shaB))
            }
        }
    }

    @Test
    fun `identical patches with different bodies classify as DIVERGENT`() {
        withRepo { workDir ->
            val shaA = commit(workDir, "file.txt", "content\n", "subject\n\nBody A", DATE_A)
            git(workDir, "reset", "HEAD~1")
            val shaB = commit(workDir, "file.txt", "content\n", "subject\n\nBody B", DATE_B)

            assertNotEquals(shaA, shaB)
            withClassifier(workDir) { classifier ->
                assertEquals(DivergenceClassifier.Result.DIVERGENT, classifier.classify(shaA, shaB))
            }
        }
    }

    @Test
    fun `different patches with identical messages classify as DIVERGENT`() {
        withRepo { workDir ->
            val shaA = commit(workDir, "file.txt", "content A\n", "shared subject", DATE_A)
            git(workDir, "reset", "HEAD~1")
            val shaB = commit(workDir, "file.txt", "content B\n", "shared subject", DATE_B)

            assertNotEquals(shaA, shaB)
            withClassifier(workDir) { classifier ->
                assertEquals(DivergenceClassifier.Result.DIVERGENT, classifier.classify(shaA, shaB))
            }
        }
    }

    private fun withRepo(block: (File) -> Unit) {
        val workDir = createTempDirectory("jaspr-classifier-test").toFile()
        try {
            git(workDir, "init", "--initial-branch=main")
            git(workDir, "config", "user.email", "test@example.com")
            git(workDir, "config", "user.name", "Test")
            git(workDir, "commit", "--allow-empty", "-m", "initial")
            block(workDir)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun withClassifier(workDir: File, block: (DivergenceClassifier) -> Unit) {
        val jasprDir = workDir.resolve(".git/jaspr").also { it.mkdirs() }
        DivergenceClassifier(jasprDir, OptimizedCliGitClient(workDir)).use(block)
    }

    private fun commit(
        workDir: File,
        fileName: String,
        content: String,
        message: String,
        committerDate: String,
    ): String {
        workDir.resolve(fileName).writeText(content)
        git(workDir, "add", fileName)
        gitWithEnv(
            workDir,
            mapOf("GIT_AUTHOR_DATE" to committerDate, "GIT_COMMITTER_DATE" to committerDate),
            "commit",
            "-m",
            message,
        )
        return git(workDir, "rev-parse", "HEAD")
    }

    private fun git(dir: File, vararg args: String) = gitWithEnv(dir, emptyMap(), *args)

    private fun gitWithEnv(dir: File, env: Map<String, String>, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0) { "git ${args.toList()} failed: $output" }
        return output
    }

    companion object {
        private const val DATE_A = "2024-01-01T10:00:00+00:00"
        private const val DATE_B = "2024-01-01T11:00:00+00:00"
    }
}
