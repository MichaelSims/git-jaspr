package sims.michael.gitjaspr

object RemoteRefEncoding {
    const val DEFAULT_REMOTE_BRANCH_PREFIX = "jaspr"
    const val DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX = "jaspr-named"

    const val REV_NUM_DELIMITER = "_"

    fun buildRemoteRef(
        commitId: String,
        targetRef: String = DEFAULT_TARGET_REF,
        prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    ): String = listOf(prefix, targetRef, commitId).joinToString("/")

    // The original target for this stack is "baked in" to the ref name.
    fun buildRemoteNamedStackRef(
        stackName: String,
        targetRef: String = DEFAULT_TARGET_REF,
        prefix: String = DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
    ): String = listOf(prefix, targetRef, stackName).joinToString("/")

    fun getRemoteRefParts(remoteRef: String, remoteBranchPrefix: String): RemoteRefParts? =
        "^$remoteBranchPrefix/(.*)/(.*?)(?:$REV_NUM_DELIMITER(\\d+))?$"
            .toRegex()
            .matchEntire(remoteRef)
            ?.let { result ->
                val values = result.groupValues
                RemoteRefParts(
                    targetRef = values[1],
                    commitId = values[2],
                    values.getOrNull(3)?.toIntOrNull(),
                )
            }

    fun getRemoteNamedStackRefParts(
        remoteRef: String,
        remoteNamedStackBranchPrefix: String,
    ): RemoteNamedStackRefParts? {
        val matchResult =
            "^$remoteNamedStackBranchPrefix/(.*?)/(.*)$".toRegex().matchEntire(remoteRef)
        return matchResult?.let { result ->
            val values = result.groupValues
            RemoteNamedStackRefParts(targetRef = values[1], stackName = values[2])
        }
    }

    fun getCommitIdFromRemoteRef(remoteRef: String, remoteBranchPrefix: String): String? =
        getRemoteRefParts(remoteRef, remoteBranchPrefix)?.commitId

    data class RemoteRefParts(val targetRef: String, val commitId: String, val revisionNum: Int?)

    data class RemoteNamedStackRefParts(val targetRef: String, val stackName: String)
}
