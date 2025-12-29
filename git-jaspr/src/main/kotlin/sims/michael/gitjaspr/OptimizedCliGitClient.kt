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
