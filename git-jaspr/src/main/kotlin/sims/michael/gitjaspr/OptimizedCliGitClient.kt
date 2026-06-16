package sims.michael.gitjaspr

import java.io.File

/**
 * An optimized [GitClient] that uses [CliGitClient] for transport operations and [JGitClient] for
 * everything else.
 *
 * [JGitClient] transport operations do not work for users on OS X who use SSH agent (to supply the
 * passphrase for their keys). This class exists to work around this issue by using the CLI for
 * transport operations and JGit for everything else (since it is theoretically faster).
 */
class OptimizedCliGitClient
private constructor(private val cliGitClient: CliGitClient, private val jGitClient: JGitClient) :
    GitClient by jGitClient {

    /** When true, git commands write their stderr (e.g. progress) directly to the terminal. */
    var showStderr: Boolean
        get() = cliGitClient.showStderr
        set(value) {
            cliGitClient.showStderr = value
        }

    override fun clone(uri: String, remoteName: String, bare: Boolean): GitClient {
        cliGitClient.clone(uri, remoteName, bare)
        return this
    }

    override fun fetch(remoteName: String, prune: Boolean) {
        cliGitClient.fetch(remoteName, prune)
    }

    override fun remoteBranchExists(remoteName: String, branchName: String): Boolean =
        cliGitClient.remoteBranchExists(remoteName, branchName)

    override fun push(refSpecs: List<RefSpec>, remoteName: String) {
        cliGitClient.push(refSpecs, remoteName)
    }

    override fun pushWithLease(
        refSpecs: List<RefSpec>,
        remoteName: String,
        forceWithLeaseRefs: Map<String, String?>,
    ) {
        cliGitClient.pushWithLease(refSpecs, remoteName, forceWithLeaseRefs)
    }

    override fun mergeTreeWriteTree(
        base: String,
        ours: String,
        theirs: String,
        useTheirs: Boolean,
    ): MergeTreeResult = cliGitClient.mergeTreeWriteTree(base, ours, theirs, useTheirs)

    override fun cherryPick(
        commit: Commit,
        committer: Ident?,
        author: Ident?,
        useTheirs: Boolean,
        reflogMessage: String?,
    ): Commit =
        // Always route cherry-pick through CliGitClient. JGit's CherryPickCommand has two
        // practical bugs that bite our use cases (see JGitClient.cherryPick for the full
        // writeup):
        //   1. It returns a CherryPickResult with a status (OK / CONFLICTING / FAILED) but
        //      the natural implementation uses "HEAD didn't move" as a proxy. That proxy
        //      mis-classifies CONFLICTING and "merge no-op vs HEAD" as "empty source", and
        //      the resulting empty-commit fallback silently writes a commit with the
        //      source's message but only the current index's content.
        //   2. JGit's ResolveMerger reads the working tree as merge state. Cherry-picking
        //      onto a HEAD with a dirty workdir (the canonical jaspr-unsplit workflow:
        //      split, `git add -p` a precursor, then unsplit with the remainder unstaged)
        //      makes the merger conclude the merge is a no-op, HEAD doesn't move, and
        //      bug (1) fires, silently dropping the cherry-pick's intended content.
        // CLI cherry-pick costs ~50-100ms extra per call (process fork). Acceptable for
        // every cherry-pick path in jaspr (unsplit, top, pull, sync, divergence probe).
        cliGitClient.cherryPick(commit, committer, author, useTheirs, reflogMessage)

    override fun tryCherryPick(
        commit: Commit,
        committer: Ident?,
        author: Ident?,
        useTheirs: Boolean,
        reflogMessage: String?,
    ): CherryPickResult =
        cliGitClient.tryCherryPick(commit, committer, author, useTheirs, reflogMessage)

    override fun stashPush(refName: String, message: String, includeUntracked: Boolean): String? =
        cliGitClient.stashPush(refName, message, includeUntracked)

    // Always route HEAD-moving ops through CliGitClient so that GIT_REFLOG_ACTION reaches git.
    // JGit's API doesn't expose reflog-message setters for reset / checkout / branch, so a
    // conditional "fast path via JGit when reflogMessage is null" would silently lose the
    // annotation if a caller ever forgot to set one. Uniform routing is the simpler invariant;
    // the fork cost (~50-100ms per call) is already paid for cherry-pick and is invisible on
    // jaspr's interactive paths.

    override fun reset(refName: String, reflogMessage: String?): GitClient = apply {
        cliGitClient.reset(refName, reflogMessage)
    }

    override fun resetMixed(refName: String, reflogMessage: String?): GitClient = apply {
        cliGitClient.resetMixed(refName, reflogMessage)
    }

    override fun resetSoft(refName: String, reflogMessage: String?): GitClient = apply {
        cliGitClient.resetSoft(refName, reflogMessage)
    }

    override fun checkout(refName: String, reflogMessage: String?): GitClient = apply {
        cliGitClient.checkout(refName, reflogMessage)
    }

    override fun branch(
        name: String,
        startPoint: String,
        force: Boolean,
        reflogMessage: String?,
    ): Commit? = cliGitClient.branch(name, startPoint, force, reflogMessage)

    override fun addWorktree(path: File, ref: String?, detached: Boolean) =
        cliGitClient.addWorktree(path, ref, detached)

    override fun removeWorktree(path: File, force: Boolean) =
        cliGitClient.removeWorktree(path, force)

    override fun patchId(sha: String): String? = cliGitClient.patchId(sha)

    companion object {
        operator fun invoke(
            workingDirectory: File,
            remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
        ): OptimizedCliGitClient {
            val cliGitClient = CliGitClient(workingDirectory, remoteBranchPrefix)
            val jGitClient = JGitClient(workingDirectory, remoteBranchPrefix)
            return OptimizedCliGitClient(cliGitClient, jGitClient)
        }
    }
}
