package sims.michael.gitkspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitkspr.DEFAULT_TARGET_REF
import sims.michael.gitkspr.JGitClient
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import sims.michael.gitkspr.RefSpec
import java.io.File

class GitHubTestHarness(
    workingDirectory: File,
) {

    private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

    val localRepo by lazy { workingDirectory.resolve(LOCAL_REPO_SUBDIR).also(File::mkdir) }
    val remoteRepo by lazy { workingDirectory.resolve(REMOTE_REPO_SUBDIR).also(File::mkdir) }

    private val localGit = JGitClient(localRepo)
    private val remoteGit = JGitClient(remoteRepo)

    fun createRepoWithInitialCommit(): File {
        val readme = "README.txt"
        val readmeFile = remoteRepo.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        val git = remoteGit.init()
        git.add(readme).commit("Initial commit")
        localGit.clone(remoteRepo.toURI().toString())
        return localRepo
    }

    fun createCommits(branch: BranchData) {
        fun doCreateCommits(branch: BranchData) {
            localGit.checkout(branch.name.ifBlank { DEFAULT_TARGET_REF }, CreateBranchIfNotExists)
            for (commit in branch.commits) {
                commit.create()
                if (commit.branches.isNotEmpty()) {
                    for (childBranch in commit.branches) {
                        doCreateCommits(childBranch)
                    }
                    localGit.checkout(branch.name, CreateBranchIfNotExists)
                }
            }
        }

        doCreateCommits(branch)
        localGit.push(collectBranches(branch).map { branchName -> RefSpec(branchName, branchName) })
    }


    private fun collectBranches(branch: BranchData): List<String> {
        return branch.commits.fold(listOf(branch.name)) { branchNames, commit ->
            branchNames + commit.branches.flatMap(::collectBranches)
        }
    }

    private fun CommitData.create() {
        val file = localRepo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        localGit.add(file.name).commit(title)
    }

    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()

    companion object {
        private const val LOCAL_REPO_SUBDIR = "local"
        private const val REMOTE_REPO_SUBDIR = "remote"
    }
}
