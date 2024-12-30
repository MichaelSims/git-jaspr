package sims.michael.gitjaspr

import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts
import java.io.File

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
            },
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
            .also {
                logger.trace("getParents {} {}", commit, it)
            }
    }

    override fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return executeCommand(listOf("git", "status", "-s"))
            .output
            .lines
            .isEmpty()
    }

    override fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
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
        val command = listOf(
            "git",
            "branch",
            "-r",
            "-l",
            "--format=%(refname:lstrip=2)${GIT_FORMAT_SEPARATOR}%(objectname:short)",
        )
        return executeCommand(command)
            .output
            .lines
            .mapNotNull { line ->
                val (nameWithRemote, hash) = line.split(GIT_FORMAT_SEPARATOR)
                val (thisRemoteName, name) = nameWithRemote.split("/", limit = 2)
                if (thisRemoteName == remoteName) {
                    RemoteBranch(name, log(hash, 1).single())
                } else {
                    null
                }
            }
    }

    override fun getRemoteBranchesById(remoteName: String): Map<String, RemoteBranch> {
        logger.trace("getRemoteBranchesById")
        return getRemoteBranches(remoteName)
            .mapNotNull { branch ->
                getRemoteRefParts(branch.name, remoteBranchPrefix)
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
        val old = if (refExists(name)) {
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
        val prefixedRef = if (ref.startsWith(GitClient.R_HEADS) || ref.startsWith(GitClient.R_REMOTES)) {
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

    override fun setCommitId(commitId: String, commitIdent: Ident?) {
        logger.trace("setCommitId {}", commitId)
        val head = log("HEAD", 1).single()
        require(!CommitParsers.getFooters(head.fullMessage).containsKey("commit-id")) {
            "Commit already has a commit-id footer: $head"
        }
        executeCommand(
            listOf(
                "git",
                "commit",
                "--amend",
                "-m",
                CommitParsers.addFooters(head.fullMessage, mapOf(COMMIT_ID_LABEL to commitId)),
            ),
            if (commitIdent != null) {
                mapOf(
                    "GIT_COMMITTER_NAME" to commitIdent.name,
                    "GIT_COMMITTER_EMAIL" to commitIdent.email,
                    "GIT_AUTHOR_NAME" to commitIdent.name,
                    "GIT_AUTHOR_EMAIL" to commitIdent.email,
                )
            } else {
                emptyMap()
            },
        )
    }

    override fun commit(message: String, footerLines: Map<String, String>, commitIdent: Ident?): Commit {
        logger.trace("commit {} {}", message, footerLines)
        executeCommand(
            listOf(
                "git",
                "commit",
                "-m",
                CommitParsers.addFooters(message, footerLines),
            ),
            if (commitIdent != null) {
                mapOf(
                    "GIT_COMMITTER_NAME" to commitIdent.name,
                    "GIT_COMMITTER_EMAIL" to commitIdent.email,
                    "GIT_AUTHOR_NAME" to commitIdent.name,
                    "GIT_AUTHOR_EMAIL" to commitIdent.email,
                )
            } else {
                emptyMap()
            },
        )
        return log("HEAD", 1).single()
    }

    override fun cherryPick(commit: Commit, commitIdent: Ident?): Commit {
        logger.trace("cherryPick {}", commit)
        executeCommand(
            listOf("git", "cherry-pick", commit.hash),
            if (commitIdent != null) {
                mapOf(
                    "GIT_COMMITTER_NAME" to commitIdent.name,
                    "GIT_COMMITTER_EMAIL" to commitIdent.email,
                )
            } else {
                emptyMap()
            },
        )
        return log("HEAD", 1).single()
    }

    override fun push(refSpecs: List<RefSpec>, remoteName: String) {
        logger.trace("push {}", refSpecs)
        val filteredRefSpecs = refSpecs
            .filterNot { refSpec ->
                // Cli push doesn't like it when you try to force push a branch that doesn't exist
                // Since we want it deleted anyway, don't complain, just filter it out
                refSpec.localRef == FORCE_PUSH_PREFIX && !refExists(refsRemotes(refSpec.remoteRef, remoteName))
            }
            .map { refSpec ->
                // In this context we want to use the full ref name, so we can push HEAD to new branches
                refSpec.copy(remoteRef = refsHeads(refSpec.remoteRef))
            }
        if (filteredRefSpecs != refSpecs) {
            logger.trace("Filtered refSpecs to {}", filteredRefSpecs)
        }
        if (filteredRefSpecs.isEmpty()) {
            logger.info("No refSpecs to push")
        } else {
            executeCommand(
                listOf(
                    "git",
                    "push",
                    remoteName,
                    "--atomic",
                ) + filteredRefSpecs.map(RefSpec::toString),
            )
        }
    }

    override fun getRemoteUriOrNull(remoteName: String): String? {
        // Intentionally avoiding trace logging since this is called during initialization and shows up in the output
        // of --show-config, which I want to avoid.
        // It might be better in the future to either log everything to STDERR, or conditionally log to STDERR depending
        // on the command + options (i.e. git jaspr status --show-config should log to STDERR to separate logging from
        // that command's output.
        return executeCommand(listOf("git", "remote", "get-url", remoteName))
            .output
            .string
            .trim()
            .takeIf(String::isNotBlank)
    }

    override fun getUpstreamBranch(remoteName: String): RemoteBranch? {
        logger.trace("getUpstreamBranch {}", remoteName)
        val prefix = "$remoteName/"
        return executeCommand(listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"))
            .output
            .string
            .trim()
            .takeIf(String::isNotBlank)
            ?.takeIf { name -> name.startsWith(prefix) }
            ?.let { trackingBranchName ->
                val trackingBranchSimpleName = trackingBranchName.removePrefix(prefix)
                getRemoteBranches(remoteName).firstOrNull { branch -> branch.name == trackingBranchSimpleName }
            }
    }

    override fun setUpstreamBranch(remoteName: String, branchName: String) {
        logger.trace("setUpstreamBranch {} {}", remoteName, branchName)
        executeCommand(listOf("git", "branch", "--set-upstream-to", "$remoteName/$branchName"))
    }

    override fun reflog(): List<Commit> {
        logger.trace("reflog")
        return gitLog("-g")
    }

    override fun getCurrentBranchName(): String {
        logger.trace("getCurrentBranchName")
        return executeCommand(listOf("git", "branch", "--show-current"))
            .output
            .string
            .trim()
    }

    override fun isHeadDetached(): Boolean {
        logger.trace("isHeadDetached")
        return getCurrentBranchName().isEmpty()
    }

    private fun gitLog(vararg logArg: String): List<Commit> {
        // Thanks to https://www.nushell.sh/cookbook/parsing_git_log.html for inspiration here
        val prettyFormat = listOf(
            "--pretty=format:%h", // hash
            "%s", // subject
            "%cN", // commit name
            "%cE", // commit email
            "%(trailers:key=commit-id,separator=$GIT_LOG_TRAILER_SEPARATOR,valueonly=true)", // trailers
            "%ct", // commit timestamp
            "%at", // author timestamp
            "%B", // raw body (subject + body)
        )
            .joinToString(GIT_FORMAT_SEPARATOR)

        return executeCommand(listOf("git", "log") + logArg.toList() + listOf("-z", prettyFormat))
            .output
            .string
            .split('\u0000')
            .filter(String::isNotBlank)
            .map(CommitParsers::parseCommitLogEntry)
    }

    private fun executeCommand(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
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
