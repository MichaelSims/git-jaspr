package sims.michael.gitjaspr

import java.io.File

/**
 * The production [GitClient]. It composes two backends:
 * - [CliGitClient], a complete implementation that shells out to the `git` CLI, handles every
 *   method by default (delegated via `by cli`). It is the source of truth and the only backend that
 *   must implement the full interface.
 * - [JGitClient], an in-process accelerator used for read-heavy and local-mutation methods. Each
 *   CLI call forks a `git` process (tens of ms on macOS); JGit opens the repo in-process and
 *   amortizes that across a walk, which matters most on hot paths like the named-stack reachability
 *   search.
 *
 * Transport (clone/fetch/push) and HEAD-moving ops (checkout/reset/branch) intentionally stay on
 * the CLI: JGit's SSH transport doesn't work with an SSH agent on macOS, and JGit can't annotate
 * the reflog. Because CLI is the default backend, any method not explicitly routed to JGit below is
 * served correctly by the CLI with no extra work.
 */
class DefaultGitClient
private constructor(private val cli: CliGitClient, private val jgit: JGitClient) :
    GitClient by cli {

    constructor(
        workingDirectory: File,
        remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
    ) : this(
        CliGitClient(workingDirectory, remoteBranchPrefix),
        JGitClient(workingDirectory, remoteBranchPrefix),
    )

    // Methods that return a GitClient for chaining are overridden to return this composite (not the
    // inner backend), so a chained call stays routed through here.

    override fun init() = apply { jgit.init() }

    override fun cleanUntracked() = apply { jgit.cleanUntracked() }

    override fun add(filePattern: String) = apply { jgit.add(filePattern) }

    override fun checkout(refName: String, reflogMessage: String?) = apply {
        cli.checkout(refName, reflogMessage)
    }

    override fun clone(uri: String, remoteName: String, bare: Boolean) = apply {
        cli.clone(uri, remoteName, bare)
    }

    override fun reset(refName: String, reflogMessage: String?) = apply {
        cli.reset(refName, reflogMessage)
    }

    override fun resetMixed(refName: String, reflogMessage: String?) = apply {
        cli.resetMixed(refName, reflogMessage)
    }

    override fun resetSoft(refName: String, reflogMessage: String?) = apply {
        cli.resetSoft(refName, reflogMessage)
    }

    // === Served by JGit: reads ===

    override fun log() = jgit.log()

    override fun log(revision: String, maxCount: Int) = jgit.log(revision, maxCount)

    override fun logAll() = jgit.logAll()

    override fun getParents(commit: Commit) = jgit.getParents(commit)

    override fun logRange(since: String, until: String) = jgit.logRange(since, until)

    override fun getCommitIdsInRange(target: String, refs: List<String>) =
        jgit.getCommitIdsInRange(target, refs)

    override fun hasUncommittedChangesToTrackedFiles() = jgit.hasUncommittedChangesToTrackedFiles()

    override fun getCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ) = jgit.getCommitStack(remoteName, localObjectName, targetRefName)

    override fun refExists(ref: String) = jgit.refExists(ref)

    override fun getBranchNames() = jgit.getBranchNames()

    override fun getRemoteBranches(remoteName: String) = jgit.getRemoteBranches(remoteName)

    override fun getRemoteBranchesById(remoteName: String) = jgit.getRemoteBranchesById(remoteName)

    override fun getRemoteUriOrNull(remoteName: String) = jgit.getRemoteUriOrNull(remoteName)

    override fun getConfigValue(key: String) = jgit.getConfigValue(key)

    override fun getUpstreamBranch(remoteName: String) = jgit.getUpstreamBranch(remoteName)

    override fun getUpstreamBranchName(localBranch: String, remoteName: String) =
        jgit.getUpstreamBranchName(localBranch, remoteName)

    override fun reflog() = jgit.reflog()

    override fun getCurrentBranchName() = jgit.getCurrentBranchName()

    override fun isHeadDetached() = jgit.isHeadDetached()

    override fun mergeBase(a: String, b: String) = jgit.mergeBase(a, b)

    override fun isAncestor(ancestor: String, descendant: String) =
        jgit.isAncestor(ancestor, descendant)

    override fun gitDir() = jgit.gitDir()

    override fun gitCommonDir() = jgit.gitCommonDir()

    override fun isCherryPickInProgress() = jgit.gitDir().resolve("CHERRY_PICK_HEAD").exists()

    override fun getTree(ref: String) = jgit.getTree(ref)

    override fun getShortMessages(refs: List<String>) = jgit.getShortMessages(refs)

    override fun getCommits(refs: List<String>) = jgit.getCommits(refs)

    // === Served by JGit: local mutations (no transport, no SSH) ===

    override fun deleteBranches(names: List<String>, force: Boolean) =
        jgit.deleteBranches(names, force)

    override fun setCommitId(commitId: String, committer: Ident?, author: Ident?) {
        jgit.setCommitId(commitId, committer, author)
    }

    override fun commit(
        message: String?,
        footerLines: Map<String, String>?,
        committer: Ident?,
        author: Ident?,
        amend: Boolean,
        reflogMessage: String?,
    ) = jgit.commit(message, footerLines, committer, author, amend, reflogMessage)

    override fun addRemote(remoteName: String, remoteUri: String) {
        jgit.addRemote(remoteName, remoteUri)
    }

    override fun setConfigValue(key: String, value: String) {
        jgit.setConfigValue(key, value)
    }

    override fun setUpstreamBranch(remoteName: String, branchName: String) {
        jgit.setUpstreamBranch(remoteName, branchName)
    }

    override fun setUpstreamBranchForLocalBranch(
        localBranch: String,
        remoteName: String,
        remoteBranchName: String?,
    ) {
        jgit.setUpstreamBranchForLocalBranch(localBranch, remoteName, remoteBranchName)
    }

    override fun updateRef(refName: String, sha: String) {
        jgit.updateRef(refName, sha)
    }

    override fun cherryPickAbort() {
        jgit.cherryPickAbort()
    }
}
