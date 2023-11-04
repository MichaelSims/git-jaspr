package sims.michael.gitkspr

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate.Status
import org.slf4j.LoggerFactory
import java.io.File
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
        val commits = git.log().addRange(r.resolve(since), r.resolve(until)).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    fun log(): List<Commit> = useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }

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

    fun getRemoteBranches(): List<RemoteBranch> = useGit { git ->
        git
            .branchList()
            .setListMode(ListBranchCommand.ListMode.REMOTE)
            .call()
            .map { ref ->
                val r = git.repository
                RemoteBranch(r.shortenRemoteBranchName(ref.name), r.parseCommit(ref.objectId).toCommit(git))
            }
    }

    fun getRemoteBranchesById(): Map<String, RemoteBranch> =
        getRemoteBranches().associateBy { branch -> branch.name.removePrefix(remoteBranchPrefix) }

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

    fun checkout(refName: String, createBranch: Boolean = false) = apply {
        logger.trace("checkout {}{}", refName, if (createBranch) " (create)" else "")
        useGit { git ->
            val name = if (createBranch && !refExists(refName)) {
                refName
            } else {
                val objectId = requireNotNull(git.repository.resolve(refName)) {
                    "Ref named $refName was not found"
                }
                objectId.name
            }
            git.checkout().setName(name).setCreateBranch(createBranch).call()
        }
    }

    fun branch(name: String) {
        useGit { git -> git.branchCreate().setName(name).call() }
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
        git.commit().setMessage(message).setAmend(true).call().toCommit(git)
    }

    fun commit(message: String) = useGit { git ->
        git.commit().setMessage(message).call().toCommit(git)
    }

    fun setCommitId(commitId: String) {
        logger.trace("setCommitId {}", commitId) // TODO add trace calls to all functions
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(HEAD).objectId)
            require(head.getFooterLines(COMMIT_ID_LABEL).isEmpty())
            git.commit().setAmend(true).setMessage(appendCommitId(head.fullMessage, commitId)).call()
        }
    }

    fun clone(uri: String) = apply {
        Git.cloneRepository().setDirectory(workingDirectory).setURI(uri).call().close()
    }

    private fun appendCommitId(fullMessage: String, commitId: String) =
        """
            $fullMessage

            $COMMIT_ID_LABEL: $commitId

        """.trimIndent()

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
            getFooterLines(COMMIT_ID_LABEL).firstOrNull(),
            ZonedDateTime.ofInstant(committerIdent.`when`.toInstant(), ZoneId.systemDefault()),
            ZonedDateTime.ofInstant(authorIdent.`when`.toInstant(), ZoneId.systemDefault()),
        )
    }

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        private val SUCCESSFUL_PUSH_STATUSES = setOf(Status.OK, Status.UP_TO_DATE, Status.NON_EXISTING)
    }
}
