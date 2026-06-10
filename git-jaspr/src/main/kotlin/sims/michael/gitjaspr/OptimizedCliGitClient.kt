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
        // `-X theirs` strategy lives on the CLI side; JGit's cherry-pick path doesn't support
        // strategy options the same way. Routes only the strategy-using calls through the CLI;
        // plain cherry-picks continue to use the JGit-backed fast path.
        if (useTheirs) {
            cliGitClient.cherryPick(commit, committer, author, useTheirs = true)
        } else {
            jGitClient.cherryPick(commit, committer, author)
        }

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
