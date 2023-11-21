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
import sims.michael.gitkspr.PullRequest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class GitHubTestHarness private constructor(
    val localRepo: File,
    val remoteRepo: File,
    private val remoteUri: String,
    private val gitHubInfo: GitHubInfo,
    val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    private val configByUserKey: Map<String, UserConfig>,
    private val useFakeRemote: Boolean = true,
) {

    val localGit: JGitClient = JGitClient(localRepo)
    val remoteGit: JGitClient = JGitClient(remoteRepo)

    private val ghClientsByUserKey: Map<String, GitHubClient> by lazy {
        if (!useFakeRemote) {
            configByUserKey
                .map { (k, v) -> k to GitHubClientWiring(v.githubToken, gitHubInfo, remoteBranchPrefix).gitHubClient }
                .toMap()
        } else {
            emptyMap()
        }
    }

    val gitHub by lazy {
        if (!useFakeRemote) {
            // These all point to the same project. Since this is mainly used for verifications in tests (i.e. it will
            // mostly just be getting the PRs and not mutating anything), it doesn't matter which we use so we'll just
            // grab the first.
            ghClientsByUserKey.values.first()
        } else {
            GitHubStubClient(remoteBranchPrefix)
        }
    }

    // Assign unique IDs for the lifetime of this test harness
    private fun uuidIterator() = (0..Int.MAX_VALUE).asSequence().map(Int::toString).iterator()
    private val ids = uuidIterator()

    val gitKspr = GitKspr(
        ghClient = gitHub,
        localGit,
        Config(localRepo, DEFAULT_REMOTE_NAME, gitHubInfo, remoteBranchPrefix = remoteBranchPrefix),
        ids::next,
    )

    init {
        val uriToClone = if (!useFakeRemote) {
            remoteUri
        } else {
            remoteGit.init().createInitialCommit()
            remoteRepo.toURI().toString()
        }
        localGit.clone(uriToClone)
    }

    suspend fun createCommitsFrom(testCase: TestCaseData) {
        requireNoDuplicatedCommitTitles(testCase)
        requireNoDuplicatedPrTitles(testCase)

        val commitHashesByTitle = localGit.logAll().associate { commit -> commit.shortMessage to commit.hash }

        val initialCommit = localGit.log(DEFAULT_TARGET_REF).last()
        localGit.checkout(initialCommit.hash) // Go into detached HEAD

        // Inner recursive function, called below
        fun BranchData.createCommits() {
            val iterator = commits.iterator() // Using an iterator to peek ahead at the next element

            @Suppress("GrazieInspection") // IJ being a little too aggressive on the grammar assistance...
            while (iterator.hasNext()) {
                val commitData = iterator.next()

                setGitCommitterInfo(commitData.committer.toIdent())

                val existingHash = commitHashesByTitle[commitData.title]
                val commit = if (existingHash != null) {
                    // A commit with this title already exists... cherry-pick it
                    // TODO but what if this version of the commit differs? shouldn't we amend it?
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

                    val rollbackDeleteMarker = "$DELETE_PREFIX$localRef"
                    val rollbackRestoreMarker = "$RESTORE_PREFIX$localRef"

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
                        childBranch.createCommits()
                    }
                    localGit.checkout(commit.hash)
                }
            }
        }

        testCase.repository.createCommits()
        if (testCase.localWillBeDirty) {
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
            val existingPrsByTitle = gitHub.getPullRequestsById().associateBy(PullRequest::title)
            for (pr in prs) {
                val gitHubClient = (ghClientsByUserKey[pr.userKey] ?: gitHub)
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

    fun gitLogLocalAndRemote() {
        gitLogGraphAll(localRepo, "LOCAL")
        if (useFakeRemote) {
            gitLogGraphAll(remoteRepo, "REMOTE")
        }
    }

    fun rollbackRemoteChanges() {
        logger.trace("rollbackRemoteChanges")
        val restoreRegex = "$RESTORE_PREFIX(.*?)".toRegex()
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
            "($DELETE_PREFIX(.*)|${JGitClient.R_REMOTES}$DEFAULT_REMOTE_NAME/($remoteBranchPrefix.*))"
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

    private val canRunGit = AtomicBoolean(true)

    private fun gitLogGraphAll(repo: File, label: String) {
        if (canRunGit.get()) {
            val divider = "----------"
            logger.trace(divider)
            try {
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
            } catch (e: IOException) {
                logger.error("Couldn't run git log, whatsa matta, you don't have git installed!?")
                canRunGit.set(false)
            }
            logger.trace(divider)
        }
    }

    private fun JGitClient.createInitialCommit() = apply {
        val repoDir = workingDirectory
        val readme = "README.txt"
        val readmeFile = repoDir.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        add(readme).commit(INITIAL_COMMIT_SHORT_MESSAGE)
    }

    private fun CommitData.create(): Commit {
        val file = localRepo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        // Use the title as the commit ID if ID wasn't provided
        val commitId = if (id != null) {
            id
        } else {
            require(!title.contains("\\s+".toRegex())) {
                "ID wasn't provided and title '$title' can\'t be used as it contains whitespace."
            }
            title
        }
        val message = localGit.appendCommitId(title, commitId)
        return localGit.add(file.name).commit(message, footerLines)
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
                "The following were duplicated: $duplicatedTitles\n" +
                "--------- \n" +
                "PLEASE NOTE: If you wish to have the same commit ID/title at different places in the local and " +
                "remote, make two calls to createCommitsFrom, with the second updating localRefs without updating " +
                "remoteRefs\n" +
                "--------- \n"
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

    private data class UserConfig(val name: String, val email: String, val githubToken: String)

    companion object {
        fun withTestSetup(
            useFakeRemote: Boolean = true,
            configPropertiesFile: File = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME),
            block: suspend GitHubTestHarness.() -> Unit,
        ): GitHubTestHarness {
            val (localRepo, remoteRepo) = createTempDir().createRepoDirs()

            val properties = Properties()
                .apply { configPropertiesFile.inputStream().use(::load) }
                .map { (k, v) -> k.toString() to v.toString() }
                .toMap()

            val configByUserKey = properties.getConfigByUserKeyFromPropertiesFile()

            val githubUri = requireNotNull(properties["$PROPERTIES_PREFIX.$PROPERTY_GITHUB_URI"]) {
                "$PROPERTY_GITHUB_URI not defined in $configPropertiesFile"
            }

            return runBlocking {
                val testHarness = GitHubTestHarness(
                    localRepo,
                    remoteRepo,
                    remoteUri = githubUri,
                    gitHubInfo = requireNotNull(extractGitHubInfoFromUri(githubUri)) {
                        "Couldn't extract github info from $githubUri defined in $configPropertiesFile"
                    },
                    DEFAULT_REMOTE_BRANCH_PREFIX,
                    configByUserKey,
                    useFakeRemote,
                )
                testHarness.apply {
                    try {
                        block()
                    } finally {
                        rollbackRemoteChanges()
                        gitLogLocalAndRemote()
                    }
                }
            }
        }

        private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

        const val INITIAL_COMMIT_SHORT_MESSAGE = "Initial commit"
        val DEFAULT_COMMITTER: Ident = Ident("Frank Grimes", "grimey@springfield.example.com")

        private const val LOCAL_REPO_SUBDIR = "local"
        private const val REMOTE_REPO_SUBDIR = "remote"
        private const val PROPERTIES_PREFIX = "github-test-harness"
        private const val PROPERTIES_USER_KEY_PREFIX = "userKey"
        private const val PROPERTY_GITHUB_URI = "githubUri"
        private const val PROPERTY_NAME = "name"
        private const val PROPERTY_EMAIL = "email"
        private const val PROPERTY_GITHUB_TOKEN = "githubToken"
        private val RESTORE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-restore/"
        private val DELETE_PREFIX = "${GitHubTestHarness::class.java.simpleName.lowercase()}-delete/"

        private fun File.toStringWithClickableURI(): String = "$this (${toURI().toString().replaceFirst("/", "///")})"
        private fun File.createRepoDirs() = resolve(LOCAL_REPO_SUBDIR) to resolve(REMOTE_REPO_SUBDIR)

        private fun createTempDir() =
            checkNotNull(Files.createTempDirectory(GitHubTestHarness::class.java.simpleName).toFile())
                .also { logger.info("Temp dir created in {}", it.toStringWithClickableURI()) }

        private fun Map<String, String>.getConfigByUserKeyFromPropertiesFile(): Map<String, UserConfig> {
            val regex = "$PROPERTIES_PREFIX\\.$PROPERTIES_USER_KEY_PREFIX\\.(.*?)\\.(.*?)".toRegex()

            data class ConfigFileEntry(val userKey: String, val key: String, val value: String)

            return mapNotNull { (key, value) ->
                val matchResult = regex.matchEntire(key)
                val matchValues = matchResult?.groupValues
                matchValues?.let { (_, userKey, key) -> ConfigFileEntry(userKey, key, value) }
            }
                .groupBy { (userKey) -> userKey }
                .map { (userKey, values) ->
                    val entriesByKey = values.groupBy(ConfigFileEntry::key)
                    val name = requireNotNull(entriesByKey[PROPERTY_NAME]).single().value
                    val email = requireNotNull(entriesByKey[PROPERTY_EMAIL]).single().value
                    val githubToken = requireNotNull(entriesByKey[PROPERTY_GITHUB_TOKEN]).single().value
                    userKey to UserConfig(name, email, githubToken)
                }
                .toMap()
        }
    }
}
