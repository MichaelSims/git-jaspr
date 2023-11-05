package sims.michael.gitkspr.githubtests

import sims.michael.gitkspr.DEFAULT_TARGET_REF
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import java.io.File

class GitHubTestHarness(private val repo: File) {

    private val git = JGitClient(repo)

    fun createRepoWithInitialCommit(): File {
        val readme = "README.txt"
        val readmeFile = repo.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        val git = git.init()
        git.add(readme).commit("Initial commit")
        return repo
    }

    fun createCommits(branch: BranchData) {
        git.checkout(branch.name.ifBlank { DEFAULT_TARGET_REF }, CreateBranchIfNotExists)
        for (commit in branch.commits) {
            commit.create()
            if (commit.branches.isNotEmpty()) {
                for (childBranch in commit.branches) {
                    createCommits(childBranch)
                }
                git.checkout(branch.name, CreateBranchIfNotExists)
            }
        }
    }

    private fun CommitData.create() {
        val file = repo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        git.add(file.name).commit(title)
    }

    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()
}
