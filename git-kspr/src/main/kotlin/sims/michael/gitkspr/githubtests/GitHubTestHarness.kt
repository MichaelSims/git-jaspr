package sims.michael.gitkspr.githubtests

import sims.michael.gitkspr.JGitClient
import java.io.File

class GitHubTestHarness(private val repo: File) {
    fun createRepoWithInitialCommit(): File {
        val readme = "README.txt"
        val readmeFile = repo.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        val git = JGitClient(repo).init()
        git.add(readme).commit("Initial commit")
        return repo
    }

    fun createCommits(branch: BranchData) {
        fun createIt(single: CommitData) {
            val file = repo.resolve("${single.title.sanitize()}.txt")
            file.writeText("Title: ${single.title}\n")
            val git = JGitClient(repo).init()
            git.add(file.name).commit(single.title)
        }

        for (commit in branch.commits) {
            createIt(commit)
        }
    }


    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()
}