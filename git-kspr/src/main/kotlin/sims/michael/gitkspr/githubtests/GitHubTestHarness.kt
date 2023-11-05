package sims.michael.gitkspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.DEFAULT_TARGET_REF
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import sims.michael.gitkspr.RefSpec
import java.io.File

class GitHubTestHarness(workingDirectory: File, remoteUri: String? = null) {
    private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

    val localRepo by lazy { workingDirectory.resolve(LOCAL_REPO_SUBDIR).also(File::mkdir) }

    private val localGit: JGitClient = JGitClient(localRepo)

    init {
        val remoteRepo = workingDirectory.resolve(REMOTE_REPO_SUBDIR)
        if (remoteUri != null) {
            localGit.clone(remoteUri)
        } else {
            JGitClient(remoteRepo).init().createInitialCommit().also { localGit.clone(remoteRepo.toURI().toString()) }
        }
    }

    private fun JGitClient.createInitialCommit() = apply {
        val repoDir = workingDirectory
        val readme = "README.txt"
        val readmeFile = repoDir.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        add(readme).commit("Initial commit")
    }

    fun createCommits(branch: BranchData) {
        fun doCreateCommits(branch: BranchData) {
            for (commit in branch.commits) {
                val c = commit.create()
                for (localRef in commit.localRefs) {
                    localGit.branch(localRef, force = true)
                }
                for (remoteRef in commit.remoteRefs) {
                    localGit.push(listOf(RefSpec("HEAD", remoteRef)))
                }
                if (commit.branches.isNotEmpty()) {
                    for (childBranch in commit.branches) {
                        doCreateCommits(childBranch)
                    }
                    localGit.checkout(c.hash, CreateBranchIfNotExists)
                }
            }
        }

        doCreateCommits(branch)
        localGit.checkout(DEFAULT_TARGET_REF)
    }

    private fun CommitData.create(): Commit {
        val file = localRepo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        return localGit.add(file.name).commit(title)
    }

    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()

    companion object {
        private const val LOCAL_REPO_SUBDIR = "local"
        private const val REMOTE_REPO_SUBDIR = "remote"
    }
}
