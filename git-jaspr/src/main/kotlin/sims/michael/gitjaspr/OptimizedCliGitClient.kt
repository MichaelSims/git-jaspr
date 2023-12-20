package sims.michael.gitjaspr

import java.io.File

/**
 * An optimized [GitClient] that uses [CliGitClient] for transport operations and [JGitClient] for everything else.
 */
class OptimizedCliGitClient private constructor(
    private val cliGitClient: CliGitClient,
    private val jGitClient: JGitClient,
) : GitClient by jGitClient {

    override fun clone(uri: String, bare: Boolean): GitClient {
        cliGitClient.clone(uri, bare)
        return this
    }

    override fun fetch(remoteName: String) {
        cliGitClient.fetch(remoteName)
    }

    override fun push(refSpecs: List<RefSpec>) {
        cliGitClient.push(refSpecs)
    }

    companion object {
        operator fun invoke(
            workingDirectory: File,
            remoteBranchPrefix: String = RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX,
        ): GitClient {
            val cliGitClient = CliGitClient(workingDirectory, remoteBranchPrefix)
            val jGitClient = JGitClient(workingDirectory, remoteBranchPrefix)
            return OptimizedCliGitClient(cliGitClient, jGitClient)
        }
    }
}
