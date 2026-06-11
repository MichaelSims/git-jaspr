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
        cliGitClient.cherryPick(commit, committer, author, useTheirs)

    override fun tryCherryPick(
        commit: Commit,
        committer: Ident?,
        author: Ident?,
        useTheirs: Boolean,
    ): CherryPickResult = cliGitClient.tryCherryPick(commit, committer, author, useTheirs)

    override fun stashPush(message: String, includeUntracked: Boolean): String? =
        cliGitClient.stashPush(message, includeUntracked)

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
