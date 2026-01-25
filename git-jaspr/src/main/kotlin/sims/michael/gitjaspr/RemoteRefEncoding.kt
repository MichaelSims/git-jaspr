package sims.michael.gitjaspr

object RemoteRefEncoding {
    const val DEFAULT_REMOTE_BRANCH_PREFIX = "jaspr"
    const val DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX = "jn"

    const val REV_NUM_DELIMITER = "_"

    fun buildRemoteRef(
        commitId: String,
        targetRef: String = DEFAULT_TARGET_REF,
        prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    ): String = RemoteRef(commitId, targetRef, prefix).name()

    fun getCommitIdFromRemoteRef(remoteRef: String, remoteBranchPrefix: String): String? =
        RemoteRef.parse(remoteRef, remoteBranchPrefix)?.commitId

    data class RemoteRef(
        val commitId: String,
        val targetRef: String = DEFAULT_TARGET_REF,
        val prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
        val revisionNum: Int? = null,
        val remoteName: String? = null,
    ) {
        fun name(): String =
            listOfNotBlank(remoteName, prefix, targetRef, commitId).joinToString("/") +
                revisionNum?.let { "$REV_NUM_DELIMITER$it" }.orEmpty()

        companion object {
            fun parse(remoteRef: String, remoteBranchPrefix: String): RemoteRef? {
                val matchResult =
                    "^$remoteBranchPrefix/(.*)/(.*?)(?:${REV_NUM_DELIMITER}(\\d+))?$"
                        .toRegex()
                        .matchEntire(remoteRef)
                return matchResult?.let { result ->
                    val values = result.groupValues
                    RemoteRef(
                        commitId = values[2],
                        targetRef = values[1],
                        remoteBranchPrefix,
                        values.getOrNull(3)?.toIntOrNull(),
                    )
                }
            }
        }
    }

    data class RemoteNamedStackRef(
        val stackName: String,
        val targetRef: String = DEFAULT_TARGET_REF,
        val prefix: String = DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
        val remoteName: String? = null,
    ) {
        fun name(): String =
            listOfNotBlank(remoteName, prefix, targetRef, stackName).joinToString("/")

        companion object {
            fun parse(
                remoteRef: String,
                remoteNamedStackBranchPrefix: String,
            ): RemoteNamedStackRef? {
                val matchResult =
                    "^$remoteNamedStackBranchPrefix/(.*?)/(.*)$".toRegex().matchEntire(remoteRef)
                return matchResult?.let { result ->
                    val values = result.groupValues
                    RemoteNamedStackRef(
                        stackName = values[2],
                        targetRef = values[1],
                        remoteNamedStackBranchPrefix,
                    )
                }
            }
        }
    }
}

private fun listOfNotBlank(vararg strings: String?) =
    strings.filterNotNull().filter(String::isNotBlank)
