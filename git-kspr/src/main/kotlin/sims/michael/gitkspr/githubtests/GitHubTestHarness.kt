package sims.michael.gitkspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitkspr.*
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import java.io.File

class GitHubTestHarness(
    workingDirectory: File,
    remoteUri: String? = null,
    getGitHubClient: (File) -> GitHubClient? = { null },
) {
    private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

    val localRepo by lazy { workingDirectory.resolve(LOCAL_REPO_SUBDIR).also(File::mkdir) }

    private val localGit: JGitClient = JGitClient(localRepo)

    private val gitHubClient = getGitHubClient(workingDirectory)

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

    suspend fun createCommits(testCase: TestCaseData) {
        localGit.checkout("HEAD") // Go into detached head so as not to move the main ref as we create commits
        fun doCreateCommits(branch: BranchData) {
            val iterator = branch.commits.iterator()
            while (iterator.hasNext()) {
                val commit = iterator.next()
                val c = commit.create().also { logger.info("Created {}", it) }
                if (!iterator.hasNext()) requireNamedRef(commit)
                for (localRef in commit.localRefs) {
                    val commit1 = localGit.branch(localRef, force = true)
                    if (commit1 != null) {
                        localGit.branch("${RESTORE_PREFIX}$localRef", startPoint = commit1.hash)
                    } else {
                        localGit.branch("${DELETE_PREFIX}$localRef")
                    }
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

        doCreateCommits(testCase.repository)
        localGit.checkout(DEFAULT_TARGET_REF)

        if (gitHubClient != null) {
            for (pr in testCase.pullRequests) {
                gitHubClient.createPullRequest(PullRequest(null, null, null, pr.headRef, pr.baseRef, pr.title, pr.body))
            }
        }
    }

    fun rollbackRemoteChanges() {
        val restoreRegex = "${RESTORE_PREFIX}(.*?)".toRegex()
        val toRestore = localGit
            .getBranchNames()
            .mapNotNull { name ->
                restoreRegex.matchEntire(name)
            }
            .map {
                RefSpec("+" + it.groupValues[0], it.groupValues[1])
            }
        val deleteRegex = "${DELETE_PREFIX}(.*?)".toRegex()
        val toDelete = localGit.getBranchNames()
            .mapNotNull { name -> deleteRegex.matchEntire(name) }
            .map {
                RefSpec("+", it.groupValues[1])
            }
        localGit.push(toRestore + toDelete)
        localGit.deleteBranches(
            names = toRestore.map(RefSpec::localRef) + toDelete.map(RefSpec::remoteRef),
            force = true,
        )
    }

    private fun CommitData.create(): Commit {
        val file = localRepo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        return localGit.add(file.name).commit(title)
    }

    private fun requireNamedRef(commit: CommitData) {
        require((commit.localRefs + commit.remoteRefs).isNotEmpty()) {
            "\"${commit.title}\" is not connected to any branches. Assign a local or remote ref to fix this"
        }
    }

    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()

    companion object {
        private const val LOCAL_REPO_SUBDIR = "local"
        private const val REMOTE_REPO_SUBDIR = "remote"
        val RESTORE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-restore/"
        val DELETE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-delete/"
    }
}
