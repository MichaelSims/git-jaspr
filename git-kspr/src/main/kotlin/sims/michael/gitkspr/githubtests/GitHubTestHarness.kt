package sims.michael.gitkspr.githubtests

import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.*
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.Ident
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import sims.michael.gitkspr.PullRequest
import java.io.File

class GitHubTestHarness(
    private val localRepo: File,
    private val remoteRepo: File,
    private val gitHubClients: Map<String, GitHubClient> = emptyMap(),
    remoteUri: String? = null,
) {
    private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

    private val localGit: JGitClient = JGitClient(localRepo)

    init {
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
        requireNoDuplicatedCommitTitles(testCase)
        requireNoDuplicatedPrTitles(testCase)
        val titleToCommitHashMap = localGit.logAll().associate { it.shortMessage to it.hash }
        localGit.checkout(localGit.logAll().last().hash)
        fun doCreateCommits(branch: BranchData) {
            val iterator = branch.commits.iterator()
            while (iterator.hasNext()) {
                val commit = iterator.next()
                setGitCommitterInfo(commit.committer.toIdent())
                val existingHash = titleToCommitHashMap[commit.title]
                val c = if (existingHash != null) {
                    localGit.cherryPick(localGit.log(existingHash, maxCount = 1).single())
                } else {
                    commit.create().also { logger.info("Created {}", it) }
                }
                if (!iterator.hasNext()) requireNamedRef(commit)
                for (localRef in commit.localRefs) {
                    val oldCommit = localGit.branch(localRef, force = true)
                    if (oldCommit != null) {
                        val restore = "${RESTORE_PREFIX}$localRef"
                        val branchNames = localGit.getBranchNames()
                        if (!branchNames.contains(restore)) {
                            if (branchNames.none { it.startsWith("${DELETE_PREFIX}$localRef") }) {
                                localGit.branch(restore, startPoint = oldCommit.hash)
                            } else {
                                localGit.branch("${DELETE_PREFIX}$localRef/${generateUuid(20)}")
                            }
                        }
                    } else {
                        localGit.branch("${DELETE_PREFIX}$localRef/${generateUuid(20)}")
                    }
                }
                for (remoteRef in commit.remoteRefs) {
                    localGit.push(listOf(RefSpec("+HEAD", remoteRef)))
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

        val prs = testCase.pullRequests
        if (prs.isNotEmpty()) {
            val existingPrsByTitle = gitHubClients.values.first().getPullRequests().associateBy(PullRequest::title)
            for (pr in prs) {
                val gitHubClient = requireNotNull(gitHubClients[pr.userKey] ?: gitHubClients.values.firstOrNull()) {
                    "No github client available!"
                }
                val newPullRequest = PullRequest(null, null, null, pr.headRef, pr.baseRef, pr.title, pr.body)
                val existingPr = existingPrsByTitle[pr.title]
                if (existingPr != null) {
                    gitHubClient.updatePullRequest(newPullRequest.copy(id = existingPr.id))
                } else {
                    gitHubClient.createPullRequest(newPullRequest)
                }
            }
        }
    }

    fun rollbackRemoteChanges() {
        logger.trace("rollbackRemoteChanges")
        val restoreRegex = "${RESTORE_PREFIX}(.*?)".toRegex()
        val toRestore = localGit
            .getBranchNames()
            .also { branchNames -> logger.trace("getBranchNames {}", branchNames) }
            .mapNotNull { name ->
                restoreRegex.matchEntire(name)
            }
            .map {
                RefSpec("+" + it.groupValues[0], it.groupValues[1])
            }
        val deleteRegex = "${DELETE_PREFIX}(.*?)/.*".toRegex()
        val toDelete = localGit.getBranchNames()
            .mapNotNull { name -> deleteRegex.matchEntire(name) }
            .map {
                RefSpec("+", it.groupValues[1])
            }
        val refSpecs = (toRestore + toDelete).distinct()
        logger.debug("Pushing {}", refSpecs)
        localGit.push(refSpecs)
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

    private fun IdentData.toIdent(): Ident = Ident(name, email)

    private fun requireNamedRef(commit: CommitData) {
        require((commit.localRefs + commit.remoteRefs).isNotEmpty()) {
            "\"${commit.title}\" is not connected to any branches. Assign a local or remote ref to fix this"
        }
    }

    private fun setGitCommitterInfo(ident: Ident) {
        SystemReader
            .setInstance(
                MockSystemReader()
                    .apply {
                        setProperty(GIT_COMMITTER_NAME_KEY, ident.name.ifBlank { DEFAULT_COMMITTER.name })
                        setProperty(GIT_COMMITTER_EMAIL_KEY, ident.email.ifBlank { DEFAULT_COMMITTER.email })
                    },
            )
    }

    private fun requireNoDuplicatedCommitTitles(testCase: TestCaseData) {
        fun collectCommitTitles(branchData: BranchData): List<String> =
            branchData.commits.fold(emptyList()) { list, commit ->
                list + commit.title + commit.branches.flatMap { collectCommitTitles(it) }
            }

        val titles = collectCommitTitles(testCase.repository)
        val duplicatedTitles = titles.groupingBy { it }.eachCount().filterValues { count -> count > 1 }.keys
        require(duplicatedTitles.isEmpty()) {
            "All commit subjects in the repo should be unique as they are used as keys. " +
                "The following were duplicated: $duplicatedTitles"
        }
    }

    private fun requireNoDuplicatedPrTitles(testCase: TestCaseData) {
        val duplicatedTitles = testCase
            .pullRequests
            .groupingBy(PullRequestData::title)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicatedTitles.isEmpty()) {
            "All pull request titles should be unique as they are used as keys. " +
                "The following were duplicated: $duplicatedTitles"
        }
    }

    private val filenameSafeRegex = "\\W+".toRegex()
    private fun String.sanitize() = replace(filenameSafeRegex, "_").lowercase()

    companion object {
        const val LOCAL_REPO_SUBDIR = "local"
        const val REMOTE_REPO_SUBDIR = "remote"
        val RESTORE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-restore/"
        val DELETE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-delete/"
        val DEFAULT_COMMITTER: Ident = Ident("Frank Grimes", "grimey@springfield.example.com")
    }
}
