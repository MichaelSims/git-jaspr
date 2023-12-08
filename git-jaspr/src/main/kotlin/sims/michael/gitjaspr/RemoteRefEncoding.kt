package sims.michael.gitjaspr

object RemoteRefEncoding {
    const val DEFAULT_REMOTE_BRANCH_PREFIX = "jaspr"

    const val REV_NUM_DELIMITER = "_"

    fun buildRemoteRef(
        commitId: String,
        targetRef: String = DEFAULT_TARGET_REF,
        prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    ): String = listOf(prefix, targetRef, commitId).joinToString("/")

    fun getRemoteRefParts(remoteRef: String, remoteBranchPrefix: String): RemoteRefParts? =
        "^$remoteBranchPrefix/(.*/)(.*?)(?:$REV_NUM_DELIMITER(\\d+))?$"
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

    fun getCommitIdFromRemoteRef(remoteRef: String, remoteBranchPrefix: String): String? =
        getRemoteRefParts(remoteRef, remoteBranchPrefix)?.commitId

    data class RemoteRefParts(val targetRef: String, val commitId: String, val revisionNum: Int?)
}
