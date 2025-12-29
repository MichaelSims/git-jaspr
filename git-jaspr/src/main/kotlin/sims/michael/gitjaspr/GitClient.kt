package sims.michael.gitjaspr

import java.io.File
import org.eclipse.jgit.lib.Constants

interface GitClient {
    val workingDirectory: File
    val remoteBranchPrefix: String

    fun init(): GitClient

    fun checkout(refName: String): GitClient

    fun clone(
        uri: String,
        remoteName: String = DEFAULT_REMOTE_NAME,
        bare: Boolean = false,
    ): GitClient

    fun fetch(remoteName: String, prune: Boolean = false)

    fun log(): List<Commit>

    fun log(revision: String, maxCount: Int = -1): List<Commit>

    fun logAll(): List<Commit>

    fun getParents(commit: Commit): List<Commit>

    fun logRange(since: String, until: String): List<Commit>

    fun isWorkingDirectoryClean(): Boolean

    fun getLocalCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit>

    fun refExists(ref: String): Boolean

    fun getBranchNames(): List<String>

    fun getRemoteBranches(remoteName: String = DEFAULT_REMOTE_NAME): List<RemoteBranch>

    fun getRemoteBranchesById(remoteName: String = DEFAULT_REMOTE_NAME): Map<String, RemoteBranch>

    fun reset(refName: String): GitClient

    fun branch(name: String, startPoint: String = "HEAD", force: Boolean = false): Commit?

    fun deleteBranches(names: List<String>, force: Boolean = false): List<String>

    fun add(filePattern: String): GitClient

    fun setCommitId(commitId: String, commitIdent: Ident? = null)

    fun commit(
        message: String,
        footerLines: Map<String, String> = emptyMap(),
        commitIdent: Ident? = null,
    ): Commit

    fun cherryPick(commit: Commit, commitIdent: Ident? = null): Commit

    fun push(refSpecs: List<RefSpec>, remoteName: String = DEFAULT_REMOTE_NAME)

    /**
     * Push with force-with-lease protection.
     *
     * @param refSpecs The refSpecs to push
     * @param remoteName The remote name
     * @param forceWithLeaseRefs Map of remote ref name to expected old value (null means must not
     *   exist)
     * @throws PushFailedException if the push fails (e.g., ref exists when it shouldn't, or has
     *   unexpected value)
     */
    fun pushWithLease(
        refSpecs: List<RefSpec>,
        remoteName: String = DEFAULT_REMOTE_NAME,
        forceWithLeaseRefs: Map<String, String?> = emptyMap(),
    )

    fun getRemoteUriOrNull(remoteName: String): String?

    fun addRemote(remoteName: String, remoteUri: String)

    fun getConfigValue(key: String): String?

    // As of this writing, this only allows "local" (working copy specific) config changes
    fun setConfigValue(key: String, value: String)

    fun getUpstreamBranch(remoteName: String): RemoteBranch?

    fun setUpstreamBranch(remoteName: String, branchName: String)

    fun reflog(): List<Commit>

    fun getCurrentBranchName(): String

    fun isHeadDetached(): Boolean

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        const val R_REMOTES = Constants.R_REMOTES
    }
}
