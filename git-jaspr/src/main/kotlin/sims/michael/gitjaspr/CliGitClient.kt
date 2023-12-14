package sims.michael.gitjaspr

import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult
import java.io.File

class CliGitClient(
    override val workingDirectory: File,
    override val remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
) : GitClient {

    override fun init(): GitClient {
        TODO("Not yet implemented")
    }

    override fun checkout(refName: String): GitClient {
        TODO("Not yet implemented")
    }

    override fun clone(uri: String): GitClient {
        TODO("Not yet implemented")
    }

    override fun fetch(remoteName: String) {
        TODO("Not yet implemented")
    }

    override fun log(): List<Commit> = gitLog().reversed()

    override fun log(revision: String, maxCount: Int): List<Commit> = gitLog(revision, "-$maxCount")

    override fun logAll(): List<Commit> = gitLog("--all").sortedBy(Commit::hash)

    override fun logRange(since: String, until: String): List<Commit> = gitLog("$since..$until").reversed()

    override fun getParents(commit: Commit): List<Commit> {
        TODO("Not yet implemented")
    }

    override fun isWorkingDirectoryClean(): Boolean = ProcessExecutor()
        .directory(workingDirectory)
        .command(listOf("git", "status", "-s"))
        .destroyOnExit()
        .readOutput(true)
        .execute()
        .also(ProcessResult::requireZeroExitValue)
        .output
        .lines
        .isEmpty()

    override fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
        TODO("Not yet implemented")
    }

    override fun getBranchNames(): List<String> {
        TODO("Not yet implemented")
    }

    override fun getRemoteBranches(): List<RemoteBranch> {
        TODO("Not yet implemented")
    }

    override fun getRemoteBranchesById(): Map<String, RemoteBranch> {
        TODO("Not yet implemented")
    }

    override fun reset(refName: String): GitClient {
        TODO("Not yet implemented")
    }

    override fun branch(name: String, startPoint: String, force: Boolean): Commit? {
        TODO("Not yet implemented")
    }

    override fun deleteBranches(names: List<String>, force: Boolean): List<String> {
        TODO("Not yet implemented")
    }

    override fun add(filePattern: String): GitClient {
        TODO("Not yet implemented")
    }

    override fun setCommitId(commitId: String) {
        TODO("Not yet implemented")
    }

    override fun commit(message: String, footerLines: Map<String, String>): Commit {
        TODO("Not yet implemented")
    }

    override fun cherryPick(commit: Commit): Commit {
        TODO("Not yet implemented")
    }

    override fun push(refSpecs: List<RefSpec>) {
        TODO("Not yet implemented")
    }

    override fun getRemoteUriOrNull(remoteName: String): String? {
        TODO("Not yet implemented")
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
            .joinToString(GIT_LOG_SEPARATOR)

        return ProcessExecutor()
            .directory(workingDirectory)
            .command(listOf("git", "log") + logArg.toList() + listOf("-z", prettyFormat))
            .destroyOnExit()
            .readOutput(true)
            .execute()
            .also(ProcessResult::requireZeroExitValue)
            .output
            .string
            .split('\u0000')
            .map(CommitParsers::parseCommitLogEntry)
    }

    companion object {
        const val GIT_LOG_SEPARATOR = "»¦«"
        const val GIT_LOG_TRAILER_SEPARATOR = "{^}"
    }
}

private fun ProcessResult.requireZeroExitValue() {
    val exitValue = exitValue
    require(exitValue == 0) { "Command returned $exitValue: ${output.string}" }
}
