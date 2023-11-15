package sims.michael.gitkspr.githubtests

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitkspr.*
import sims.michael.gitkspr.Commit
import sims.michael.gitkspr.Ident
import sims.michael.gitkspr.JGitClient.CheckoutMode.CreateBranchIfNotExists
import sims.michael.gitkspr.PullRequest
import java.io.File
import java.nio.file.Files
import java.util.Properties

data class GitHubTestHarness(
    val localRepo: File,
    val remoteRepo: File,
    val configMap: Map<String, UserConfig> = emptyMap(),
    private val remoteUri: String? = null,
    private val gitHubInfo: GitHubInfo = GitHubInfo("github.com", "MichaelSims", "git-spr-demo"),
    private val prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    val useFakeRemote: Boolean = true,
) {

    val localGit: JGitClient = JGitClient(localRepo)
    val remoteGit: JGitClient = JGitClient(remoteRepo)

    private val configMapWithClient: Map<String, Pair<UserConfig, GitHubClient>> = configMap
        .map { (k, v) ->
            k to (v to GitHubClientWiring(v.githubToken, gitHubInfo, prefix).gitHubClient)
        }
        .toMap()


    init {


        if (!useFakeRemote) {
            localGit.clone(remoteUri!!)
        } else {
            // remoteUri is used for functional tests. If we don't have one we create a "fake" remote with an initial
            // commit and clone it.
            remoteGit.init().createInitialCommit()
            localGit.clone(remoteRepo.toURI().toString())
        }
    }

    private fun JGitClient.createInitialCommit() = apply {
        val repoDir = workingDirectory
        val readme = "README.txt"
        val readmeFile = repoDir.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        add(readme).commit(INITIAL_COMMIT_SHORT_MESSAGE)
    }

    suspend fun createCommitsFrom(testCase: TestCaseData) {
        requireNoDuplicatedCommitTitles(testCase)
        requireNoDuplicatedPrTitles(testCase)

        val commitHashesByTitle = localGit.logAll().associate { commit -> commit.shortMessage to commit.hash }

        val initialCommit = localGit.log(DEFAULT_TARGET_REF).last()
        localGit.checkout(initialCommit.hash) // Go into detached HEAD

        fun doCreateCommits(branch: BranchData) {
            val iterator = branch.commits.iterator()
            while (iterator.hasNext()) {
                val commitData = iterator.next()

                setGitCommitterInfo(commitData.committer.toIdent())

                val existingHash = commitHashesByTitle[commitData.title]
                val commit = if (existingHash != null) {
                    // A commit with this title already exists... cherry-pick it
                    localGit.cherryPick(localGit.log(existingHash, maxCount = 1).single())
                } else {
                    // Create a new one
                    commitData.create()
                }

                if (!iterator.hasNext()) {
                    // This is a HEAD commit with no more children... bomb out if it doesn't have a named ref assigned
                    requireNamedRef(commitData)
                }

                // Create temp branches to track which refs need to either be restored or deleted when the test is
                // finished and we roll back the changes (important in functional tests to leave the remote repo in
                // the same state we left it)
                for (localRef in commitData.localRefs) {
                    val previousCommit = localGit.branch(localRef, force = true)

                    val rollbackDeleteMarker = "${DELETE_PREFIX}$localRef"
                    val rollbackRestoreMarker = "${RESTORE_PREFIX}$localRef"

                    if (previousCommit != null) {
                        // This local ref existed already...
                        val branchNames = localGit.getBranchNames()
                        if (branchNames.any { it.startsWith(rollbackDeleteMarker) }) {
                            // ...and was marked for deletion in a previous test stage. Update the marker.
                            localGit.branch(rollbackDeleteMarker, force = true)
                        } else if (!branchNames.contains(rollbackRestoreMarker)) {
                            // ...and was not previously marked for deletion. Set the restore marker unless it already
                            // exists due to a previous testing stage.
                            localGit.branch(rollbackRestoreMarker, startPoint = previousCommit.hash)
                        }
                    } else {
                        // This local ref is new. Set the delete marker
                        localGit.branch(rollbackDeleteMarker, force = true)
                    }
                }

                for (remoteRef in commitData.remoteRefs) {
                    localGit.push(listOf(RefSpec("+HEAD", remoteRef)))
                }

                if (commitData.branches.isNotEmpty()) {
                    for (childBranch in commitData.branches) {
                        doCreateCommits(childBranch)
                    }
                    localGit.checkout(commit.hash, CreateBranchIfNotExists)
                }
            }
        }

        doCreateCommits(testCase.repository)
        if (testCase.localIsDirty) {
            localRepo
                .walk()
                .maxDepth(1)
                .filter(File::isFile)
                .filter { file -> file.name.endsWith(".txt") }
                .first()
                .appendText("This is an uncommitted change.\n")
        }

        val prs = testCase.pullRequests
        if (prs.isNotEmpty()) {
            val existingPrsByTitle = configMapWithClient.values.first().second.getPullRequests().associateBy(PullRequest::title)
            for (pr in prs) {
                val gitHubClient =
                    requireNotNull(configMapWithClient[pr.userKey] ?: configMapWithClient.values.firstOrNull()) {
                        "No github client available!"
                    }.second
                val newPullRequest = PullRequest(null, null, null, pr.headRef, pr.baseRef, pr.title, pr.body)
                val existingPr = existingPrsByTitle[pr.title]
                if (existingPr == null) {
                    gitHubClient.createPullRequest(newPullRequest)
                } else {
                    gitHubClient.updatePullRequest(newPullRequest.copy(id = existingPr.id))
                }
            }
        }

        gitLogLocalAndRemote()
    }

    private fun gitLogLocalAndRemote() {
        gitLogGraphAll(localRepo, "LOCAL")
        if (remoteRepo.exists()) { // If we cloned from a remote URI there will be a "fake" remote
            gitLogGraphAll(remoteRepo, "REMOTE")
        }
    }

    private fun gitLogGraphAll(repo: File, label: String) {
        logger.trace("----------")
        ProcessExecutor()
            .directory(repo)
            .command(listOf("git", "log", "--graph", "--all", "--oneline", "--pretty=format:%h -%d %s <%an>"))
            .destroyOnExit()
            .readOutput(true)
            .execute()
            .output
            .lines
            .forEach {
                logger.trace("{}: {}", label, it)
            }
        logger.trace("----------")
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

        // TODO this currently deletes all "kspr/" branches indiscriminately. Much better would be to capture the ones
        //  we created via our JGitClient and delete only those
        val deleteRegex =
            "($DELETE_PREFIX(.*)|${JGitClient.R_REMOTES}$DEFAULT_REMOTE_NAME/($DEFAULT_REMOTE_BRANCH_PREFIX.*))"
                .toRegex()

        val toDelete = localGit.getBranchNames()
            .mapNotNull { name -> deleteRegex.matchEntire(name) }
            .map { result ->
                RefSpec("+", result.groupValues.last(String::isNotBlank))
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

    data class UserConfig(
        val name: String,
        val email: String,
        val githubToken: String,
    )

    companion object {
        fun withTestSetup(
            useFakeRemote: Boolean = true,
            configPropertiesFile: File = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME),
            block: suspend GitHubTestHarness.() -> Unit,
        ) {

            val (localRepo, remoteRepo) = createTempDir().createRepoDirs()

            val properties = Properties()
                .apply { configPropertiesFile.inputStream().use(::load) }.map { (k,v) -> k.toString() to v.toString() }.toMap()

            val configMap = properties.getUserConfigFromPropertiesFile()

            val githubUri = properties["github-test-harness.githubUri"]!!

            return runBlocking {
                GitHubTestHarness(
                    localRepo,
                    remoteRepo,
                    configMap,
                    githubUri,
                    extractGitHubInfoFromUri(githubUri)!!,
                    useFakeRemote = useFakeRemote,
                ).apply {
                    try {
                        block()
                    } finally {
                        rollbackRemoteChanges()
                        gitLogLocalAndRemote()
                    }
                }
            }
        }
        const val LOCAL_REPO_SUBDIR = "local"
        const val REMOTE_REPO_SUBDIR = "remote"
        const val INITIAL_COMMIT_SHORT_MESSAGE = "Initial commit"
        val RESTORE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-restore/"
        val DELETE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-delete/"

        val DEFAULT_COMMITTER: Ident = Ident("Frank Grimes", "grimey@springfield.example.com")
        private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)
        private fun File.toStringWithClickableURI(): String = "$this (${toURI().toString().replaceFirst("/", "///")})"

        private fun File.createRepoDirs() = resolve(LOCAL_REPO_SUBDIR) to resolve(REMOTE_REPO_SUBDIR)

        private fun createTempDir() =
            checkNotNull(Files.createTempDirectory(GitHubTestHarness::class.java.simpleName).toFile())
                .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }

        private fun Map<String, String>.getUserConfigFromPropertiesFile(): Map<String, UserConfig> {
            val regex = "github-test-harness\\.userKey\\.(.*?)\\.(.*?)".toRegex()

            data class ConfigFileEntry(val userKey: String, val key: String, val value: String)

            return mapNotNull { (key, value) ->
                    val matchResult = regex.matchEntire(key)
                    val matchValues = matchResult?.groupValues
                    matchValues?.let { (_, userKey, key) -> ConfigFileEntry(userKey, key, value) }
                }
                .groupBy { (userKey) -> userKey }
                .map { (userKey, values) ->
                    val entriesByKey = values.groupBy(ConfigFileEntry::key)
                    val name = requireNotNull(entriesByKey["name"]).single().value
                    val email = requireNotNull(entriesByKey["email"]).single().value
                    val githubToken = requireNotNull(entriesByKey["githubToken"]).single().value
                    userKey to UserConfig(name, email, githubToken)
                }
                .toMap()
        }

    }
}
