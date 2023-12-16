package sims.michael.gitjaspr

import org.eclipse.jgit.lib.Constants
import java.io.File

interface GitClient {
    val workingDirectory: File
    val remoteBranchPrefix: String
    fun init(): GitClient
    fun checkout(refName: String): GitClient
    fun clone(uri: String, bare: Boolean = false): GitClient
    fun fetch(remoteName: String)
    fun log(): List<Commit>
    fun log(revision: String, maxCount: Int = -1): List<Commit>
    fun logAll(): List<Commit>
    fun getParents(commit: Commit): List<Commit>
    fun logRange(since: String, until: String): List<Commit>
    fun isWorkingDirectoryClean(): Boolean
    fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit>
    fun refExists(ref: String): Boolean
    fun getBranchNames(): List<String>
    fun getRemoteBranches(): List<RemoteBranch>
    fun getRemoteBranchesById(): Map<String, RemoteBranch>
    fun reset(refName: String): GitClient
    fun branch(name: String, startPoint: String = "HEAD", force: Boolean = false): Commit?
    fun deleteBranches(names: List<String>, force: Boolean = false): List<String>
    fun add(filePattern: String): GitClient
    fun setCommitId(commitId: String)
    fun commit(message: String, footerLines: Map<String, String> = emptyMap(), commitIdent: Ident? = null): Commit
    fun cherryPick(commit: Commit): Commit

    // TODO this should accept the remote to push to!
    fun push(refSpecs: List<RefSpec>)
    fun getRemoteUriOrNull(remoteName: String): String?

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        const val R_REMOTES = Constants.R_REMOTES
    }
}
