package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import sims.michael.gitjaspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files

/**
 * Tests for git worktree support.
 *
 * Git worktrees allow multiple working directories to be associated with a single repository.
 * In a worktree, `.git` is a file (not a directory) containing a `gitdir:` pointer to the
 * actual git directory.
 */
class WorktreeSupportTest {

    private val logger = LoggerFactory.getLogger(WorktreeSupportTest::class.java)

    @Test
    fun `findNearestGitDir detects regular repository`() {
        val tempDir = createTempDir()
        try {
            val repoDir = tempDir.resolve("repo")
            CliGitClient(repoDir).init()

            val foundDir = repoDir.findNearestGitDir()
            assertEquals(repoDir.canonicalFile, foundDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `findNearestGitDir detects worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = CliGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree using git command
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            // Verify .git is a file in worktree
            val dotGit = worktreeDir.resolve(".git")
            assertTrue(dotGit.isFile, ".git should be a file in worktree")
            assertTrue(dotGit.readText().trim().startsWith("gitdir:"), ".git file should contain gitdir pointer")

            // Test findNearestGitDir works with worktree
            val foundDir = worktreeDir.findNearestGitDir()
            assertEquals(worktreeDir.canonicalFile, foundDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `findNearestGitDir detects worktree from subdirectory`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = CliGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            // Create a subdirectory in the worktree
            val subDir = worktreeDir.resolve("src/main/kotlin")
            subDir.mkdirs()

            // Test findNearestGitDir works from subdirectory
            val foundDir = subDir.findNearestGitDir()
            assertEquals(worktreeDir.canonicalFile, foundDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isGitWorkingDirectory returns true for regular repository`() {
        val tempDir = createTempDir()
        try {
            val repoDir = tempDir.resolve("repo")
            CliGitClient(repoDir).init()

            assertTrue(repoDir.isGitWorkingDirectory())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isGitWorkingDirectory returns true for worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = CliGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            assertTrue(worktreeDir.isGitWorkingDirectory())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isGitWorkingDirectory returns false for non-git directory`() {
        val tempDir = createTempDir()
        try {
            assertFalse(tempDir.isGitWorkingDirectory())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isGitWorkingDirectory returns false for directory with non-gitdir dot-git file`() {
        val tempDir = createTempDir()
        try {
            // Create a .git file that doesn't contain a gitdir pointer
            tempDir.resolve(".git").writeText("not a valid gitdir reference")

            assertFalse(tempDir.isGitWorkingDirectory())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JGitClient works in worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository with JGit
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = JGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree using git command (JGit doesn't support creating worktrees)
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            // Create JGitClient pointing to worktree
            val worktreeGit = JGitClient(worktreeDir)

            // Note: JGit doesn't fully support worktrees, so getCurrentBranchName() returns
            // the main repo's branch (main), not the worktree's branch (worktree-branch).
            // This is a known limitation. For git-jaspr's purposes, what matters is that
            // we can read/write commits and refs.
            assertFalse(worktreeGit.isHeadDetached())

            // Test log - should be able to read commits
            val log = worktreeGit.log()
            assertEquals(1, log.size)
            assertEquals("Initial commit", log.first().shortMessage)

            // Test creating a commit in worktree - the commit goes to main since JGit
            // uses the main repo's git directory
            worktreeDir.resolve("worktree-file.txt").writeText("Created in worktree")
            worktreeGit.add("worktree-file.txt").commit("Commit from worktree", commitIdent = DEFAULT_COMMITTER)

            val newLog = worktreeGit.log()
            assertEquals(2, newLog.size)
            assertEquals("Commit from worktree", newLog.last().shortMessage)

            // Verify that the commits can be seen via logAll
            val allLog = worktreeGit.logAll()
            assertTrue(allLog.any { it.shortMessage == "Commit from worktree" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JGitClient can read commits and refs in worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = CliGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create additional commits
            mainRepoDir.resolve("file1.txt").writeText("File 1")
            mainGit.add("file1.txt").commit("Add file 1", commitIdent = DEFAULT_COMMITTER)

            mainRepoDir.resolve("file2.txt").writeText("File 2")
            mainGit.add("file2.txt").commit("Add file 2", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            // Create JGitClient pointing to worktree
            val jgit = JGitClient(worktreeDir)

            // JGit should be able to read commits (uses main repo's git directory)
            val log = jgit.log()
            assertEquals(3, log.size)
            assertEquals("Add file 2", log.last().shortMessage)

            // JGit should be able to see all branches
            val branches = jgit.getBranchNames()
            assertTrue(branches.contains("main"), "Should see main branch")
            assertTrue(branches.contains("worktree-branch"), "Should see worktree-branch")

            // JGit should report clean working directory
            assertTrue(jgit.isWorkingDirectoryClean())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JGitClient handles branch operations in worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = JGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            val worktreeGit = JGitClient(worktreeDir)

            // Create a new branch from worktree (goes to main repo since JGit uses main git dir)
            worktreeGit.branch("feature-branch")
            assertTrue(worktreeGit.getBranchNames().contains("feature-branch"))

            // Branch should also be visible from main repo (it's the same git directory)
            assertTrue(mainGit.getBranchNames().contains("feature-branch"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JGitClient refExists works in worktree`() {
        val tempDir = createTempDir()
        try {
            // Create main repository
            val mainRepoDir = tempDir.resolve("main-repo")
            val mainGit = JGitClient(mainRepoDir).init()
            mainRepoDir.resolve("README.txt").writeText("Test repo")
            mainGit.add("README.txt").commit("Initial commit", commitIdent = DEFAULT_COMMITTER)

            // Create a worktree
            val worktreeDir = tempDir.resolve("worktree")
            ProcessExecutor()
                .directory(mainRepoDir)
                .command(listOf("git", "worktree", "add", worktreeDir.absolutePath, "-b", "worktree-branch"))
                .destroyOnExit()
                .execute()

            val worktreeGit = JGitClient(worktreeDir)

            // Test refExists - should see all refs from main repo
            assertTrue(worktreeGit.refExists("main"))
            assertTrue(worktreeGit.refExists("worktree-branch"))
            assertFalse(worktreeGit.refExists("nonexistent-branch"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(): File {
        return Files.createTempDirectory(WorktreeSupportTest::class.java.simpleName).toFile()
            .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }
    }
}
