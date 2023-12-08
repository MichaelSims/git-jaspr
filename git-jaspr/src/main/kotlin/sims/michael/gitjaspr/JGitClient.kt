package sims.michael.gitjaspr

import org.eclipse.jgit.api.CheckoutResult
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate.Status
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitFooters.addFooters
import sims.michael.gitjaspr.CommitFooters.getFooters
import sims.michael.gitjaspr.JGitClient.CheckoutMode.*
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.eclipse.jgit.transport.RefSpec as JRefSpec

// TODO consider extracting an interface from this once the implementation settles
class JGitClient(val workingDirectory: File, val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX) {
    private val logger = LoggerFactory.getLogger(JGitClient::class.java)

    fun commitIdsByBranch(): Map<String, String?> = useGit { git ->
        git
            .branchList()
            .call()
            .associate { ref ->
                val name = ref.name
                name to log(name, 1).first()
            }
            .mapValues { (_, commit) -> commit.id }
            .filterKeys { it.startsWith("$R_HEADS$remoteBranchPrefix") }
            .toSortedMap()
    }

    fun log(revision: String, maxCount: Int = -1): List<Commit> = useGit { git ->
        git
            .log()
            .add(git.repository.resolve(revision))
            .setMaxCount(maxCount)
            .call()
            .toList()
            .map { revCommit -> revCommit.toCommit(git) }
    }

    fun logRange(since: String, until: String): List<Commit> = useGit { git ->
        val r = git.repository
        val sinceObjectId = checkNotNull(r.resolve(since)) { "$since doesn't exist" }
        val untilObjectId = checkNotNull(r.resolve(until)) { "$until doesn't exist" }
        val commits = git.log().addRange(sinceObjectId, untilObjectId).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    fun log(): List<Commit> = useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }

    fun logAll(): List<Commit> = useGit { git -> git.log().all().call().map { it.toCommit(git) }.reversed() }

    fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return useGit { git -> git.status().call().isClean }
    }

    fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
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

    fun refExists(ref: String) = useGit { git -> git.repository.resolve(ref) != null }

    fun getBranchNames(): List<String> = useGit { git ->
        git
            .branchList()
            .setListMode(ListBranchCommand.ListMode.ALL)
            .call()
            .map {
                it.name.removePrefix(Constants.R_HEADS)
            }
    }

    fun getRemoteBranches(): List<RemoteBranch> = useGit { git ->
        git
            .branchList()
            .setListMode(ListBranchCommand.ListMode.REMOTE)
            .call()
            .filter { it.name.startsWith(Constants.R_REMOTES) }
            .map { ref ->
                val r = git.repository
                val shortBranchName = checkNotNull(r.shortenRemoteBranchName(ref.name)) {
                    "Short branch name was null for ${ref.name}"
                }
                RemoteBranch(shortBranchName, r.parseCommit(ref.objectId).toCommit(git))
            }
    }

    fun getRemoteBranchesById(): Map<String, RemoteBranch> =
        getRemoteBranches()
            .mapNotNull { branch ->
                val commitId = getCommitIdFromRemoteRef(branch.name, remoteBranchPrefix)
                if (commitId != null) commitId to branch else null
            }
            .toMap()

    fun fetch(remoteName: String) {
        logger.trace("fetch {}", remoteName)
        useGit { git -> git.fetch().setRemote(remoteName).call() }
    }

    fun reset(refName: String) = apply {
        logger.trace("reset {}", refName)
        useGit { git ->
            git.reset().setRef(git.repository.resolve(refName).name).setMode(ResetCommand.ResetType.HARD).call()
        }
    }

    enum class CheckoutMode { CreateBranch, CreateBranchIfNotExists, Default }

    fun checkout(refName: String, mode: CheckoutMode = Default) = apply {
        logger.trace("checkout {} ({})", refName, mode)
        useGit { git ->
            val refExists = refExists(refName)
            if (mode == Default) {
                require(refExists) { "$refName does not exist" }
            } else if (mode == CreateBranch) {
                require(!refExists) { "$refName already exists" }
            }
            git.checkout().setName(refName).setCreateBranch(!refExists).run {
                call()
                check(result.status == CheckoutResult.Status.OK) { "Checkout result was ${result.status}" }
            }
        }
    }

    fun branch(name: String, startPoint: String = "HEAD", force: Boolean = false): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old = if (refExists(name)) log(name, maxCount = 1).single() else null
        useGit { git -> git.branchCreate().setName(name).setForce(force).setStartPoint(startPoint).call() }
        return old
    }

    fun deleteBranches(names: List<String>, force: Boolean = false) = useGit { git ->
        git.branchDelete().setBranchNames(*names.toTypedArray()).setForce(force).call()
    }

    fun init(): JGitClient = apply {
        Git.init().setDirectory(workingDirectory).setInitialBranch("main").call().close()
    }

    fun add(filePattern: String) = apply {
        useGit { git ->
            git.add().addFilepattern(filePattern).call()
        }
    }

    fun commitAmend(newMessage: String? = null) = useGit { git ->
        val message = newMessage
            ?: git.log().add(git.repository.resolve(HEAD)).setMaxCount(1).call().single().fullMessage
        val committer = PersonIdent(PersonIdent(git.repository), Instant.now())

        git.commit().setMessage(message).setCommitter(committer).setAmend(true).call().toCommit(git)
    }

    fun commit(message: String, footerLines: Map<String, String> = emptyMap()) = useGit { git ->
        val committer = PersonIdent(PersonIdent(git.repository), Instant.now())
        git.commit().setMessage(addFooters(message, footerLines)).setCommitter(committer).call().toCommit(git)
    }

    fun setCommitId(commitId: String) {
        logger.trace("setCommitId {}", commitId) // TODO add trace calls to all functions
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(HEAD).objectId)
            require(!getFooters(head.fullMessage).containsKey(COMMIT_ID_LABEL))
            git
                .commit()
                .setAmend(true)
                .setMessage(addFooters(head.fullMessage, mapOf(COMMIT_ID_LABEL to commitId)))
                .call()
        }
    }

    fun clone(uri: String) = apply {
        Git.cloneRepository().setDirectory(workingDirectory).setURI(uri).call().close()
    }

    fun cherryPick(commit: Commit): Commit {
        logger.trace("cherryPick {}", commit)
        return useGit { git ->
            git.cherryPick().include(git.repository.resolve(commit.hash)).call().newHead.toCommit(git)
            // TODO check results and things
        }
    }

    fun push(refSpecs: List<RefSpec>) {
        logger.trace("push {}", refSpecs)
        if (refSpecs.isNotEmpty()) {
            useGit { git ->
                val specs = refSpecs.map { (localRef, remoteRef) ->
                    JRefSpec("$localRef:$R_HEADS$remoteRef")
                }
                checkNoPushErrors(
                    git
                        .push()
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

    fun getRemoteUriOrNull(remoteName: String): String? = useGit { git ->
        git.remoteList().call().singleOrNull { it.name == remoteName }?.urIs?.firstOrNull()?.toASCIIString()
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    private fun RevCommit.toCommit(git: Git): Commit {
        val r = git.repository
        val objectReader = r.newObjectReader()
        return Commit(
            objectReader.abbreviate(id).name(),
            shortMessage,
            fullMessage,
            getFooters(fullMessage)[COMMIT_ID_LABEL],
            Ident(committerIdent.name, committerIdent.emailAddress),
            ZonedDateTime.ofInstant(committerIdent.`when`.toInstant(), ZoneId.systemDefault()),
            ZonedDateTime.ofInstant(authorIdent.`when`.toInstant(), ZoneId.systemDefault()),
        )
    }

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        const val R_REMOTES = Constants.R_REMOTES
        private val SUCCESSFUL_PUSH_STATUSES = setOf(Status.OK, Status.UP_TO_DATE, Status.NON_EXISTING)
    }
}
