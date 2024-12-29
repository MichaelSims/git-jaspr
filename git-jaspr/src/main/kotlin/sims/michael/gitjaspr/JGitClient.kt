package sims.michael.gitjaspr

import com.jcraft.jsch.AgentIdentityRepository
import com.jcraft.jsch.IdentityRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SSHAgentConnector
import org.eclipse.jgit.api.CheckoutResult
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime.ofInstant

class JGitClient(
    override val workingDirectory: File,
    override val remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
) : GitClient {
    private val logger = LoggerFactory.getLogger(GitClient::class.java)

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
                check(result.status == CheckoutResult.Status.OK) { "Checkout result was ${result.status}" }
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
            throw GitJasprException("Failed to fetch from $remoteName; consider enabling the CLI git client", e)
        }
    }

    override fun log(): List<Commit> {
        logger.trace("log")
        return useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }
    }

    override fun log(revision: String, maxCount: Int): List<Commit> = useGit { git ->
        logger.trace("log {} {}", revision, maxCount)
        git
            .log()
            .add(git.repository.resolve(revision))
            .setMaxCount(maxCount)
            .call()
            .toList()
            .map { revCommit -> revCommit.toCommit(git) }
    }

    override fun logAll(): List<Commit> {
        logger.trace("logAll")
        return useGit { git -> git.log().all().call().map { it.toCommit(git) }.reversed() }
    }

    override fun getParents(commit: Commit): List<Commit> = useGit { git ->
        logger.trace("getParents {}", commit)
        git
            .log()
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
        val sinceObjectId = checkNotNull(r.resolve(since)) { "$since doesn't exist" }
        val untilObjectId = checkNotNull(r.resolve(until)) { "$until doesn't exist" }
        val commits = git.log().addRange(sinceObjectId, untilObjectId).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    override fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return useGit { git -> git.status().call().isClean }
    }

    override fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
        logger.trace("getLocalCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return useGit { git ->
            val r = git.repository
            val trackingBranch = requireNotNull(r.resolve("$remoteName/$targetRefName")) {
                "$targetRefName does not exist in the remote"
            }
            val revCommits = git
                .log()
                .addRange(trackingBranch, r.resolve(localObjectName))
                .call()
                .toList()
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
            git
                .branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call()
                .map {
                    it.name.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_REMOTES)
                }
        }
    }

    override fun getRemoteBranches(remoteName: String): List<RemoteBranch> {
        logger.trace("getRemoteBranches")
        return useGit { git ->
            git
                .branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .filter { it.name.startsWith(Constants.R_REMOTES) }
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
                getRemoteRefParts(branch.name, remoteBranchPrefix)
                    ?.takeIf { parts -> parts.revisionNum == null } // Filter history branches
                    ?.let { it.commitId to branch }
            }
            .toMap()
    }

    override fun reset(refName: String) = apply {
        logger.trace("reset {}", refName)
        useGit { git ->
            git.reset().setRef(git.repository.resolve(refName).name).setMode(ResetCommand.ResetType.HARD).call()
        }
    }

    override fun branch(name: String, startPoint: String, force: Boolean): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old = if (refExists(name)) log(name, maxCount = 1).single() else null
        useGit { git -> git.branchCreate().setName(name).setForce(force).setStartPoint(startPoint).call() }
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
        return apply {
            useGit { git ->
                git.add().addFilepattern(filePattern).call()
            }
        }
    }

    override fun setCommitId(commitId: String, commitIdent: Ident?) {
        logger.trace("setCommitId {}", commitId)
        // JGitClient doesn't support per-commit idents, so we are ignoring the commitIdent argument intentionally
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(GitClient.HEAD).objectId)
            require(!CommitParsers.getFooters(head.fullMessage).containsKey(COMMIT_ID_LABEL))
            git
                .commit()
                .setAmend(true)
                .setMessage(CommitParsers.addFooters(head.fullMessage, mapOf(COMMIT_ID_LABEL to commitId)))
                .call()
        }
    }

    override fun commit(message: String, footerLines: Map<String, String>, commitIdent: Ident?): Commit {
        logger.trace("commit {} {}", message, footerLines)
        // JGitClient doesn't support per-commit idents, so we are ignoring the commitIdent argument intentionally
        return useGit { git ->
            val committer = PersonIdent(PersonIdent(git.repository), Instant.now())
            git.commit().setMessage(CommitParsers.addFooters(message, footerLines)).setCommitter(committer).call()
                .toCommit(git)
        }
    }

    override fun cherryPick(commit: Commit, commitIdent: Ident?): Commit {
        logger.trace("cherryPick {}", commit)
        // JGitClient doesn't support per-commit idents, so we are ignoring the commitIdent argument intentionally
        return useGit { git ->
            git.cherryPick().include(git.repository.resolve(commit.hash)).call().newHead.toCommit(git)
        }
    }

    override fun push(refSpecs: List<RefSpec>, remoteName: String) {
        logger.trace("push {}", refSpecs)
        if (refSpecs.isNotEmpty()) {
            useGit { git ->
                val specs = refSpecs.map { (localRef, remoteRef) ->
                    org.eclipse.jgit.transport.RefSpec("$localRef:${GitClient.R_HEADS}$remoteRef")
                }
                checkNoPushErrors(
                    git
                        .push()
                        .setRemote(remoteName)
                        .setAtomic(true)
                        .setRefSpecs(specs)
                        .call(),
                )
            }
        }
    }

    private fun checkNoPushErrors(pushResults: Iterable<PushResult>) {
        val pushErrors = pushResults
            .flatMap { result -> result.remoteUpdates }
            .filterNot { it.status in SUCCESSFUL_PUSH_STATUSES }
        for (e in pushErrors) {
            logger.error("Push failed: {} -> {} ({}: {})", e.srcRef, e.remoteName, e.message, e.status)
        }
        check(pushErrors.isEmpty()) { "A git push operation failed, please check the logs" }
    }

    override fun getRemoteUriOrNull(remoteName: String): String? = useGit { git ->
        git.remoteList().call().singleOrNull { it.name == remoteName }?.urIs?.firstOrNull()?.toASCIIString()
    }

    override fun getUpstreamBranchName(remoteName: String): String? = useGit { git ->
        val prefix = "${Constants.R_REMOTES}$remoteName/"
        val repository = git.repository
        BranchConfig(repository.config, repository.branch)
            .trackingBranch
            ?.takeIf { name -> name.startsWith(prefix) }
            ?.removePrefix(prefix)
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    companion object {
        private val SUCCESSFUL_PUSH_STATUSES = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
            RemoteRefUpdate.Status.NON_EXISTING,
        )

        init {
            // Enable support for an SSH agent for those who use passphrases for their keys
            // Note that this doesn't work on OS X. Users on OS X will need to use the CLI git client
            SshSessionFactory.setInstance(
                object : JschConfigSessionFactory() {
                    override fun configureJSch(jsch: JSch) {
                        val agent = AgentIdentityRepository(SSHAgentConnector())
                        if (agent.status == IdentityRepository.RUNNING) {
                            jsch.identityRepository = agent
                        }
                    }
                },
            )
        }
    }
}

private fun RevCommit.toCommit(git: Git): Commit {
    val r = git.repository
    val objectReader = r.newObjectReader()
    fun PersonIdent.whenAsZonedDateTime() = ofInstant(whenAsInstant, ZoneId.systemDefault()).canonicalize()
    return Commit(
        objectReader.abbreviate(id).name(),
        shortMessage,
        fullMessage,
        CommitParsers.getFooters(fullMessage)[COMMIT_ID_LABEL],
        Ident(committerIdent.name, committerIdent.emailAddress),
        committerIdent.whenAsZonedDateTime(),
        authorIdent.whenAsZonedDateTime(),
    )
}
