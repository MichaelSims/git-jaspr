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

    /**
     * For each ref in [refs], returns the jaspr commit-ids of commits reachable from that ref but
     * not from [target]. The order of commit-ids within each list is unspecified.
     *
     * Behaviorally equivalent to `refs.associateWith { logRange(target, it).mapNotNull(Commit::id)
     * }`, but allows implementations to share repo state across the N walks and skip per-commit
     * work beyond trailer extraction. The default implementation delegates to [logRange];
     * [JGitClient] overrides for performance.
     */
    fun getCommitIdsInRange(target: String, refs: List<String>): Map<String, List<String>> =
        refs.associateWith { ref ->
            logRange(target, ref).mapNotNull(Commit::id)
        }

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

    /**
     * Cherry-picks [commit] onto HEAD. When [useTheirs] is true, content-level conflicts are
     * auto-resolved using the `-X theirs` strategy option (the cherry-picked commit wins). Other
     * conflict types (modify/delete, rename/rename, etc.) still surface as failures.
     */
    fun cherryPick(
        commit: Commit,
        committer: Ident? = null,
        author: Ident? = null,
        useTheirs: Boolean = false,
    ): Commit

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
     * Performs a three-way merge entirely in-memory, returning [MergeTreeResult.Clean] with the
     * result tree SHA on a clean merge or [MergeTreeResult.Conflict] with the list of conflicting
     * paths otherwise. Does not touch the working tree, the index, or HEAD. Equivalent to `git
     * merge-tree --write-tree --name-only [--Xtheirs] --merge-base=<base> <ours> <theirs>`.
     *
     * When [useTheirs] is true, content-level conflicts are auto-resolved by taking [theirs]'s
     * content (matching `git merge -X theirs` semantics). Non-content conflict types
     * (modify/delete, rename/rename, etc.) still surface in the [Conflict] result.
     *
     * Use this to probe whether a merge / cherry-pick can be applied cleanly before attempting it
     * for real.
     *
     * Requires git 2.38+. Not implemented for [JGitClient]; production wiring routes this through
     * [CliGitClient].
     */
    fun mergeTreeWriteTree(
        base: String,
        ours: String,
        theirs: String,
        useTheirs: Boolean = false,
    ): MergeTreeResult

    /**
     * Sets [refName] to point at [sha]. Equivalent to `git update-ref <refName> <sha>`. Used for
     * recording backup refs outside the normal branch namespace (e.g., `refs/jaspr-backup/...`).
     */
    fun updateRef(refName: String, sha: String)

    /**
     * Returns the worktree-specific git directory as an absolute, canonical path. Equivalent to
     * `git rev-parse --git-dir`. In a main checkout this is `<repo>/.git`; in a linked worktree
     * this is `<repo>/.git/worktrees/<name>`. Per-checkout state like `CHERRY_PICK_HEAD`,
     * `MERGE_HEAD`, and `rebase-merge/`/`rebase-apply/` live here.
     */
    fun gitDir(): File

    /**
     * Returns the shared git directory as an absolute, canonical path. Equivalent to `git rev-parse
     * --git-common-dir`. Same as [gitDir] in a main checkout; in a linked worktree this points back
     * at the main `.git/` where shared state like `hooks/`, `config`, `objects/`, and `refs/` live.
     */
    fun gitCommonDir(): File

    /**
     * Adds a linked worktree at [path]. Equivalent to `git worktree add [--detach] <path> [<ref>]`.
     * When [ref] is null, the worktree points at the current HEAD. When [detached] is true
     * (default), the worktree is in detached-HEAD state; otherwise a new branch is created.
     *
     * Not implemented for [JGitClient]; production wiring routes this through [CliGitClient].
     */
    fun addWorktree(path: File, ref: String? = null, detached: Boolean = true)

    /**
     * Removes the linked worktree at [path]. Equivalent to `git worktree remove [--force] <path>`.
     * Throws on failure (e.g., the worktree has uncommitted changes and [force] is false).
     *
     * Not implemented for [JGitClient]; production wiring routes this through [CliGitClient].
     */
    fun removeWorktree(path: File, force: Boolean = false)

    /**
     * Aborts an in-progress cherry-pick, restoring HEAD, the index, and the working tree to the
     * state before the cherry-pick started. Equivalent to `git cherry-pick --abort` plus a
     * tolerance for being called when no cherry-pick is in progress (which CLI `git` rejects with
     * an error but is a common defensive pattern after a failed [cherryPick]). Implementations
     * should hard-reset the working tree and index to HEAD and clear any `CHERRY_PICK_HEAD` /
     * `MERGE_MSG` sentinels.
     */
    fun cherryPickAbort()

    /**
     * Returns true if a cherry-pick is currently in progress (i.e., `.git/CHERRY_PICK_HEAD` exists
     * in this checkout's git dir). Useful for callers that need to differentiate "operate on a
     * clean state" from "clean up a half-finished cherry-pick first".
     */
    fun isCherryPickInProgress(): Boolean = gitDir().resolve("CHERRY_PICK_HEAD").exists()

    /**
     * Returns a stable patch-id for [sha] (equivalent to `git show <sha> | git patch-id --stable`),
     * or null if a patch-id can't be computed (e.g., the commit doesn't exist, the diff is empty,
     * or the underlying tool fails). Two commits with the same patch-id have equivalent diffs
     * ignoring line numbers and whitespace; useful for detecting cherry-picks and rebases.
     *
     * Not implemented for [JGitClient]; production wiring routes this through [CliGitClient].
     */
    fun patchId(sha: String): String?

    /**
     * Returns the tree SHA for [ref] (equivalent to `git rev-parse <ref>^{tree}`). Throws if [ref]
     * doesn't resolve to a commit. Useful for comparing tree contents across refs without walking
     * files individually.
     */
    fun getTree(ref: String): String

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

/** Outcome of an in-memory merge probe via [GitClient.mergeTreeWriteTree]. */
sealed class MergeTreeResult {
    /** Clean merge. [treeSha] is the OID of the merged tree. */
    data class Clean(val treeSha: String) : MergeTreeResult()

    /**
     * One or more conflicts could not be resolved (or were not asked to be auto-resolved).
     * [conflictingPaths] lists each conflicting path once, regardless of how many stages
     * contributed to the conflict.
     */
    data class Conflict(val conflictingPaths: List<String>) : MergeTreeResult()
}
