package sims.michael.gitjaspr

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime.ofInstant
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_MERGE
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REMOTE
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.URIish
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef
import sims.michael.gitjaspr.RetryWithBackoff.retryWithBackoff

/**
 * In-process accessor for a local `git` repository, backed by JGit. Despite the name, this is
 * intentionally not a [GitClient]: it is the read-heavy and local-mutation accelerator that
 * [DefaultGitClient] delegates to, running those methods in-process instead of forking a `git`
 * process per call.
 */
class JGitClient(
    val workingDirectory: File,
    val remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
) {
    private val logger = LoggerFactory.getLogger(JGitClient::class.java)

    fun init(): JGitClient {
        logger.trace("init")
        return apply {
            Git.init().setDirectory(workingDirectory).setInitialBranch("main").call().close()
        }
    }

    fun log(): List<Commit> {
        logger.trace("log")
        return useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }
    }

    fun log(revision: String, maxCount: Int = -1): List<Commit> = useGit { git ->
        logger.trace("log {} {}", revision, maxCount)
        git.log().add(git.repository.resolve(revision)).setMaxCount(maxCount).call().toList().map {
            revCommit ->
            revCommit.toCommit(git)
        }
    }

    fun logAll(): List<Commit> {
        logger.trace("logAll")
        return useGit { git -> git.log().all().call().map { it.toCommit(git) }.reversed() }
    }

    fun getParents(commit: Commit): List<Commit> = useGit { git ->
        logger.trace("getParents {}", commit)
        git.log()
            .add(git.repository.resolve(commit.hash))
            .setMaxCount(1)
            .call()
            .single()
            .parents
            .map { it.toCommit(git) }
    }

    fun logRange(since: String, until: String): List<Commit> = useGit { git ->
        logger.trace("logRange {}..{}", since, until)
        val r = git.repository
        val sinceObjectId =
            checkNotNull(r.resolve(since)) { "logRange $since..$until: $since doesn't exist" }
        val untilObjectId =
            checkNotNull(r.resolve(until)) { "logRange $since..$until: $until doesn't exist" }
        val commits = git.log().addRange(sinceObjectId, untilObjectId).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    fun getCommitIdsInRange(
        target: String,
        refs: List<String>,
    ): Map<String, List<String>> = useGit { git ->
        logger.trace("getCommitIdsInRange {} {}", target, refs)
        val r = git.repository
        val targetId =
            checkNotNull(r.resolve(target)) {
                "getCommitIdsInRange: target $target doesn't exist"
            }
        refs.associateWith { ref ->
            val refId =
                checkNotNull(r.resolve(ref)) { "getCommitIdsInRange: ref $ref doesn't exist" }
            git.log().addRange(targetId, refId).call().mapNotNull { revCommit ->
                CommitParsers.getFooters(revCommit.fullMessage)[COMMIT_ID_LABEL]
            }
        }
    }

    fun hasUncommittedChangesToTrackedFiles(): Boolean {
        logger.trace("hasUncommittedChangesToTrackedFiles")
        return useGit { git ->
            val call = git.status().call()
            call.run { added + changed + removed + modified + missing + conflicting }.isNotEmpty()
        }
    }

    fun getCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit> {
        logger.trace("getCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return useGit { git ->
            val r = git.repository
            val trackingBranch =
                r.resolve("$remoteName/$targetRefName")
                    ?: throw GitJasprException(
                        "Target branch '$targetRefName' was not found at " +
                            "'$remoteName/$targetRefName'. It may have been merged and deleted; " +
                            "run `git fetch --prune` to update your local view."
                    )
            val revCommits =
                git.log().addRange(trackingBranch, r.resolve(localObjectName)).call().toList()
            val mergeCommits = revCommits.filter { it.parentCount > 1 }
            val objectReader = r.newObjectReader()
            require(mergeCommits.isEmpty()) {
                "Merge commits are not supported ${mergeCommits.map { objectReader.abbreviate(it.id).name() }}"
            }
            revCommits.map { revCommit -> revCommit.toCommit(git) }.reversed()
        }
    }

    fun refExists(ref: String): Boolean {
        logger.trace("refExists {}", ref)
        return useGit { git -> git.repository.resolve(ref) != null }
    }

    fun getBranchNames(): List<String> {
        logger.trace("getBranchNames")
        return useGit { git ->
            git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().map {
                it.name.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_REMOTES)
            }
        }
    }

    fun getRemoteBranches(remoteName: String = DEFAULT_REMOTE_NAME): List<RemoteBranch> {
        logger.trace("getRemoteBranches")
        return useGit { git ->
            git.branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .filter { it.name.startsWith(Constants.R_REMOTES) }
                .filterNot { ref -> ref.name == Constants.R_REMOTES + "$remoteName/HEAD" }
                .mapNotNull { ref ->
                    val r = git.repository
                    val (thisRemoteName, shortBranchName) =
                        ref.name.removePrefix(Constants.R_REMOTES).split("/", limit = 2)
                    if (thisRemoteName == remoteName) {
                        RemoteBranch(shortBranchName, r.parseCommit(ref.objectId).toCommit(git))
                    } else {
                        null
                    }
                }
        }
    }

    fun getRemoteBranchesById(remoteName: String = DEFAULT_REMOTE_NAME): Map<String, RemoteBranch> {
        logger.trace("getRemoteBranchesById")
        return getRemoteBranches(remoteName)
            .mapNotNull { branch ->
                RemoteRef.parse(branch.name, remoteBranchPrefix)
                    ?.takeIf { parts -> parts.revisionNum == null } // Filter history branches
                    ?.let { it.commitId to branch }
            }
            .toMap()
    }

    fun cleanUntracked() = apply {
        logger.trace("cleanUntracked")
        useGit { git -> git.clean().setCleanDirectories(true).setForce(true).call() }
    }

    fun deleteBranches(names: List<String>, force: Boolean = false): List<String> {
        logger.trace("deleteBranches {} {}", names, force)
        return useGit { git ->
            git.branchDelete().setBranchNames(*names.toTypedArray()).setForce(force).call()
        }
    }

    fun add(filePattern: String): JGitClient {
        logger.trace("add {}", filePattern)
        return apply { useGit { git -> git.add().addFilepattern(filePattern).call() } }
    }

    fun setCommitId(commitId: String, committer: Ident? = null, author: Ident? = null) {
        logger.trace("setCommitId {} {} {}", commitId, committer, author)
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(GitClient.HEAD).objectId)
            require(!CommitParsers.getFooters(head.fullMessage).containsKey(COMMIT_ID_LABEL))
            val amendCommand =
                git.commit()
                    .setAmend(true)
                    .setMessage(
                        CommitParsers.addFooters(
                            head.fullMessage,
                            mapOf(COMMIT_ID_LABEL to commitId),
                        )
                    )

            if (committer != null) {
                amendCommand.setCommitter(PersonIdent(committer.name, committer.email))
            }
            if (author != null) {
                amendCommand.setAuthor(PersonIdent(author.name, author.email))
            }

            amendCommand.call()
        }
    }

    fun commit(
        message: String? = null,
        footerLines: Map<String, String>? = null,
        committer: Ident? = null,
        author: Ident? = null,
        amend: Boolean = false,
        reflogMessage: String? = null,
    ): Commit {
        logger.trace("commit {} {} {} {} {}", message, footerLines, committer, author, amend)

        require(amend || message != null) { "message is required when not amending" }

        return useGit { git ->
            fun createCommitCommand(): CommitCommand {
                // Allow empty so commits with no tree changes (e.g. --allow-empty) don't get
                // rejected
                val commitCommand = git.commit().setAmend(amend).setAllowEmpty(true)
                if (reflogMessage != null) {
                    commitCommand.setReflogComment(reflogMessage)
                }
                if (message != null || footerLines != null) {
                    val existingFullMessage: String?
                    val existingFooterLines: Map<String, String>?
                    if (amend) {
                        val r = git.repository
                        val head = r.parseCommit(r.findRef(GitClient.HEAD).objectId)
                        existingFooterLines = CommitParsers.getFooters(head.fullMessage)
                        existingFullMessage = head.fullMessage
                    } else {
                        existingFooterLines = null
                        existingFullMessage = null
                    }
                    val footers = footerLines ?: existingFooterLines ?: emptyMap()
                    val newMessage =
                        message ?: CommitParsers.trimFooters(checkNotNull(existingFullMessage))
                    commitCommand.setMessage(CommitParsers.addFooters(newMessage, footers))
                }

                if (committer != null) {
                    val committerPersonIdent = committer.toPersonIdent()
                    commitCommand.setCommitter(committerPersonIdent)
                    if (author == null && !amend) {
                        // If only the committer is set, use it as the author as well. This matches
                        // JGit's behavior (but only for new commits (i.e., amend == false))
                        commitCommand.setAuthor(committerPersonIdent)
                    }
                }
                if (author != null) {
                    commitCommand.setAuthor(PersonIdent(author.name, author.email))
                }
                if (amend && message == null && footerLines == null) {
                    // Read the existing message and explicitly set it, otherwise JGit will complain
                    val r = git.repository
                    val head = r.parseCommit(r.findRef(GitClient.HEAD).objectId)
                    commitCommand.setMessage(head.fullMessage)
                }
                return commitCommand
            }

            // Retry a few times if we're amending. From tests if we create a test commit and amend
            // it within the same second, JGit throws an exception since the commit object didn't
            // change. If we retry a couple of times, enough time will pass that the commit date
            // will bump.
            fun shouldRetry(e: Exception) = e.message.orEmpty().contains(NO_CHANGE.name)
            val result =
                retryWithBackoff(logger, shouldRetry = ::shouldRetry) {
                    createCommitCommand().call()
                }
            result.toCommit(git)
        }
    }

    fun getRemoteUriOrNull(remoteName: String): String? {
        // Intentionally avoiding trace logging here. See comment in CliGitClient.getRemoteUriOrNull
        return useGit { git ->
            git.remoteList()
                .call()
                .singleOrNull { it.name == remoteName }
                ?.urIs
                ?.firstOrNull()
                ?.toASCIIString()
        }
    }

    fun addRemote(remoteName: String, remoteUri: String) {
        logger.trace("addRemote {} {}", remoteName, remoteUri)
        useGit { git -> git.remoteAdd().setName(remoteName).setUri(URIish(remoteUri)).call() }
    }

    fun getConfigValue(key: String): String? {
        logger.trace("getConfigValue {}", key)
        return useGit { git ->
            git.repository.config.getString(
                key.substringBeforeLast('.'),
                null,
                key.substringAfterLast('.'),
            )
        }
    }

    fun setConfigValue(key: String, value: String) {
        logger.trace("setConfigValue {} {}", key, value)
        useGit { git ->
            val config = git.repository.config
            config.setString(key.substringBeforeLast('.'), null, key.substringAfterLast('.'), value)

            config.save()
        }
    }

    fun getUpstreamBranch(remoteName: String): RemoteBranch? = useGit { git ->
        val prefix = "${Constants.R_REMOTES}$remoteName/"
        val repository = git.repository
        BranchConfig(repository.config, repository.branch)
            .trackingBranch
            ?.takeIf { name -> name.startsWith(prefix) }
            ?.let { trackingBranchName ->
                val trackingBranchSimpleName = trackingBranchName.removePrefix(prefix)
                getRemoteBranches(remoteName).firstOrNull { branch ->
                    branch.name == trackingBranchSimpleName
                }
            }
    }

    fun setUpstreamBranch(remoteName: String, branchName: String) {
        logger.trace("setUpstreamBranch {} {}", remoteName, branchName)
        check(!isHeadDetached()) { "Cannot set upstream branch when in detached HEAD" }
        require(getRemoteBranches(remoteName).map(RemoteBranch::name).contains(branchName)) {
            "Remote $remoteName does not contain branch $branchName"
        }
        useGit { git ->
            val r = git.repository
            val config = r.config
            val currentBranch = r.branch
            with(config) {
                setString(CONFIG_BRANCH_SECTION, currentBranch, CONFIG_KEY_REMOTE, remoteName)
                setString(
                    CONFIG_BRANCH_SECTION,
                    currentBranch,
                    CONFIG_KEY_MERGE,
                    "${Constants.R_HEADS}$branchName",
                )
                save()
            }
        }
    }

    fun getUpstreamBranchName(localBranch: String, remoteName: String): String? {
        logger.trace("getUpstreamBranchName {} {}", localBranch, remoteName)
        return useGit { git ->
            val config = git.repository.config
            val remote = config.getString(CONFIG_BRANCH_SECTION, localBranch, CONFIG_KEY_REMOTE)
            val merge = config.getString(CONFIG_BRANCH_SECTION, localBranch, CONFIG_KEY_MERGE)
            if (remote == remoteName && merge != null) {
                merge.removePrefix(Constants.R_HEADS)
            } else {
                null
            }
        }
    }

    fun setUpstreamBranchForLocalBranch(
        localBranch: String,
        remoteName: String,
        remoteBranchName: String?,
    ) {
        logger.trace(
            "setUpstreamBranchForLocalBranch {} {} {}",
            localBranch,
            remoteName,
            remoteBranchName,
        )
        useGit { git ->
            val config = git.repository.config
            if (remoteBranchName != null) {
                config.setString(CONFIG_BRANCH_SECTION, localBranch, CONFIG_KEY_REMOTE, remoteName)
                config.setString(
                    CONFIG_BRANCH_SECTION,
                    localBranch,
                    CONFIG_KEY_MERGE,
                    "${Constants.R_HEADS}$remoteBranchName",
                )
            } else {
                config.unset(CONFIG_BRANCH_SECTION, localBranch, CONFIG_KEY_REMOTE)
                config.unset(CONFIG_BRANCH_SECTION, localBranch, CONFIG_KEY_MERGE)
            }
            config.save()
        }
    }

    fun reflog(): List<Commit> {
        logger.trace("reflog")
        return useGit { git ->
            val reader = git.repository.newObjectReader()
            git.reflog().call().flatMap { entry -> log(reader.abbreviate(entry.newId).name(), 1) }
        }
    }

    fun getCurrentBranchName(): String {
        logger.trace("getCurrentBranchName")
        return useGit { git -> git.repository.branch }
    }

    fun isHeadDetached(): Boolean {
        logger.trace("isHeadDetached")
        return useGit { git -> !git.repository.exactRef(Constants.HEAD).isSymbolic }
    }

    fun getShortMessages(refs: List<String>): Map<String, String?> {
        logger.trace("getShortMessages {}", refs)
        return useGit { git ->
            val repo = git.repository
            refs.associateWith { ref ->
                repo.resolve(ref)?.let { objectId -> repo.parseCommit(objectId).shortMessage }
            }
        }
    }

    fun getCommits(refs: List<String>): Map<String, Commit?> {
        logger.trace("getCommits {}", refs)
        return useGit { git ->
            val repo = git.repository
            refs.associateWith { ref ->
                repo.resolve(ref)?.let { objectId -> repo.parseCommit(objectId).toCommit(git) }
            }
        }
    }

    fun mergeBase(a: String, b: String): String? {
        logger.trace("mergeBase {} {}", a, b)
        return useGit { git ->
            val repo = git.repository
            val aId = repo.resolve(a) ?: return@useGit null
            val bId = repo.resolve(b) ?: return@useGit null
            RevWalk(repo).use { walk ->
                walk.revFilter = RevFilter.MERGE_BASE
                walk.markStart(walk.parseCommit(aId))
                walk.markStart(walk.parseCommit(bId))
                walk.next()?.name
            }
        }
    }

    fun isAncestor(ancestor: String, descendant: String): Boolean {
        logger.trace("isAncestor {} {}", ancestor, descendant)
        return useGit { git ->
            val repo = git.repository
            val ancestorId = repo.resolve(ancestor) ?: return@useGit false
            val descendantId = repo.resolve(descendant) ?: return@useGit false
            RevWalk(repo).use { walk ->
                walk.isMergedInto(walk.parseCommit(ancestorId), walk.parseCommit(descendantId))
            }
        }
    }

    fun gitDir(): File = useGit { git -> git.repository.directory.canonicalFile }

    fun gitCommonDir(): File = useGit { git ->
        git.repository.commonDirectory.canonicalFile
    }

    fun cherryPickAbort() {
        logger.trace("cherryPickAbort")
        useGit { git ->
            val r = git.repository
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            r.writeCherryPickHead(null)
            r.writeMergeCommitMsg(null)
        }
    }

    fun getTree(ref: String): String {
        logger.trace("getTree {}", ref)
        return useGit { git ->
            val objectId =
                checkNotNull(git.repository.resolve("$ref^{tree}")) {
                    "Cannot resolve tree of $ref"
                }
            objectId.name()
        }
    }

    fun updateRef(refName: String, sha: String) {
        logger.trace("updateRef {} {}", refName, sha)
        useGit { git ->
            val repo = git.repository
            val objectId =
                checkNotNull(repo.resolve(sha)) { "Cannot resolve $sha when updating $refName" }
            val refUpdate = repo.updateRef(refName)
            refUpdate.setNewObjectId(objectId)
            refUpdate.isForceUpdate = true
            val result = refUpdate.update()
            check(result in REF_UPDATE_SUCCESS_RESULTS) {
                "Failed to update $refName to $sha: $result"
            }
        }
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    companion object {
        private val REF_UPDATE_SUCCESS_RESULTS =
            setOf(
                RefUpdate.Result.NEW,
                RefUpdate.Result.FAST_FORWARD,
                RefUpdate.Result.FORCED,
                NO_CHANGE,
            )
    }
}

private fun RevCommit.toCommit(git: Git): Commit {
    val r = git.repository
    val objectReader = r.newObjectReader()
    fun PersonIdent.whenAsZonedDateTime() =
        ofInstant(whenAsInstant, ZoneId.systemDefault()).canonicalize()
    return Commit(
        objectReader.abbreviate(id).name(),
        shortMessage,
        fullMessage,
        CommitParsers.getFooters(fullMessage)[COMMIT_ID_LABEL],
        Ident(authorIdent.name, authorIdent.emailAddress),
        Ident(committerIdent.name, committerIdent.emailAddress),
        committerIdent.whenAsZonedDateTime(),
        authorIdent.whenAsZonedDateTime(),
    )
}
