package sims.michael.gitjaspr

import com.jcraft.jsch.AgentIdentityRepository
import com.jcraft.jsch.IdentityRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SSHAgentConnector
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime.ofInstant
import org.eclipse.jgit.api.CheckoutResult
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_MERGE
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REMOTE
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef
import sims.michael.gitjaspr.RetryWithBackoff.retryWithBackoff

class JGitClient(
    override val workingDirectory: File,
    override val remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
) : GitClient {
    private val logger = LoggerFactory.getLogger(JGitClient::class.java)

    override fun init(): GitClient {
        logger.trace("init")
        return apply {
            Git.init().setDirectory(workingDirectory).setInitialBranch("main").call().close()
        }
    }

    override fun checkout(refName: String) = apply {
        logger.trace("checkout {}", refName)
        useGit { git ->
            val refExists = refExists(refName)
            require(refExists) { "$refName does not exist" }
            git.checkout().setName(refName).run {
                call()
                check(result.status == CheckoutResult.Status.OK) {
                    "Checkout result was ${result.status}"
                }
            }
        }
    }

    override fun clone(uri: String, remoteName: String, bare: Boolean): GitClient {
        logger.trace("clone {}", uri)
        return apply {
            Git.cloneRepository()
                .setDirectory(workingDirectory)
                .setURI(uri)
                .setBare(bare)
                .setRemote(remoteName)
                .call()
                .close()
        }
    }

    override fun fetch(remoteName: String, prune: Boolean) {
        logger.trace("fetch {}{}", remoteName, if (prune) " (with prune)" else "")
        try {
            useGit { git -> git.fetch().setRemote(remoteName).setRemoveDeletedRefs(prune).call() }
        } catch (e: TransportException) {
            throw GitJasprException(
                "Failed to fetch from $remoteName; consider enabling the CLI git client",
                e,
            )
        }
    }

    override fun log(): List<Commit> {
        logger.trace("log")
        return useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }
    }

    override fun log(revision: String, maxCount: Int): List<Commit> = useGit { git ->
        logger.trace("log {} {}", revision, maxCount)
        git.log().add(git.repository.resolve(revision)).setMaxCount(maxCount).call().toList().map {
            revCommit ->
            revCommit.toCommit(git)
        }
    }

    override fun logAll(): List<Commit> {
        logger.trace("logAll")
        return useGit { git -> git.log().all().call().map { it.toCommit(git) }.reversed() }
    }

    override fun getParents(commit: Commit): List<Commit> = useGit { git ->
        logger.trace("getParents {}", commit)
        git.log()
            .add(git.repository.resolve(commit.hash))
            .setMaxCount(1)
            .call()
            .single()
            .parents
            .map { it.toCommit(git) }
    }

    override fun logRange(since: String, until: String): List<Commit> = useGit { git ->
        logger.trace("logRange {}..{}", since, until)
        val r = git.repository
        val sinceObjectId =
            checkNotNull(r.resolve(since)) { "logRange $since..$until: $since doesn't exist" }
        val untilObjectId =
            checkNotNull(r.resolve(until)) { "logRange $since..$until: $until doesn't exist" }
        val commits = git.log().addRange(sinceObjectId, untilObjectId).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    override fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return useGit { git -> git.status().call().isClean }
    }

    override fun getLocalCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit> {
        logger.trace("getLocalCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return useGit { git ->
            val r = git.repository
            val trackingBranch =
                requireNotNull(r.resolve("$remoteName/$targetRefName")) {
                    "$targetRefName does not exist in the remote"
                }
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

    override fun refExists(ref: String): Boolean {
        logger.trace("refExists {}", ref)
        return useGit { git -> git.repository.resolve(ref) != null }
    }

    override fun getBranchNames(): List<String> {
        logger.trace("getBranchNames")
        return useGit { git ->
            git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().map {
                it.name.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_REMOTES)
            }
        }
    }

    override fun getRemoteBranches(remoteName: String): List<RemoteBranch> {
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

    override fun getRemoteBranchesById(remoteName: String): Map<String, RemoteBranch> {
        logger.trace("getRemoteBranchesById")
        return getRemoteBranches(remoteName)
            .mapNotNull { branch ->
                RemoteRef.parse(branch.name, remoteBranchPrefix)
                    ?.takeIf { parts -> parts.revisionNum == null } // Filter history branches
                    ?.let { it.commitId to branch }
            }
            .toMap()
    }

    override fun reset(refName: String) = apply {
        logger.trace("reset {}", refName)
        useGit { git ->
            git.reset()
                .setRef(git.repository.resolve(refName).name)
                .setMode(ResetCommand.ResetType.HARD)
                .call()
        }
    }

    override fun branch(name: String, startPoint: String, force: Boolean): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old = if (refExists(name)) log(name, maxCount = 1).single() else null
        useGit { git ->
            git.branchCreate().setName(name).setForce(force).setStartPoint(startPoint).call()
        }
        return old
    }

    override fun deleteBranches(names: List<String>, force: Boolean): List<String> {
        logger.trace("deleteBranches {} {}", names, force)
        return useGit { git ->
            git.branchDelete().setBranchNames(*names.toTypedArray()).setForce(force).call()
        }
    }

    override fun add(filePattern: String): GitClient {
        logger.trace("add {}", filePattern)
        return apply { useGit { git -> git.add().addFilepattern(filePattern).call() } }
    }

    override fun setCommitId(commitId: String, committer: Ident?, author: Ident?) {
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

    override fun commit(
        message: String?,
        footerLines: Map<String, String>?,
        committer: Ident?,
        author: Ident?,
        amend: Boolean,
    ): Commit {
        logger.trace("commit {} {} {} {} {}", message, footerLines, committer, author, amend)

        require(amend || message != null) { "message is required when not amending" }

        return useGit { git ->
            fun createCommitCommand(): CommitCommand {
                val commitCommand = git.commit().setAmend(amend)
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

    override fun cherryPick(commit: Commit, committer: Ident?, author: Ident?): Commit {
        logger.trace("cherryPick {} {} {}", commit, committer, author)
        return useGit { git ->
            git.cherryPick().include(git.repository.resolve(commit.hash)).call()

            val r = git.repository
            val headCommit = r.parseCommit(r.findRef(GitClient.HEAD).objectId).toCommit(git)

            val isUpdatingCommitter = committer != null && committer != headCommit.committer
            val isUpdatingAuthor = author != null && author != headCommit.author
            if (isUpdatingCommitter || isUpdatingAuthor) {
                val amendCommand = git.commit().setAmend(true).setMessage(headCommit.fullMessage)
                if (isUpdatingCommitter) {
                    amendCommand.setCommitter(committer.toPersonIdent())
                }
                if (isUpdatingAuthor) {
                    amendCommand.setAuthor(author.toPersonIdent())
                }
                amendCommand.call().toCommit(git)
            } else {
                headCommit
            }
        }
    }

    override fun push(refSpecs: List<RefSpec>, remoteName: String) {
        logger.trace("push {}", refSpecs)
        if (refSpecs.isNotEmpty()) {
            useGit { git ->
                val specs =
                    refSpecs.map { (localRef, remoteRef) ->
                        org.eclipse.jgit.transport.RefSpec(
                            "$localRef:${GitClient.R_HEADS}$remoteRef"
                        )
                    }
                checkNoPushErrors(
                    git.push().setRemote(remoteName).setAtomic(true).setRefSpecs(specs).call()
                )
            }
        }
    }

    override fun pushWithLease(
        refSpecs: List<RefSpec>,
        remoteName: String,
        forceWithLeaseRefs: Map<String, String?>,
    ) {
        logger.trace("pushWithLease {} with lease refs {}", refSpecs, forceWithLeaseRefs)
        if (refSpecs.isNotEmpty()) {
            useGit { git ->
                val specs =
                    refSpecs.map { (localRef, remoteRef) ->
                        org.eclipse.jgit.transport.RefSpec(
                            "$localRef:${GitClient.R_HEADS}$remoteRef"
                        )
                    }

                val leaseSpecs =
                    forceWithLeaseRefs.map { (ref, expectedValue) ->
                        val fullRef = "${GitClient.R_HEADS}$ref"
                        if (expectedValue == null) {
                            // Ref must not exist - use empty string to indicate non-existence
                            RefLeaseSpec(fullRef, "")
                        } else {
                            // Ref must have specific value
                            RefLeaseSpec(fullRef, expectedValue)
                        }
                    }

                try {
                    checkNoPushErrors(
                        git.push()
                            .setRemote(remoteName)
                            .setAtomic(true)
                            .setRefSpecs(specs)
                            .setRefLeaseSpecs(leaseSpecs)
                            .call()
                    )
                } catch (e: Exception) {
                    throw PushFailedException("Push with lease failed: ${e.message}", e)
                }
            }
        }
    }

    private fun checkNoPushErrors(pushResults: Iterable<PushResult>) {
        val pushErrors =
            pushResults
                .flatMap { result -> result.remoteUpdates }
                .filterNot { it.status in SUCCESSFUL_PUSH_STATUSES }
        for (e in pushErrors) {
            logger.error(
                "Push failed: {} -> {} ({}: {})",
                e.srcRef,
                e.remoteName,
                e.message,
                e.status,
            )
        }
        check(pushErrors.isEmpty()) { "A git push operation failed, please check the logs" }
    }

    override fun getRemoteUriOrNull(remoteName: String): String? {
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

    override fun addRemote(remoteName: String, remoteUri: String) {
        logger.trace("addRemote {} {}", remoteName, remoteUri)
        useGit { git -> git.remoteAdd().setName(remoteName).setUri(URIish(remoteUri)).call() }
    }

    override fun getConfigValue(key: String): String? {
        logger.trace("getConfigValue {}", key)
        return useGit { git ->
            git.repository.config.getString(
                key.substringBeforeLast('.'),
                null,
                key.substringAfterLast('.'),
            )
        }
    }

    override fun setConfigValue(key: String, value: String) {
        logger.trace("setConfigValue {} {}", key, value)
        useGit { git ->
            val config = git.repository.config
            config.setString(key.substringBeforeLast('.'), null, key.substringAfterLast('.'), value)

            config.save()
        }
    }

    override fun getUpstreamBranch(remoteName: String): RemoteBranch? = useGit { git ->
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

    override fun setUpstreamBranch(remoteName: String, branchName: String) {
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

    override fun getUpstreamBranchName(localBranch: String, remoteName: String): String? {
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

    override fun setUpstreamBranchForLocalBranch(
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

    override fun reflog(): List<Commit> {
        logger.trace("reflog")
        return useGit { git ->
            val reader = git.repository.newObjectReader()
            git.reflog().call().flatMap { entry -> log(reader.abbreviate(entry.newId).name(), 1) }
        }
    }

    override fun getCurrentBranchName(): String {
        logger.trace("getCurrentBranchName")
        return useGit { git -> git.repository.branch }
    }

    override fun isHeadDetached(): Boolean {
        logger.trace("isHeadDetached")
        return useGit { git -> !git.repository.exactRef(Constants.HEAD).isSymbolic }
    }

    override fun getShortMessages(refs: List<String>): Map<String, String?> {
        logger.trace("getShortMessages {}", refs)
        return useGit { git ->
            val repo = git.repository
            refs.associateWith { ref ->
                repo.resolve(ref)?.let { objectId -> repo.parseCommit(objectId).shortMessage }
            }
        }
    }

    override fun getCommits(refs: List<String>): Map<String, Commit?> {
        logger.trace("getCommits {}", refs)
        return useGit { git ->
            val repo = git.repository
            refs.associateWith { ref ->
                repo.resolve(ref)?.let { objectId -> repo.parseCommit(objectId).toCommit(git) }
            }
        }
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    companion object {
        private val SUCCESSFUL_PUSH_STATUSES =
            setOf(
                RemoteRefUpdate.Status.OK,
                RemoteRefUpdate.Status.UP_TO_DATE,
                RemoteRefUpdate.Status.NON_EXISTING,
            )

        init {
            // Enable support for an SSH agent for those who use passphrases for their keys
            // Note that this doesn't work on OS X. This is why OptimizedCliGitClient exists.
            SshSessionFactory.setInstance(
                object : JschConfigSessionFactory() {
                    override fun configureJSch(jsch: JSch) {
                        val agent = AgentIdentityRepository(SSHAgentConnector())
                        if (agent.status == IdentityRepository.RUNNING) {
                            jsch.identityRepository = agent
                        }
                    }
                }
            )
        }
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
