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

    fun hasUncommittedChangesToTrackedFiles(): Boolean

    fun getCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit>

    fun refExists(ref: String): Boolean

    fun getBranchNames(): List<String>

    fun getRemoteBranches(remoteName: String = DEFAULT_REMOTE_NAME): List<RemoteBranch>

    fun getRemoteBranchesById(remoteName: String = DEFAULT_REMOTE_NAME): Map<String, RemoteBranch>

    fun reset(refName: String): GitClient

    fun resetMixed(refName: String): GitClient

    fun resetSoft(refName: String): GitClient

    /** Equivalent to `git clean -d -f` — removes untracked files and directories. */
    fun cleanUntracked(): GitClient

    fun branch(name: String, startPoint: String = "HEAD", force: Boolean = false): Commit?

    fun deleteBranches(names: List<String>, force: Boolean = false): List<String>

    fun add(filePattern: String): GitClient

    fun setCommitId(commitId: String, committer: Ident? = null, author: Ident? = null)

    fun commit(
        message: String? = null,
        footerLines: Map<String, String>? = null,
        committer: Ident? = null,
        author: Ident? = null,
        amend: Boolean = false,
    ): Commit

    fun cherryPick(commit: Commit, committer: Ident? = null, author: Ident? = null): Commit

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

    /**
     * Returns the upstream branch name for a specific local branch, or null if none is configured.
     * Unlike [getUpstreamBranch], this does not require the branch to be checked out.
     */
    fun getUpstreamBranchName(localBranch: String, remoteName: String): String?

    /**
     * Sets or removes the upstream tracking branch for a specific local branch. Unlike
     * [setUpstreamBranch], this does not require the branch to be checked out. Pass null for
     * [remoteBranchName] to remove the upstream tracking configuration.
     */
    fun setUpstreamBranchForLocalBranch(
        localBranch: String,
        remoteName: String,
        remoteBranchName: String?,
    )

    fun reflog(): List<Commit>

    fun getCurrentBranchName(): String

    fun isHeadDetached(): Boolean

    /**
     * Returns the merge-base SHA of two refs (i.e., the best common ancestor reachable from both),
     * or null if the two refs have no common ancestor.
     */
    fun mergeBase(a: String, b: String): String?

    /**
     * Returns true if [ancestor] is an ancestor of [descendant] (i.e., reachable by walking
     * [descendant]'s history). Equivalent to `git merge-base --is-ancestor`.
     */
    fun isAncestor(ancestor: String, descendant: String): Boolean

    /**
     * Performs a three-way merge entirely in-memory, returning the result tree SHA on success or
     * null on conflict. Does not touch the working tree, the index, or HEAD. Equivalent to `git
     * merge-tree --write-tree --merge-base=<base> <ours> <theirs>`.
     *
     * Use this to probe whether a merge / cherry-pick can be applied cleanly before attempting it
     * for real.
     *
     * Requires git 2.38+. Not implemented for [JGitClient]; production wiring routes this through
     * [CliGitClient].
     */
    fun mergeTreeWriteTree(base: String, ours: String, theirs: String): String?

    /** Returns short messages for multiple refs in a single operation. */
    fun getShortMessages(refs: List<String>): Map<String, String?>

    /** Returns full Commit objects for multiple refs in a single operation. */
    fun getCommits(refs: List<String>): Map<String, Commit?>

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        const val R_REMOTES = Constants.R_REMOTES
    }
}
