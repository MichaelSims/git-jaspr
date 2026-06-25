package sims.michael.gitjaspr

import java.io.File
import java.util.concurrent.TimeUnit
import org.eclipse.jgit.lib.Constants
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef

class CliGitClient(
    override val workingDirectory: File,
    override val remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
) : GitClient {

    private val logger = LoggerFactory.getLogger(CliGitClient::class.java)

    /** When true, git commands write their stderr (e.g. progress) directly to the terminal. */
    var showStderr = false

    override fun init(): GitClient {
        logger.trace("init")
        require(workingDirectory.exists() || workingDirectory.mkdir()) {
            "Working directory does not exist and could not be created: $workingDirectory"
        }
        return apply { executeCommand(listOf("git", "init", "-b", "main")) }
    }

    override fun checkout(refName: String, reflogMessage: String?): GitClient = apply {
        logger.trace("checkout {}", refName)
        executeCommand(listOf("git", "checkout", refName), reflogEnv(reflogMessage))
    }

    override fun clone(uri: String, remoteName: String, bare: Boolean): GitClient {
        logger.trace("clone {} {}", uri, bare)
        // The CLI doesn't support file:// URIs, so we need to strip the prefix
        val sanitizedUri = uri.removePrefix("file:")
        require(workingDirectory.exists() || workingDirectory.mkdir()) {
            "Working directory does not exist and could not be created: $workingDirectory"
        }
        val command = buildList {
            add("git")
            add("clone")
            if (bare) {
                add("--bare")
            }
            add("--origin")
            add(remoteName)
            add(sanitizedUri)
            add(workingDirectory.absolutePath)
        }
        return apply {
            executeCommand(command)
            // Remove refs/remotes/<remoteName>/HEAD to match JGitClient's behavior
            executeCommand(listOf("git", "remote", "set-head", remoteName, "-d"))
        }
    }

    override fun fetch(remoteName: String, prune: Boolean) {
        logger.trace("fetch {}{}", remoteName, if (prune) " (with prune)" else "")
        executeCommand(
            buildList {
                add("git")
                add("fetch")
                if (prune) {
                    add("--prune")
                }
                add(remoteName)
            }
        )
    }

    override fun log(): List<Commit> {
        logger.trace("log")
        return gitLog().reversed()
    }

    override fun log(revision: String, maxCount: Int): List<Commit> {
        logger.trace("log {} {}", revision, maxCount)
        return if (maxCount == -1) gitLog(revision) else gitLog(revision, "-$maxCount")
    }

    override fun logAll(): List<Commit> {
        logger.trace("logAll")
        return gitLog("--all").reversed()
    }

    override fun logRange(since: String, until: String): List<Commit> {
        logger.trace("logRange {}..{}", since, until)
        return gitLog("$since..$until").reversed()
    }

    override fun getParents(commit: Commit): List<Commit> {
        logger.trace("getParents {}", commit)
        return executeCommand(listOf("git", "log", commit.hash, "--pretty=%P", "-1"))
            .output
            .string
            .split(" ")
            .flatMap { parent -> log(parent.trim(), 1) }
            .also { logger.trace("getParents {} {}", commit, it) }
    }

    override fun hasUncommittedChangesToTrackedFiles(): Boolean {
        logger.trace("hasUncommittedChangesToTrackedFiles")
        return executeCommand(listOf("git", "status", "-s", "--untracked-files=no"))
            .output
            .lines
            .isNotEmpty()
    }

    override fun getCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit> {
        logger.trace("getCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return logRange("$remoteName/$targetRefName", localObjectName)
    }

    override fun getBranchNames(): List<String> {
        logger.trace("getBranchNames")
        return executeCommand(listOf("git", "branch", "-a", "-l", "--format=%(refname:short)"))
            .output
            .lines
            .map { line -> if (line.contains("HEAD detached")) "HEAD" else line }
    }

    override fun remoteBranchExists(remoteName: String, branchName: String): Boolean {
        logger.trace("remoteBranchExists {} {}", remoteName, branchName)
        // ls-remote queries the remote directly, so it sees a branch deletion that a stale local
        // remote-tracking ref would still report as present.
        return executeCommand(
                listOf("git", "ls-remote", "--heads", remoteName, "refs/heads/$branchName")
            )
            .output
            .string
            .isNotBlank()
    }

    override fun getRemoteBranches(remoteName: String): List<RemoteBranch> {
        logger.trace("getRemoteBranches {}", remoteName)
        val command =
            listOf(
                "git",
                "branch",
                "-r",
                "-l",
                "--format=%(refname:lstrip=2)${GIT_FORMAT_SEPARATOR}%(objectname:short)",
            )
        // Collect branch names and hashes first
        data class BranchWithHash(val name: String, val hash: String)
        val branchesWithHashes =
            executeCommand(command).output.lines.mapNotNull { line ->
                val (nameWithRemote, hash) = line.split(GIT_FORMAT_SEPARATOR)
                val (thisRemoteName, name) = nameWithRemote.split("/", limit = 2)
                if (thisRemoteName == remoteName && name != Constants.HEAD) {
                    BranchWithHash(name, hash)
                } else {
                    null
                }
            }
        // Batch fetch all commits
        val hashes = branchesWithHashes.map(BranchWithHash::hash)
        val commits = getCommits(hashes)
        // Build the result
        return branchesWithHashes.mapNotNull { (name, hash) ->
            commits[hash]?.let { commit -> RemoteBranch(name, commit) }
        }
    }

    override fun getRemoteBranchesById(remoteName: String): Map<String, RemoteBranch> {
        logger.trace("getRemoteBranchesById")
        return getRemoteBranches(remoteName)
            .mapNotNull { branch ->
                RemoteRef.parse(branch.name, remoteBranchPrefix)
                    ?.takeIf { parts -> parts.revisionNum == null } // Filter history branches
                    ?.let { it.commitId to branch }
            }
            .toMap()
    }

    override fun reset(refName: String, reflogMessage: String?): GitClient {
        logger.trace("reset {}", refName)
        return apply {
            executeCommand(
                listOf("git", "reset", "--hard", refName),
                reflogEnv(reflogMessage),
            )
        }
    }

    override fun resetMixed(refName: String, reflogMessage: String?): GitClient {
        logger.trace("resetMixed {}", refName)
        return apply {
            executeCommand(listOf("git", "reset", refName), reflogEnv(reflogMessage))
        }
    }

    override fun resetSoft(refName: String, reflogMessage: String?): GitClient {
        logger.trace("resetSoft {}", refName)
        return apply {
            executeCommand(
                listOf("git", "reset", "--soft", refName),
                reflogEnv(reflogMessage),
            )
        }
    }

    override fun cleanUntracked(): GitClient {
        logger.trace("cleanUntracked")
        return apply { executeCommand(listOf("git", "clean", "-d", "-f")) }
    }

    override fun branch(
        name: String,
        startPoint: String,
        force: Boolean,
        reflogMessage: String?,
    ): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old =
            if (refExists(name)) {
                log(name, 1).single()
            } else {
                null
            }
        if (force) {
            // `git branch -f` refuses to update a branch that's checked out in any worktree
            // (including the current one). JGit's branchCreate with setForce(true) updates the
            // ref directly without that check, and jaspr relied on the permissive behavior --
            // both endNavSession (updating the source branch from a detached HEAD that may
            // have wandered) and various test fixtures hit this. Use `git update-ref`, which
            // bypasses the worktree check, to match the prior semantics.
            val resolved =
                executeCommand(listOf("git", "rev-parse", startPoint)).output.string.trim()
            executeCommand(
                listOf("git", "update-ref", refsHeads(name), resolved),
                reflogEnv(reflogMessage),
            )
        } else {
            executeCommand(
                listOf("git", "branch", name, startPoint),
                reflogEnv(reflogMessage),
            )
        }
        return old
    }

    override fun refExists(ref: String): Boolean {
        logger.trace("refExists {}", ref)
        // Using --verify requires fully qualified ref names
        // TODO eventually don't check the prefix
        val prefixedRef =
            if (ref.startsWith(GitClient.R_HEADS) || ref.startsWith(GitClient.R_REMOTES)) {
                ref
            } else {
                refsHeads(ref)
            }
        return ProcessExecutor()
            .directory(workingDirectory)
            .command(listOf("git", "show-ref", "--verify", "--quiet", prefixedRef))
            .destroyOnExit()
            .readOutput(true)
            .execute()
            .exitValue
            .let { exitValue -> exitValue == 0 }
    }

    override fun deleteBranches(names: List<String>, force: Boolean): List<String> {
        logger.trace("deleteBranches {} {}", names, force)
        val filteredNames = names.filter { name -> refExists(name) }
        if (filteredNames.isNotEmpty()) {
            val forceOption = if (force) listOf("-D") else listOf("-d")
            executeCommand(listOf("git", "branch") + forceOption + filteredNames)
        }
        return names
    }

    override fun add(filePattern: String): GitClient = apply {
        logger.trace("add {}", filePattern)
        executeCommand(listOf("git", "add", filePattern))
    }

    override fun setCommitId(commitId: String, committer: Ident?, author: Ident?) {
        logger.trace("setCommitId {} {} {}", commitId, committer, author)
        val head = log("HEAD", 1).single()
        require(!CommitParsers.getFooters(head.fullMessage).containsKey("commit-id")) {
            "Commit already has a commit-id footer: $head"
        }
        val shouldResetAuthor = author != null && head.author != author
        executeCommand(
            buildList {
                addAll(listOf("git", "commit", "--amend"))
                if (shouldResetAuthor) {
                    addAll(listOf("--reset-author"))
                }
                addAll(
                    listOf(
                        "-m",
                        CommitParsers.addFooters(
                            head.fullMessage,
                            mapOf(COMMIT_ID_LABEL to commitId),
                        ),
                    )
                )
            },
            getIdentEnvironmentMap(committer, author?.takeIf { shouldResetAuthor }),
        )
    }

    override fun commit(
        message: String?,
        footerLines: Map<String, String>?,
        committer: Ident?,
        author: Ident?,
        amend: Boolean,
        reflogMessage: String?,
    ): Commit {
        logger.trace("commit {} {} {} {} {}", message, footerLines, committer, author, amend)

        require(amend || message != null) { "Message is required unless amending the HEAD commit" }

        val command = buildList {
            add("git")
            add("commit")
            // Allow empty so commits with no tree changes (e.g. --allow-empty) don't get rejected
            add("--allow-empty")
            if (amend) {
                add("--amend")
            }

            if (message != null || footerLines != null) {
                val existingFullMessage: String?
                val existingFooterLines: Map<String, String>?
                if (amend) {
                    val head = log("HEAD", 1).single()
                    existingFullMessage = head.fullMessage
                    existingFooterLines = CommitParsers.getFooters(existingFullMessage)
                } else {
                    existingFullMessage = null
                    existingFooterLines = null
                }
                val footers = footerLines ?: existingFooterLines ?: emptyMap()
                val newMessage =
                    message ?: CommitParsers.trimFooters(checkNotNull(existingFullMessage))
                add("-m")
                add(CommitParsers.addFooters(newMessage, footers))
            } else {
                add("--no-edit")
            }

            if (author != null && amend) {
                add("--reset-author")
            }
        }
        executeCommand(
            command,
            getIdentEnvironmentMap(committer, author) + reflogEnv(reflogMessage),
        )
        return log("HEAD", 1).single()
    }

    override fun cherryPick(
        commit: Commit,
        committer: Ident?,
        author: Ident?,
        useTheirs: Boolean,
        reflogMessage: String?,
    ): Commit {
        logger.trace("cherryPick {} {} {} useTheirs={}", commit, committer, author, useTheirs)
        val env = getIdentEnvironmentMap(committer, author) + reflogEnv(reflogMessage)
        val command = buildCherryPickCommand(commit.hash, useTheirs)
        executeCommand(command, env)
        if (author != null && log("HEAD", 1).single().author != author) {
            logger.debug(
                "cherryPick: resetting author to {} after cherry-pick via commit --amend",
                author,
            )
            executeCommand(listOf("git", "commit", "--amend", "--no-edit", "--reset-author"), env)
        }

        return log("HEAD", 1).single()
    }

    override fun push(refSpecs: List<RefSpec>, remoteName: String) {
        logger.trace("push {}", refSpecs)
        val filteredRefSpecs =
            refSpecs
                .filterNot { refSpec ->
                    // Cli push doesn't like it when you try to force push a branch that doesn't
                    // exist. Since we want it deleted anyway, don't complain, just filter it out
                    refSpec.localRef == FORCE_PUSH_PREFIX &&
                        !refExists(refsRemotes(refSpec.remoteRef, remoteName))
                }
                .map { refSpec ->
                    // In this context we want to use the full ref name, so we can push HEAD to new
                    // branches
                    refSpec.copy(remoteRef = refsHeads(refSpec.remoteRef))
                }
        if (filteredRefSpecs != refSpecs) {
            logger.trace("Filtered refSpecs to {}", filteredRefSpecs)
        }
        if (filteredRefSpecs.isEmpty()) {
            logger.info("push: No refSpecs to push")
        } else {
            executeCommand(
                listOf("git", "push", remoteName, "--atomic") +
                    filteredRefSpecs.map(RefSpec::toString)
            )
        }
    }

    override fun pushWithLease(
        refSpecs: List<RefSpec>,
        remoteName: String,
        forceWithLeaseRefs: Map<String, String?>,
    ) {
        logger.trace("pushWithLease {} with lease refs {}", refSpecs, forceWithLeaseRefs)
        val filteredRefSpecs = refSpecs.map { refSpec ->
            // In this context we want to use the full ref name, so we can push HEAD to new
            // branches
            refSpec.copy(remoteRef = refsHeads(refSpec.remoteRef))
        }

        if (filteredRefSpecs.isEmpty()) {
            logger.info("pushWithLease: No refSpecs to push")
        } else {
            val forceWithLeaseArgs = forceWithLeaseRefs.flatMap { (ref, expectedValue) ->
                val fullRef = refsHeads(ref)
                if (expectedValue == null) {
                    // Ref must not exist
                    listOf("--force-with-lease=$fullRef:")
                } else {
                    // Ref must have specific value
                    listOf("--force-with-lease=$fullRef:$expectedValue")
                }
            }

            try {
                executeCommand(
                    listOf("git", "push", remoteName, "--atomic") +
                        forceWithLeaseArgs +
                        filteredRefSpecs.map(RefSpec::toString)
                )
            } catch (e: GitJasprException) {
                // Auth failures bubble up as GitJasprExceptions so let's just rethrow those
                throw e
            } catch (e: Exception) {
                throw PushFailedException("Push with lease failed: ${e.message}", e)
            }
        }
    }

    override fun getRemoteUriOrNull(remoteName: String): String? {
        // Intentionally avoiding trace logging since this is called during initialization and shows
        // up in the output of --show-config, which I want to avoid. It might be better in the
        // future to either log everything to STDERR or conditionally log to STDERR depending on
        // the command + options (i.e., jaspr status --show-config should log to STDERR to
        // separate logging from that command's output).
        return executeCommand(listOf("git", "remote", "get-url", remoteName))
            .output
            .string
            .trim()
            .takeIf(String::isNotBlank)
    }

    override fun addRemote(remoteName: String, remoteUri: String) {
        logger.trace("addRemote {} {}", remoteName, remoteUri)
        executeCommand(listOf("git", "remote", "add", remoteName, remoteUri))
    }

    override fun getConfigValue(key: String): String? {
        logger.trace("getConfigValue {}", key)
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                .command(listOf("git", "config", "--get", key))
                .destroyOnExit()
                .readOutput(true)
                .execute()
        // git config --get returns 1 when the key is not found
        return if (result.exitValue == 0) {
            result.output.string.trim().takeIf(String::isNotBlank)
        } else {
            null
        }
    }

    override fun setConfigValue(key: String, value: String) {
        logger.trace("setConfigValue {} {}", key, value)
        executeCommand(listOf("git", "config", key, value))
    }

    override fun getUpstreamBranch(remoteName: String): RemoteBranch? {
        logger.trace("getUpstreamBranch {}", remoteName)
        if (isHeadDetached()) {
            return null
        }
        val prefix = "$remoteName/"
        return executeCommand(
                listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
            )
            .output
            .string
            .trim()
            .takeIf(String::isNotBlank)
            ?.takeIf { name -> name.startsWith(prefix) }
            ?.let { trackingBranchName ->
                val trackingBranchSimpleName = trackingBranchName.removePrefix(prefix)
                getRemoteBranches(remoteName).firstOrNull { branch ->
                    branch.name == trackingBranchSimpleName
                }
            }
    }

    override fun setUpstreamBranch(remoteName: String, branchName: String) {
        logger.trace("setUpstreamBranch {} {}", remoteName, branchName)
        check(!isHeadDetached()) { "Cannot set upstream branch when in detached HEAD" }
        executeCommand(listOf("git", "branch", "--set-upstream-to", "$remoteName/$branchName"))
    }

    override fun getUpstreamBranchName(localBranch: String, remoteName: String): String? {
        logger.trace("getUpstreamBranchName {} {}", localBranch, remoteName)
        val merge =
            getConfigValue("branch.$localBranch.merge")?.removePrefix(Constants.R_HEADS)
                ?: return null
        val remote = getConfigValue("branch.$localBranch.remote") ?: return null
        return if (remote == remoteName) merge else null
    }

    override fun setUpstreamBranchForLocalBranch(
        localBranch: String,
        remoteName: String,
        remoteBranchName: String?,
    ) {
        logger.trace(
            "setUpstreamBranchForLocalBranch {} {} {}",
            localBranch,
            remoteName,
            remoteBranchName,
        )
        if (remoteBranchName != null) {
            setConfigValue("branch.$localBranch.remote", remoteName)
            setConfigValue("branch.$localBranch.merge", "${Constants.R_HEADS}$remoteBranchName")
        } else {
            executeCommand(listOf("git", "branch", "--unset-upstream", localBranch))
        }
    }

    override fun reflog(): List<Commit> {
        logger.trace("reflog")
        return gitLog("-g")
    }

    override fun getCurrentBranchName(): String {
        logger.trace("getCurrentBranchName")
        return executeCommand(listOf("git", "branch", "--show-current")).output.string.trim()
    }

    override fun isHeadDetached(): Boolean {
        logger.trace("isHeadDetached")
        return getCurrentBranchName().isEmpty()
    }

    override fun getShortMessages(refs: List<String>): Map<String, String?> {
        logger.trace("getShortMessages {}", refs)
        if (refs.isEmpty()) return emptyMap()
        // Get all full hashes in one call
        val fullHashes =
            executeCommand(listOf("git", "rev-parse") + refs)
                .output
                .lines
                .filter(String::isNotBlank)
        val refToFullHash = refs.zip(fullHashes).toMap()
        // Get all subjects in one call
        val format = "%H${GIT_FORMAT_SEPARATOR}%s"
        val hashToSubject =
            executeCommand(listOf("git", "log", "--no-walk", "--format=$format") + refs)
                .output
                .lines
                .filter(String::isNotBlank)
                .associate { line ->
                    val (fullHash, subject) = line.split(GIT_FORMAT_SEPARATOR, limit = 2)
                    fullHash to subject
                }
        return refs.associateWith { ref -> refToFullHash[ref]?.let { hashToSubject[it] } }
    }

    override fun getCommits(refs: List<String>): Map<String, Commit?> {
        logger.trace("getCommits {}", refs)
        if (refs.isEmpty()) return emptyMap()
        // Get all full hashes in one call
        val fullHashes =
            executeCommand(listOf("git", "rev-parse") + refs)
                .output
                .lines
                .filter(String::isNotBlank)
        val refToFullHash = refs.zip(fullHashes).toMap()
        // Get all commits in one call, prepending full hash for mapping
        val prettyFormat =
            listOf(
                    "--pretty=format:%H", // full hash for mapping
                    "%h", // short hash for Commit
                    "%s", // subject
                    "%aN", // author name
                    "%aE", // author email
                    "%cN", // committer name
                    "%cE", // committer email
                    "%(trailers:key=commit-id,separator=$GIT_LOG_TRAILER_SEPARATOR,valueonly=true)",
                    "%ct", // commit timestamp
                    "%at", // author timestamp
                    "%B", // raw body
                )
                .joinToString(GIT_FORMAT_SEPARATOR)
        val hashToCommit =
            executeCommand(listOf("git", "log", "--no-walk", "-z", prettyFormat) + refs)
                .output
                .string
                .split('\u0000')
                .filter(String::isNotBlank)
                .associate { entry ->
                    val fullHash = entry.substringBefore(GIT_FORMAT_SEPARATOR)
                    val commitEntry = entry.substringAfter(GIT_FORMAT_SEPARATOR)
                    fullHash to CommitParsers.parseCommitLogEntry(commitEntry)
                }
        return refs.associateWith { ref -> refToFullHash[ref]?.let { hashToCommit[it] } }
    }

    private fun reflogEnv(reflogMessage: String?): Map<String, String> =
        if (reflogMessage != null) mapOf("GIT_REFLOG_ACTION" to reflogMessage) else emptyMap()

    private fun getIdentEnvironmentMap(committer: Ident?, author: Ident?) = buildMap {
        if (committer != null) {
            put("GIT_COMMITTER_NAME", committer.name)
            put("GIT_COMMITTER_EMAIL", committer.email)
            if (author == null) {
                // If only committer is set, also set author to the same
                put("GIT_AUTHOR_NAME", committer.name)
                put("GIT_AUTHOR_EMAIL", committer.email)
            }
        }
        if (author != null) {
            put("GIT_AUTHOR_NAME", author.name)
            put("GIT_AUTHOR_EMAIL", author.email)
        }
    }

    private fun gitLog(vararg logArg: String): List<Commit> {
        // Thanks to https://www.nushell.sh/cookbook/parsing_git_log.html for inspiration here
        val prettyFormat =
            listOf(
                    "--pretty=format:%h", // hash
                    "%s", // subject
                    "%aN", // author name
                    "%aE", // author email
                    "%cN", // committer name
                    "%cE", // committer email
                    "%(trailers:key=commit-id,separator=$GIT_LOG_TRAILER_SEPARATOR,valueonly=true)", // trailers
                    "%ct", // commit timestamp
                    "%at", // author timestamp
                    "%B", // raw body (subject and body)
                )
                .joinToString(GIT_FORMAT_SEPARATOR)

        return executeCommand(listOf("git", "log") + logArg.toList() + listOf("-z", prettyFormat))
            .output
            .string
            .split('\u0000')
            .filter(String::isNotBlank)
            .map(CommitParsers::parseCommitLogEntry)
    }

    override fun mergeBase(a: String, b: String): String? {
        logger.trace("mergeBase {} {}", a, b)
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                .command(listOf("git", "merge-base", a, b))
                .destroyOnExit()
                .readOutput(true)
                .apply { if (showStderr) redirectError(System.err) }
                .execute()
        return when (result.exitValue) {
            0 -> result.output.string.trim().takeIf(String::isNotEmpty)
            1 -> null
            else ->
                error("git merge-base $a $b returned ${result.exitValue}: ${result.output.string}")
        }
    }

    override fun isAncestor(ancestor: String, descendant: String): Boolean {
        logger.trace("isAncestor {} {}", ancestor, descendant)
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                .command(listOf("git", "merge-base", "--is-ancestor", ancestor, descendant))
                .destroyOnExit()
                .readOutput(true)
                .apply { if (showStderr) redirectError(System.err) }
                .execute()
        return when (result.exitValue) {
            0 -> true
            1 -> false
            else ->
                error(
                    "git merge-base --is-ancestor $ancestor $descendant returned " +
                        "${result.exitValue}: ${result.output.string}"
                )
        }
    }

    override fun updateRef(refName: String, sha: String) {
        logger.trace("updateRef {} {}", refName, sha)
        executeCommand(listOf("git", "update-ref", refName, sha))
    }

    override fun gitDir(): File {
        logger.trace("gitDir")
        val output = executeCommand(listOf("git", "rev-parse", "--git-dir")).output.string.trim()
        return workingDirectory.resolve(output).canonicalFile
    }

    override fun gitCommonDir(): File {
        logger.trace("gitCommonDir")
        val output =
            executeCommand(listOf("git", "rev-parse", "--git-common-dir")).output.string.trim()
        return workingDirectory.resolve(output).canonicalFile
    }

    override fun addWorktree(path: File, ref: String?, detached: Boolean) {
        logger.trace("addWorktree path={} ref={} detached={}", path, ref, detached)
        val command = buildList {
            add("git")
            add("worktree")
            add("add")
            if (detached) add("--detach")
            add(path.absolutePath)
            if (ref != null) add(ref)
        }
        executeCommand(command)
    }

    override fun removeWorktree(path: File, force: Boolean) {
        logger.trace("removeWorktree path={} force={}", path, force)
        val command = buildList {
            add("git")
            add("worktree")
            add("remove")
            if (force) add("--force")
            add(path.absolutePath)
        }
        executeCommand(command)
    }

    override fun cherryPickAbort() {
        logger.trace("cherryPickAbort")
        executeCommand(listOf("git", "cherry-pick", "--abort"))
    }

    override fun tryCherryPick(
        commit: Commit,
        committer: Ident?,
        author: Ident?,
        useTheirs: Boolean,
        reflogMessage: String?,
    ): CherryPickResult {
        logger.trace("tryCherryPick {} {} {} useTheirs={}", commit, committer, author, useTheirs)
        val env = getIdentEnvironmentMap(committer, author) + reflogEnv(reflogMessage)
        val command = buildCherryPickCommand(commit.hash, useTheirs)
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                .environment(env)
                .command(command)
                .destroyOnExit()
                .readOutput(true)
                .apply { if (showStderr) redirectError(System.err) }
                .execute()
        return when {
            result.exitValue == 0 -> {
                if (author != null && log("HEAD", 1).single().author != author) {
                    logger.debug(
                        "tryCherryPick: resetting author to {} after cherry-pick via commit --amend",
                        author,
                    )
                    executeCommand(
                        listOf("git", "commit", "--amend", "--no-edit", "--reset-author"),
                        env,
                    )
                }
                CherryPickResult.Success(log("HEAD", 1).single())
            }
            isCherryPickInProgress() -> CherryPickResult.LeftInProgress
            else ->
                error(
                    "cherry-pick failed with exit ${result.exitValue} but no cherry-pick is in " +
                        "progress: ${result.output.string}"
                )
        }
    }

    private fun buildCherryPickCommand(commitHash: String, useTheirs: Boolean): List<String> {
        return buildList {
            add("git")
            add("cherry-pick")
            add("--allow-empty")
            if (useTheirs) {
                add("-X")
                add("theirs")
            }
            add(commitHash)
        }
    }

    override fun stashPush(
        refName: String,
        message: String,
        includeUntracked: Boolean,
    ): String? {
        logger.trace(
            "stashPush refName={} message={} includeUntracked={}",
            refName,
            message,
            includeUntracked,
        )
        val command = buildList {
            add("git")
            add("stash")
            add("push")
            if (includeUntracked) add("--include-untracked")
            add("-m")
            add(message)
        }
        val result = executeCommand(command)
        // `git stash push` exits 0 whether or not a stash was created. The only reliable signal
        // is the output: a successful stash prints "Saved working directory and index state ...";
        // a no-op prints "No local changes to save". Parse the output rather than checking the
        // stash stack to avoid races with concurrent stash use.
        val output = result.output.string
        if ("No local changes to save" in output) return null
        val sha = executeCommand(listOf("git", "rev-parse", "stash@{0}")).output.string.trim()
        // Relocate the stash-shaped commit off the stash stack and into the caller's namespace.
        // update-ref runs before drop so a crash between the two leaves a recoverable duplicate
        // (the operator sees the entry in both places) rather than losing the commit entirely.
        executeCommand(listOf("git", "update-ref", refName, sha))
        executeCommand(listOf("git", "stash", "drop"))
        return sha
    }

    override fun getTree(ref: String): String {
        logger.trace("getTree {}", ref)
        return executeCommand(listOf("git", "rev-parse", "$ref^{tree}")).output.string.trim()
    }

    override fun patchId(sha: String): String? {
        logger.trace("patchId {}", sha)
        return try {
            val pipeline =
                ProcessBuilder.startPipeline(
                    listOf(
                        ProcessBuilder("git", "show", sha).directory(workingDirectory),
                        ProcessBuilder("git", "patch-id", "--stable").directory(workingDirectory),
                    )
                )
            val tail = pipeline.last()
            val output = tail.inputStream.bufferedReader().readText().trim()
            val showRc = pipeline.first().waitFor()
            val patchIdRc = tail.waitFor()
            if (showRc != 0 || patchIdRc != 0 || output.isEmpty()) {
                null
            } else {
                output.substringBefore(' ').takeIf(String::isNotEmpty)
            }
        } catch (e: Exception) {
            logger.debug("Failed to compute patch-id for {}", sha, e)
            null
        }
    }

    override fun mergeTreeWriteTree(
        base: String,
        ours: String,
        theirs: String,
        useTheirs: Boolean,
    ): MergeTreeResult {
        logger.trace(
            "mergeTreeWriteTree base={} ours={} theirs={} useTheirs={}",
            base,
            ours,
            theirs,
            useTheirs,
        )
        val command = buildList {
            add("git")
            add("merge-tree")
            add("--write-tree")
            add("--name-only")
            add("--no-messages")
            if (useTheirs) {
                add("-X")
                add("theirs")
            }
            add("--merge-base=$base")
            add(ours)
            add(theirs)
        }
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                .command(command)
                .destroyOnExit()
                .readOutput(true)
                .apply { if (showStderr) redirectError(System.err) }
                .execute()
        // `git merge-tree --write-tree` exits 0 on a clean merge (prints just the tree SHA),
        // exits 1 on a merge with conflicts (prints tree SHA, then one path per line for each
        // conflicting path with --name-only; --no-messages suppresses the trailing
        // human-readable "Auto-merging" / "CONFLICT (...)" lines that would otherwise mix in
        // with the path list), and exits >1 on unrecoverable errors (invalid args, etc.).
        return when (result.exitValue) {
            0 -> {
                val tree =
                    result.output.lines.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
                        ?: error("git merge-tree returned exit 0 but no tree SHA")
                MergeTreeResult.Clean(tree)
            }
            1 -> {
                val paths = result.output.lines.drop(1).map(String::trim).filter(String::isNotEmpty)
                MergeTreeResult.Conflict(paths)
            }
            else ->
                error(
                    "git merge-tree --write-tree returned ${result.exitValue}: " +
                        result.output.string
                )
        }
    }

    private fun executeCommand(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val result =
            ProcessExecutor()
                .directory(workingDirectory)
                // GIT_TERMINAL_PROMPT=0 makes git fail fast with a parseable error instead of
                // dropping the user into an interactive Username/Password prompt mid-command. jaspr
                // isn't an interactive git shell, and a half-rendered credential prompt is worse
                // than an immediate failure we can explain.
                .environment(environment + ("GIT_TERMINAL_PROMPT" to "0"))
                .command(command)
                .destroyOnExit()
                .readOutput(true)
                .apply { if (showStderr) redirectError(System.err) }
                .execute()
        val exitValue = result.exitValue
        if (exitValue != 0) {
            val output = result.output.string
            val authFailureMessage = gitAuthFailureMessageOrNull(output, ::isGhAuthenticated)
            if (authFailureMessage != null) {
                // Surface the authentication failure as a GitJasprException for proper handling
                throw GitJasprException(authFailureMessage)
            } else {
                throw IllegalArgumentException("Command returned $exitValue: $output")
            }
        }
        return result
    }

    /**
     * Probes whether the GitHub CLI (`gh`) is installed and logged in, so HTTPS auth-failure
     * guidance can recommend `gh auth setup-git` specifically. Returns false (quietly) when `gh` is
     * missing or not authenticated; this runs only on the rare auth-failure path.
     */
    private fun isGhAuthenticated(): Boolean =
        try {
            ProcessExecutor()
                .directory(workingDirectory)
                .command("gh", "auth", "status")
                .destroyOnExit()
                .readOutput(true)
                .timeout(GH_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute()
                .exitValue == 0
        } catch (e: Exception) {
            logger.debug("gh auth status probe failed; omitting gh-specific guidance", e)
            false
        }

    companion object {
        const val GIT_FORMAT_SEPARATOR = "»¦«"
        const val GIT_LOG_TRAILER_SEPARATOR = "{^}"
        private const val GH_PROBE_TIMEOUT_SECONDS = 5L
    }
}

private val AUTH_FAILURE_SIGNATURES =
    listOf(
        "Authentication failed",
        "Invalid username or token",
        "Password authentication is not supported",
        "could not read Username",
        "could not read Password",
        "terminal prompts disabled",
        "Permission denied (publickey)",
    )

private val SSH_REMOTE_SIGNATURES = listOf("git@", "ssh://", "Permission denied (publickey)")

/**
 * If [gitOutput] indicates the git CLI failed to authenticate to a remote, returns an actionable
 * error message explaining how to fix it; otherwise returns null so the caller can fall back to its
 * generic failure handling.
 *
 * git-jaspr shells out to the git CLI, so pushes and fetches authenticate with the user's git
 * transport credentials, not the GitHub API token in `~/.jaspr.properties`. A new user who has set
 * up git via GitHub Desktop or a browser often has no credential helper configured, so git falls
 * back to the (unsupported by GitHub) username/password prompt and fails. We want to explain that
 * rather than render the generic "you've likely encountered a bug" banner.
 *
 * @param ghIsAuthenticated probe for whether the GitHub CLI is installed and logged in; when it
 *   returns true the HTTPS guidance recommends `gh auth setup-git` directly.
 */
fun gitAuthFailureMessageOrNull(
    gitOutput: String,
    ghIsAuthenticated: () -> Boolean = { false },
): String? {
    val isAuthFailure = AUTH_FAILURE_SIGNATURES.any { signature ->
        gitOutput.contains(signature, ignoreCase = true)
    }
    if (!isAuthFailure) return null

    val isSsh = SSH_REMOTE_SIGNATURES.any { signature ->
        gitOutput.contains(signature, ignoreCase = true)
    }

    return buildList {
            add("Authentication to the git remote failed.")
            add("")
            add(
                "jaspr runs the git CLI under the hood, so pushes and fetches use your git " +
                    "credentials, not the GitHub token in ~/$CONFIG_FILE_NAME (that token is only " +
                    "used for GitHub's API). If `git push` fails the same way, fixing git fixes jaspr."
            )
            add("")
            if (isSsh) {
                add("Your SSH key isn't authorized for this remote. Add a key to your GitHub")
                add("account, load it into ssh-agent, then retry:")
                add("  https://docs.github.com/authentication/connecting-to-github-with-ssh")
            } else {
                add("Your git CLI isn't set up to authenticate over HTTPS. Fix it with one of:")
                if (ghIsAuthenticated()) {
                    add("  • You're logged in to the GitHub CLI; point git at those credentials:")
                    add("      gh auth setup-git")
                } else {
                    add("  • Log in with the GitHub CLI and use it as your credential helper:")
                    add("      gh auth login && gh auth setup-git")
                }
                add("  • Or switch the remote to SSH:")
                add("      git remote set-url <remote> git@github.com:<owner>/<repo>.git")
            }
        }
        .joinToString("\n")
}
