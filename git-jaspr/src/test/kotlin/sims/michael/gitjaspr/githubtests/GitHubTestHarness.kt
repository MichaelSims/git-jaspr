package sims.michael.gitjaspr.githubtests

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitjaspr.*
import sims.michael.gitjaspr.Commit
import sims.michael.gitjaspr.Ident
import sims.michael.gitjaspr.PullRequest
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts
import sims.michael.gitjaspr.testing.DEFAULT_COMMITTER
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.RegexOption.IGNORE_CASE

class GitHubTestHarness private constructor(
    val scratchDir: File,
    val localRepo: File,
    val remoteRepo: File,
    val remoteUri: String,
    val remoteName: String,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    private val configByUserKey: Map<String, UserConfig>,
    private val useFakeRemote: Boolean = true,
    private val getPullRequestsPageSize: Int,
) {

    val localGit: GitClient = OptimizedCliGitClient(localRepo)
    val remoteGit: GitClient = OptimizedCliGitClient(remoteRepo)

    private val ghClientsByUserKey: Map<String, GitHubClient> by lazy {
        if (!useFakeRemote) {
            configByUserKey
                .map { (k, v) ->
                    val wiring = GitHubClientWiring(
                        v.githubToken,
                        gitHubInfo,
                        remoteBranchPrefix,
                        getPullRequestsPageSize,
                    )
                    k to wiring.gitHubClient
                }
                .toMap()
        } else {
            emptyMap()
        }
    }

    val gitHub by lazy {
        if (!useFakeRemote) {
            // TODO
            //   At some point I had intended to use the github client just for verifications. Since all of the GH
            //   clients here point to the same project, it shouldn't matter which client I actually use, as long as
            //   I'm not mutating anything. Unfortunately, I *am* mutating with this client (via push and merge).
            //   To make matters worse, it matters which client I use to push... it can't be the same one that I use
            //   to approve the PRs in the tests, otherwise I get "Can not approve your own pull request" from GitHub.
            //   Right now I'm lucky that in my particular config calling `values.first()` grabs the user who isn't
            //   the one approving all the PRs in the tests.
            //   I need to think on how to fix this. Either the harness needs to expose a `gitJasprByUserKey` to the
            //   tests so that the tests can choose which user to push and merge with, or I need to come up with a more
            //   predictable way to choose the token that will be used for pushes and merges in the tests.
            //   Note that the mechanism the external process test uses to land on a github token to use is entirely
            //   different. It basically uses whichever one is in the home directory of the user running the test.
            //   This may be fixable too, but it's less important because the point of the external process test is
            //   to provide a mechanism to update native-image metadata, and not so much to verify behavior.
            ghClientsByUserKey.values.first()
        } else {
            GitHubStubClient(
                remoteBranchPrefix,
                remoteName,
                localGit,
            )
        }
    }

    // Assign unique IDs for the lifetime of this test harness
    private fun uuidIterator() = (0..Int.MAX_VALUE).asSequence().map(Int::toString).iterator()
    private val ids = uuidIterator()

    val gitJaspr = GitJaspr(
        ghClient = gitHub,
        localGit,
        Config(localRepo, remoteName, gitHubInfo, remoteBranchPrefix = remoteBranchPrefix),
        ids::next,
        commitIdentOverride = DEFAULT_COMMITTER,
    )

    init {
        val uriToClone = if (!useFakeRemote) {
            remoteUri
        } else {
            val remoteSource = scratchDir.resolve("remote-source")
            JGitClient(remoteSource).init().createInitialCommit()
            remoteGit.clone(remoteSource.toURI().toString(), remoteName, bare = true)
            remoteRepo.toURI().toString()
        }
        localGit.clone(uriToClone, remoteName)
    }

    suspend fun createCommitsFrom(testCase: TestCaseData) {
        requireNoDuplicatedCommitTitles(testCase)
        requireNoDuplicatedPrTitles(testCase)

        val commitHashesByTitle = localGit.logAll().associate { commit -> commit.shortMessage to commit.hash }

        val initialCommit = localGit.log(DEFAULT_TARGET_REF).last()

        // This saves the state of `main` so it will be restored even if moved by an "external" process
        // This is for functional tests so I can roll them back when done.
        val initialRestoreMarker = "$RESTORE_PREFIX$DEFAULT_TARGET_REF"
        if (!localGit.getBranchNames().contains(initialRestoreMarker)) {
            localGit.branch(initialRestoreMarker, startPoint = initialCommit.hash)
        }

        localGit.checkout(initialCommit.hash) // Go into detached HEAD

        // Inner recursive function, called below
        fun BranchData.createCommits() {
            val iterator = commits.iterator() // Using an iterator to peek ahead at the next element

            @Suppress("GrazieInspection") // IJ being a little too aggressive on the grammar assistance...
            while (iterator.hasNext()) {
                val commitData = iterator.next()

                // Called for JGitClient's sake. If we're fully switched to CliGitClient, this can be removed
                setGitCommitterInfo(commitData.committer.toIdent())

                val existingHash = commitHashesByTitle[commitData.title]
                val commit = if (existingHash != null) {
                    // A commit with this title already exists... if it's a direct child of this commit, simply check
                    // it out. Otherwise cherry-pick it.
                    // Note that we don't support amending commits, so it's not really possible to _change_ a commit
                    // using this test harness.
                    val headHash = localGit.log("HEAD", 1).single().hash
                    val parentHashes = localGit.getParents(localGit.log(existingHash, 1).single()).map(Commit::hash)
                    if (headHash in parentHashes) {
                        localGit.checkout(existingHash)
                        localGit.log(existingHash, maxCount = 1).single()
                    } else {
                        localGit.cherryPick(
                            localGit.log(existingHash, maxCount = 1).single(),
                            commitData.committer.toIdent(),
                        )
                    }
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
                    localGit.push(listOf(RefSpec("${FORCE_PUSH_PREFIX}HEAD", remoteRef)), remoteName)
                }

                if (commitData.branches.isNotEmpty()) {
                    for (childBranch in commitData.branches) {
                        childBranch.createCommits()
                        localGit.checkout(commit.hash)
                    }
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

        val isDraftRegex = "^(draft|wip)\\b.*$".toRegex(IGNORE_CASE)
        val prs = testCase.pullRequests
        if (prs.isNotEmpty()) {
            val existingPrsByTitle = gitHub.getPullRequestsById().associateBy(PullRequest::title)
            val commitsByTitle = testCase.repository.collectAllCommits().associateBy(CommitData::title)
            for (pr in prs) {
                val gitHubClient = (ghClientsByUserKey[pr.userKey] ?: gitHub)
                val newPullRequest = PullRequest(
                    id = null,
                    commitId = getRemoteRefParts(pr.headRef, remoteBranchPrefix)?.commitId,
                    number = null,
                    headRefName = pr.headRef,
                    baseRefName = pr.baseRef,
                    title = pr.title,
                    body = pr.body,
                    // This logic is incomplete. In this context, we could have PRs with multiple commits. If we want
                    // to support this so we can test how JASPR reacts, this logic needs to be updated to set
                    // checksPass only if _all_ commits in the PR will pass. It's unlikely that I'll make this change
                    // but I'll leave this comment here
                    checksPass = commitsByTitle[pr.title]?.willPassVerification,
                    approved = pr.willBeApprovedByUserKey?.isNotBlank(),
                    permalink = "http://example.com",
                    isDraft = isDraftRegex.matches(pr.title),
                )
                val existingPr = existingPrsByTitle[pr.title]
                val createdOrUpdatedPr = if (existingPr == null) {
                    gitHubClient.createPullRequest(newPullRequest)
                } else {
                    newPullRequest
                        .copy(id = existingPr.id)
                        .also { gitHubClient.updatePullRequest(it) }
                }
                if (!useFakeRemote && pr.willBeApprovedByUserKey?.isNotBlank() == true) {
                    ghClientsByUserKey[pr.willBeApprovedByUserKey]?.approvePullRequest(createdOrUpdatedPr)
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
                RefSpec(it.groupValues[0], it.groupValues[1])
            }

        // This currently deletes all "jaspr/" branches indiscriminately. Much better would be to capture the ones
        // we created via our JGitClient and delete only those
        val deleteRegex =
            "($DELETE_PREFIX(.*)|$DEFAULT_REMOTE_NAME/($remoteBranchPrefix.*))"
                .toRegex()

        val toDelete = localGit.getBranchNames()
            .mapNotNull { name -> deleteRegex.matchEntire(name) }
            .map { result ->
                RefSpec(
                    "", // Will force push below
                    result.groupValues.last(String::isNotBlank),
                )
            }
        val refSpecs = (toRestore + toDelete).distinct().map(RefSpec::forcePush)
        logger.debug("Pushing {}", refSpecs)
        localGit.push(refSpecs, remoteName)
        fun deleteBranches() = localGit.deleteBranches(
            names = toRestore.map(RefSpec::localRef) + toDelete.map(RefSpec::remoteRef),
            force = true,
        )
        if (localGit.getCurrentBranchName() in toDelete.map(RefSpec::remoteRef)) {
            try {
                localGit.checkout(localGit.log("HEAD", 1).single().hash)
                deleteBranches()
            } finally {
                localGit.checkout("main")
            }
        } else {
            deleteBranches()
        }
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

    private fun GitClient.createInitialCommit(): Commit {
        val repoDir = workingDirectory
        val readme = "README.txt"
        val readmeFile = repoDir.resolve(readme)
        readmeFile.writeText("This is a test repo.\n")
        return add(readme).commit(INITIAL_COMMIT_SHORT_MESSAGE, commitIdent = DEFAULT_COMMITTER)
    }

    private fun CommitData.create(): Commit {
        val file = localRepo.resolve("${title.sanitize()}.txt")
        file.writeText("Title: $title\n")
        val safeId = id
        val safeWillPassVerification = willPassVerification
        return localGit
            .add(file.name)
            .commit(
                message = if (body.isNotEmpty()) {
                    title.trim() + "\n\n" + body.trim() + "\n"
                } else {
                    title
                },
                footerLines = buildMap {
                    putAll(footerLines)
                    if (safeId == null || safeId.isNotBlank()) {
                        put(
                            COMMIT_ID_LABEL,
                            safeId ?: title.also {
                                require(!it.contains("\\s+".toRegex())) {
                                    "ID wasn't provided and title '$it' can\'t be used as it contains whitespace."
                                }
                            },
                        )
                    }
                    if (safeWillPassVerification != null) {
                        put("verify-result", if (safeWillPassVerification) "0" else "13")
                    }
                },
                commitIdent = committer.toIdent(),
            )
    }

    private fun IdentData.toIdent(): Ident =
        Ident(name, email).takeUnless { it.name.isBlank() || it.email.isBlank() } ?: DEFAULT_COMMITTER

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
            rollBackChanges: Boolean = true,
            configPropertiesFile: File = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME),
            remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
            remoteName: String = DEFAULT_REMOTE_NAME,
            getPullRequestsPageSize: Int = GitHubClient.GET_PULL_REQUESTS_DEFAULT_PAGE_SIZE,
            block: suspend GitHubTestHarness.() -> Unit,
        ): GitHubTestHarness {
            val scratchDir = createTempDir()
            val (localRepo, remoteRepo) = scratchDir.createRepoDirs()

            val properties = Properties()
                .apply {
                    if (configPropertiesFile.exists()) {
                        configPropertiesFile.inputStream().use(::load)
                    } else {
                        logger.info("No {} found.", configPropertiesFile) // Expected in CI/CD
                    }
                }
                .map { (k, v) -> k.toString() to v.toString() }
                .toMap()

            val configByUserKey = properties.getConfigByUserKeyFromPropertiesFile()

            val githubUri = properties["$PROPERTIES_PREFIX.$PROPERTY_GITHUB_URI"]
            val gitHubInfo = if (githubUri != null && !useFakeRemote) {
                requireNotNull(extractGitHubInfoFromUri(githubUri)) { "Unable to extract GitHubInfo from $githubUri" }
            } else {
                GitHubInfo("example.com", "SomeOwner", "SomeRepo")
            }

            return runBlocking {
                val testHarness = GitHubTestHarness(
                    scratchDir,
                    localRepo,
                    remoteRepo,
                    remoteUri = githubUri.orEmpty(),
                    remoteName,
                    gitHubInfo,
                    remoteBranchPrefix,
                    configByUserKey,
                    useFakeRemote,
                    getPullRequestsPageSize,
                )
                testHarness.apply {
                    try {
                        block()
                    } finally {
                        if (rollBackChanges) {
                            rollbackRemoteChanges()
                        }
                        gitLogLocalAndRemote()
                    }
                }
            }
        }

        private val logger = LoggerFactory.getLogger(GitHubTestHarness::class.java)

        const val INITIAL_COMMIT_SHORT_MESSAGE = "Initial commit"

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

        // Use a default remote name that is something other than "origin". Previously there was lots of code that made
        // assumptions about the remote name being "origin" or didn't parameterize it at all. I want to make sure I
        // don't repeat that mistake, so we'll use something other than "origin" by default.
        private const val DEFAULT_REMOTE_NAME = "git-hub-test-harness-remote"

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

private fun BranchData.collectAllCommits(): List<CommitData> {
    return commits + commits.flatMap { commit -> commit.branches.flatMap { it.collectAllCommits() } }
}
