package sims.michael.gitjaspr

import java.io.File
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

    override fun init(): GitClient {
        logger.trace("init")
        require(workingDirectory.exists() || workingDirectory.mkdir()) {
            "Working directory does not exist and could not be created: $workingDirectory"
        }
        return apply { executeCommand(listOf("git", "init", "-b", "main")) }
    }

    override fun checkout(refName: String): GitClient = apply {
        logger.trace("checkout {}", refName)
        executeCommand(listOf("git", "checkout", refName))
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

    override fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return executeCommand(listOf("git", "status", "-s")).output.lines.isEmpty()
    }

    override fun getLocalCommitStack(
        remoteName: String,
        localObjectName: String,
        targetRefName: String,
    ): List<Commit> {
        logger.trace("getLocalCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return logRange("$remoteName/$targetRefName", localObjectName)
    }

    override fun getBranchNames(): List<String> {
        logger.trace("getBranchNames")
        return executeCommand(listOf("git", "branch", "-a", "-l", "--format=%(refname:short)"))
            .output
            .lines
            .map { line -> if (line.contains("HEAD detached")) "HEAD" else line }
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

    override fun reset(refName: String): GitClient {
        logger.trace("reset {}", refName)
        return apply { executeCommand(listOf("git", "reset", "--hard", refName)) }
    }

    override fun branch(name: String, startPoint: String, force: Boolean): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old =
            if (refExists(name)) {
                log(name, 1).single()
            } else {
                null
            }
        val forceOption = if (force) listOf("-f") else emptyList()
        executeCommand(listOf("git", "branch") + forceOption + listOf(name, startPoint))
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
    ): Commit {
        logger.trace("commit {} {} {} {} {}", message, footerLines, committer, author, amend)

        require(amend || message != null) { "Message is required unless amending the HEAD commit" }

        val command = buildList {
            add("git")
            add("commit")
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
        executeCommand(command, getIdentEnvironmentMap(committer, author))
        return log("HEAD", 1).single()
    }

    override fun cherryPick(commit: Commit, committer: Ident?, author: Ident?): Commit {
        logger.trace("cherryPick {} {} {}", commit, committer, author)
        val env = getIdentEnvironmentMap(committer, author)
        executeCommand(listOf("git", "cherry-pick", commit.hash), env)
        if (author != null && log("HEAD", 1).single().author != author) {
            logger.debug("Resetting author to {} after cherry-pick via commit --amend", author)
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
        val filteredRefSpecs =
            refSpecs.map { refSpec ->
                // In this context we want to use the full ref name, so we can push HEAD to new
                // branches
                refSpec.copy(remoteRef = refsHeads(refSpec.remoteRef))
            }

        if (filteredRefSpecs.isEmpty()) {
            logger.info("pushWithLease: No refSpecs to push")
        } else {
            val forceWithLeaseArgs =
                forceWithLeaseRefs.flatMap { (ref, expectedValue) ->
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
            } catch (e: Exception) {
                throw PushFailedException("Push with lease failed: ${e.message}", e)
            }
        }
    }

    override fun getRemoteUriOrNull(remoteName: String): String? {
        // Intentionally avoiding trace logging since this is called during initialization and shows
        // up in the output of --show-config, which I want to avoid. It might be better in the
        // future to either log everything to STDERR or conditionally log to STDERR depending on
        // the command + options (i.e., git jaspr status --show-config should log to STDERR to
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
        return executeCommand(listOf("git", "config", "--get", key))
            .output
            .string
            .trim()
            .takeIf(String::isNotBlank)
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

    private fun executeCommand(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        return ProcessExecutor()
            .directory(workingDirectory)
            .environment(environment)
            .command(command)
            .destroyOnExit()
            .readOutput(true)
            .execute()
            .also(ProcessResult::requireZeroExitValue)
    }

    companion object {
        const val GIT_FORMAT_SEPARATOR = "»¦«"
        const val GIT_LOG_TRAILER_SEPARATOR = "{^}"
    }
}

private fun ProcessResult.requireZeroExitValue() {
    val exitValue = exitValue
    require(exitValue == 0) { "Command returned $exitValue: ${output.string}" }
}
